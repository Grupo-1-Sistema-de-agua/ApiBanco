# API Bancos — Guía de integración (Feature 3)

**Proyecto:** Oficina del Agua · Fase 2 · Desarrollo Web 2026
**Contrato:** `bank-payments-api.yaml` (OpenAPI 3.0.3, v1.0.0) — entregado por el docente, **no se modifica**
**Fuentes:** `bank-payments-api.yaml`, diagrama "Oficina de agua" (punto 3), Clase 10 (slides 9 y 13), Clase 11 (slides 14–21)

---

## 1. Qué es y dónde encaja

El "banco" es un **simulador desplegado por el docente en AWS** (API Gateway + Lambda + DynamoDB). Es el mismo para los 4 equipos y recibe la notificación de los pagos cobrados por la Oficina del Agua.

Nuestro código **no implementa** esta API: la **consume**. Es el lado derecho del flujo del diagrama:

```
pagos pendientes (flag) → producer → cola → consumer ──HTTP──► API Banco
                                               │
                                               └─► flag "enviado a banco" + log en DynamoDB
```

Solo el **consumidor** del banco habla con esta API (Clase 10, slide 11: los simuladores se conectan únicamente a los consumidores).

> **Dos cosas que cambian respecto al borrador anterior de Feature 3**
> 1. El banco **no corre en nuestro Docker Compose**: vive en AWS. Se quita `banco-simulador` de la lista de servicios y se reemplaza por una URL configurable (`apiId` lo comparte el docente). Hay una ejecución local opcional con `sam local start-api` en `http://localhost:3000`.
> 2. El cliente se genera como **Feign** (ver sección 3), no como `resttemplate`.

---

## 2. El contrato en una página

### 2.1 Endpoints

| Método | Ruta | `operationId` | Para qué | API key |
|---|---|---|---|---|
| `POST` | `/bank/v1/payments` | `sendBankPayment` | Registrar un pago en el banco | Sí |
| `GET` | `/bank/v1/payments/{idempotencyKey}` | `getBankPayment` | Reconciliar: ¿el banco ya registró este pago? | Sí |
| `GET` | `/bank/v1/health` | `getBankHealth` | Verificar disponibilidad | No |

**Base URL:** `https://{apiId}.execute-api.{region}.amazonaws.com/{stage}` (región por defecto `us-east-1`, stage `prod`).

### 2.2 Headers

| Header | Obligatorio | Uso |
|---|---|---|
| `x-api-key` | Sí (excepto health) | Llave entregada a cada equipo. **Identifica al equipo** |
| `Idempotency-Key` | Sí en el `POST` | Llave de unicidad del pago. 8–64 caracteres, patrón `^[A-Za-z0-9_-]+$` |
| `X-Correlation-Id` | No | Para trazar productor → cola → consumidor → banco. Si no se envía, el banco genera uno |
| `X-Simulation-Scenario` | No (solo pruebas) | Fuerza un escenario de fallo (ver sección 6) |

### 2.3 Request (`BankPaymentRequest`)

| Campo | Tipo | Obligatorio | Notas |
|---|---|---|---|
| `teamCode` | enum | Sí | `EQUIPO_A`…`EQUIPO_D`. **Debe coincidir con la API key** o responde `403 TEAM_MISMATCH` |
| `payer.customerCode` | string (1–50) | Sí | Código del cliente en el monolito |
| `payer.fullName` | string (≤150) | No | |
| `payer.taxId` | string (≤20) | No | NIT o `CF` |
| `amount.valueInCents` | integer | Sí | **Centavos**, entero, 1–99,999,999. Q150.75 → `15075` |
| `amount.currency` | enum | Sí | Solo `GTQ` |
| `transactionDate` | date-time | Sí | ISO-8601 **con zona horaria** |
| `channel` | enum | Sí | `CASH`, `CARD`, `BANK_TRANSFER`, `CHECK` |
| `description` | string (≤140) | No | Concepto que verá el banco |
| `metadata` | map string→string | No | Máx. 10 entradas, valor ≤200. No participa en la detección de duplicados. **Nunca contraseñas ni tokens** |

Ejemplo (el `Idempotency-Key` va en el header, no aquí):

```http
POST /bank/v1/payments
x-api-key: <llave-del-equipo>
Idempotency-Key: EQA-000123
X-Correlation-Id: 7f1c2a9e-1b2c-4d3e-9f00-112233445566
Content-Type: application/json

{
  "teamCode": "EQUIPO_A",
  "payer": { "customerCode": "CLI-0001", "fullName": "Ana López", "taxId": "CF" },
  "amount": { "valueInCents": 15075, "currency": "GTQ" },
  "transactionDate": "2026-10-01T16:15:00Z",
  "channel": "CASH",
  "description": "Servicio de agua 2026-09",
  "metadata": { "sourceSystem": "laravel-oficina-agua" }
}
```

### 2.4 Respuesta exitosa `201` (`BankPaymentReceipt`)

```json
{
  "receiptId": "BNK-20261007-8F3K2Q",
  "idempotencyKey": "EQA-000123",
  "teamCode": "EQUIPO_A",
  "status": "ACCEPTED",
  "amount": { "valueInCents": 15075, "currency": "GTQ" },
  "receivedAt": "2026-10-07T23:14:58Z",
  "valueDate": "2026-10-08"
}
```

`receiptId` es el comprobante del banco: **hay que guardarlo** (junto con el flag y el log).

---

## 3. Generar el cliente (contract-first)

Se copia el YAML **sin tocarlo** a `src/main/resources/openapi/` y se genera con `openapi-generator-maven-plugin` (en la Clase 11 se usó la versión `7.12.0`, con Spring Boot 3.5.6 y JDK 17).

El comentario del propio YAML indica `library = spring-cloud` (cliente **Feign**). La Clase 11 (slide 21) menciona `generatorName=java`. Para este spec conviene seguir lo que dice el YAML; ambas opciones generan un cliente HTTP.

```xml
<execution>
  <id>bank-client</id>
  <goals><goal>generate</goal></goals>
  <configuration>
    <inputSpec>${project.basedir}/src/main/resources/openapi/bank-payments-api.yaml</inputSpec>
    <generatorName>spring</generatorName>
    <library>spring-cloud</library>
    <output>${project.build.directory}/generated-sources/openapi-bank</output>
    <apiPackage>gt.edu.umg.desarrolloweb.pagos.bank.client</apiPackage>
    <modelPackage>gt.edu.umg.desarrolloweb.pagos.bank.dto</modelPackage>
    <modelNameSuffix>Dto</modelNameSuffix>
    <skipValidateSpec>false</skipValidateSpec>
    <configOptions>
      <useSpringBoot3>true</useSpringBoot3>
      <useBeanValidation>true</useBeanValidation>
      <openApiNullable>false</openApiNullable>
      <dateLibrary>java8</dateLibrary>
    </configOptions>
  </configuration>
</execution>
```

Dependencias adicionales (el cliente generado usa Feign): `spring-cloud-starter-openfeign` más el BOM de Spring Cloud compatible con Spring Boot 3.5 (línea `2025.0.x`).

> Las opciones exactas del generador pueden variar entre versiones. Hay que confirmarlas con un `mvn clean compile` y revisar `target/generated-sources/`. Si algo no sirve, **se cambia la configuración del plugin, nunca el YAML ni el código generado**.

**API key:** el título del spec es ASCII a propósito para que la propiedad se llame así:

```yaml
# application.yml  (la llave real va en variable de entorno, nunca en el repo)
bankpaymentssimulator:
  security:
    apiKeyAuth:
      key: ${BANK_API_KEY:cambiar-en-local}
```

**Base URL y timeouts** (recomendados por el contrato: connect 2 s, read 3 s):

```yaml
spring:
  cloud:
    openfeign:
      client:
        config:
          default:
            connect-timeout: 2000
            read-timeout: 3000
bank:
  base-url: ${BANK_BASE_URL:https://REEMPLAZAR_API_ID.execute-api.us-east-1.amazonaws.com/prod}
```

---

## 4. Reglas de oro del contrato

1. **`Idempotency-Key` estable.** Se deriva del pago del monolito (ej. `EQA-000123` = prefijo del equipo + id del pago). Todos los reintentos del mismo pago usan **la misma llave**. Nunca un UUID nuevo por intento.
2. **Montos en centavos, enteros.** Nunca pasar por `double`. Con `BigDecimal`:
   ```java
   long cents = amount.setScale(2, RoundingMode.UNNECESSARY)
                      .movePointRight(2)
                      .longValueExact();   // 150.75 -> 15075
   ```
3. **`teamCode` debe coincidir con la API key.**
4. **Decidir por código HTTP**, no por `message` ni solo por `retryable` (que es una pista).
5. **Timeouts obligatorios** en el cliente: connect 2 s, read 3 s.

---

## 5. Qué hacer con cada respuesta

Esta tabla es el corazón del consumidor.

| Respuesta | `code` | Significado | Acción del consumidor |
|---|---|---|---|
| `201` | — | Pago registrado | **ACK.** Guardar `receiptId`, marcar flag, log `EXITOSO` |
| `409` | `DUPLICATE_PAYMENT` | Mismo pago ya registrado | **ACK como entregado.** Usar `originalReceipt.receiptId`. Log `EXITOSO` |
| `409` | `IDEMPOTENCY_KEY_REUSED` | Misma llave con datos distintos | **Dead-letter**, sin reintento. Es bug del productor |
| `400` | `VALIDATION_ERROR` | Request no cumple el contrato | **Dead-letter**, sin reintento. Revisar `details[]` y el mapeo |
| `403` | `TEAM_MISMATCH` o API key inválida | Configuración | **Dead-letter** y alertar. Revisar key/teamCode |
| `422` | `AMOUNT_LIMIT_EXCEEDED` | `valueInCents` > 2,500,000 (Q25,000.00) | **Dead-letter**, sin reintento |
| `422` | `PAYMENT_DATE_IN_FUTURE` | Fecha > 5 min en el futuro | **Dead-letter**, sin reintento |
| `422` | `UNSUPPORTED_CHANNEL` | `channel = CHECK` | **Dead-letter**, sin reintento |
| `429` | `RATE_LIMITED` | Demasiadas peticiones | **Reintentar** respetando `Retry-After` (si viene) o con backoff |
| `500` | `INTERNAL_ERROR` | Error interno; el pago **no** se registró | **Reintentar** con backoff |
| `502` | — | Error API Gateway ↔ Lambda | **Reintentar** con backoff |
| `503` | `SERVICE_UNAVAILABLE` | Banco no disponible; el pago **no** se registró | **Reintentar** respetando `Retry-After` |
| `504` | — | API Gateway cortó la petición; **no se sabe** si se registró | **Reintentar**: el `409` lo resolverá |
| Timeout / error de red | — | No se sabe si se registró | **Reintentar** (o reconciliar con `GET`, ver 5.2) |

### 5.1 Los errores de API Gateway no tienen el formato `BankError`

Los `403` por API key, `429`, `502` y `504` generados por API Gateway devuelven un body como `{"message":"Forbidden"}` o `{"message":"Endpoint request timed out"}`. El manejo de errores debe **decidir por el status HTTP** y tolerar que el body no traiga `code`, `retryable` ni `correlationId`.

### 5.2 Reconciliación después de un timeout

Si el `POST` no obtuvo respuesta, el banco pudo haber registrado el pago o no. Hay dos caminos válidos:

- **Simple:** reintentar el `POST` con la misma llave. Si ya estaba registrado, responde `409 DUPLICATE_PAYMENT` y se trata como éxito.
- **Explícito:** `GET /bank/v1/payments/{idempotencyKey}` → `200` (ya está, guardar comprobante) o `404 PAYMENT_NOT_FOUND` (no está, reintentar el `POST`).

### 5.3 Reintentos y dead-letter (con RabbitMQ)

Se reintenta **solo** lo marcado como reintentable. Todo lo demás va directo a la DLQ, sin gastar intentos.

```
Reintentables:      429, 500, 502, 503, 504, timeout/IO
No reintentables:   400, 403, 409 IDEMPOTENCY_KEY_REUSED, 422   → DLQ inmediata
Terminal OK:        201, 409 DUPLICATE_PAYMENT                   → ACK
```

Sugerencia: backoff exponencial (ej. 1 s → 2 s → 4 s…, con tope y máximo de intentos) y, si llega `Retry-After`, esperar al menos ese tiempo. Con `spring-retry`/`spring.rabbitmq.listener.simple.retry` los no reintentables se pueden marcar con `AmqpRejectAndDontRequeueException` para que vayan a la DLQ de inmediato.

---

## 6. Escenarios de prueba (`X-Simulation-Scenario`)

El simulador inyecta retardos y fallos **a propósito**. En modo `AUTO` (por defecto) son aleatorios; para pruebas determinísticas se fuerza un escenario con el header:

| Valor | Qué simula | Qué debe demostrar nuestro consumidor |
|---|---|---|
| `AUTO` | Comportamiento aleatorio | Funcionamiento general |
| `SUCCESS` | Sin fallos ni retardos | Camino feliz: `201`, flag y log |
| `SLOW` | Responde bien tras 1.5–2.5 s | El timeout de 3 s **no** corta una respuesta válida |
| `TIMEOUT` | Tarda 4–8 s **sin registrar** | Corta a los 3 s, reintenta, termina registrando |
| `LOST_ACK` | **Registra** y luego tarda 4–8 s | Timeout → reintento → `409 DUPLICATE_PAYMENT` → tratado como éxito. **Es el caso estrella de idempotencia** |
| `ERROR_500` | Error interno sin registrar | Reintento con backoff |
| `ERROR_503` | No disponible, con `Retry-After` | Respeta `Retry-After` |
| `RATE_LIMIT` | `429` con `Retry-After` | Respeta `Retry-After` |

> **Cuidado con la demo:** el docente **puede deshabilitar este header** (por ejemplo el día de la demo); entonces se ignora y se usa `AUTO`. Conviene tener un segundo mecanismo para mostrar la DLQ, como un perfil de Spring cuya `bank.base-url` apunte a una URL inexistente. Así se simula "banco caído" sin depender del header. Además, el header debe estar detrás de una propiedad (`bank.simulation-scenario`) y **vacío por defecto**, nunca fijo en el código.

---

## 7. Mapeo: pago del monolito → `BankPaymentRequest`

> Estos orígenes dependen del esquema real de la tabla de pagos del monolito (CodeIgniter/Laravel). **Hay que confirmarlos con el experto residente.** Aquí se muestran los destinos y las transformaciones obligatorias.

| Campo del banco | De dónde sale | Transformación / cuidado |
|---|---|---|
| Header `Idempotency-Key` | id del pago | `<prefijo equipo>-<id con ceros>`, ej. `EQA-000123`. Mín. 8 caracteres, solo `A-Za-z0-9_-` |
| `teamCode` | configuración | Variable de entorno; debe coincidir con la API key |
| `payer.customerCode` | cliente del pago | Ej. `CLI-0001` |
| `payer.fullName` | cliente | Opcional, máx. 150 |
| `payer.taxId` | cliente | NIT o `CF` si no tiene |
| `amount.valueInCents` | monto del pago | `BigDecimal` → centavos con `longValueExact()`. Validar `1 ≤ x ≤ 2,500,000` antes de enviar |
| `amount.currency` | fijo | `GTQ` |
| `transactionDate` | fecha del pago | Si en MySQL es un `DATETIME` sin zona, interpretarlo en `America/Guatemala` (UTC-6) y enviar con offset o en UTC |
| `channel` | forma de pago | Mapear a `CASH`/`CARD`/`BANK_TRANSFER`. Un cheque (`CHECK`) será rechazado con `422`: decidir si el productor ni lo publica o si se deja ir a la DLQ |
| `description` | período/concepto | Máx. 140, ej. `Servicio de agua 2026-09` |
| `metadata.sourceSystem` | fijo | Ej. `laravel-oficina-agua` o `codeigniter-oficina-agua` |

**Validaciones previas en el productor/consumidor** (evitan gastar un viaje de red en algo que el banco va a rechazar de todos modos): monto dentro del rango, fecha no futura, canal soportado, `Idempotency-Key` con formato válido.

---

## 8. Lo que debe quedar registrado

| Dónde | Qué | Cuándo |
|---|---|---|
| Flag "pago enviado a banco" (almacén propio de Fase 2, **no** en el monolito) | `true` + `receiptId` | Solo en `201` o `409 DUPLICATE_PAYMENT` |
| DynamoDB Logs | `paymentId`, `destino=BANCO`, `estado` (`EXITOSO`/`FALLIDO`), `intento`, `httpStatus`, `codigoError`, `correlationId`, `receiptId`, `fecha` | En **cada** intento, éxito o fallo |
| Logs de aplicación | `X-Correlation-Id` en cada línea | Siempre |

Reglas de seguridad al loguear: **no** registrar la `x-api-key` ni el body completo con datos personales; **no** devolver stack traces (`server.error.include-stacktrace: never`).

---

## 9. Checklist de implementación

- [ ] Copiar `bank-payments-api.yaml` a `src/main/resources/openapi/` (sin editar)
- [ ] Configurar `openapi-generator-maven-plugin` y verificar que compila (`mvn clean compile`)
- [ ] Salida generada en `target/` (no se edita ni se versiona)
- [ ] `BANK_API_KEY`, `BANK_BASE_URL` y `TEAM_CODE` como variables de entorno (`${VAR:default}`)
- [ ] Timeouts connect 2 s / read 3 s
- [ ] `Idempotency-Key` estable y con formato válido
- [ ] Conversión a centavos con `BigDecimal`
- [ ] Clasificador de respuestas: ACK / reintentar / DLQ según la tabla de la sección 5
- [ ] `409 DUPLICATE_PAYMENT` tratado como éxito
- [ ] Manejo de errores sin formato `BankError` (los de API Gateway)
- [ ] Respeto de `Retry-After`
- [ ] Guardar `receiptId`, flag y log por intento
- [ ] Probar `SUCCESS`, `SLOW`, `TIMEOUT`, `LOST_ACK`, `ERROR_500`, `ERROR_503`, `RATE_LIMIT`
- [ ] Mecanismo alterno de "banco caído" para la demo

---

## 10. Dudas a confirmar con el docente

1. ¿Cuándo se comparte el **`apiId`** y la **API key** del equipo? (el spec trae `REEMPLAZAR_API_ID`)
2. ¿Se genera el cliente con `generatorName=spring` + `library=spring-cloud` (como dice el YAML) o con `generatorName=java` (como la Clase 11)?
3. ¿El header `X-Simulation-Scenario` estará habilitado el día de la demo?
4. ¿Qué hacemos con los pagos por **cheque**? El banco los rechaza con `422`.
5. ¿La **Lambda** del diagrama es el propio simulador del banco en AWS o un componente que debemos construir nosotros?
6. ¿Sigue vigente enviar también al **ERP**? (ver apéndice)

---

## Apéndice — Banco vs. ERP (por qué no se pueden reusar los mapeos)

El docente hizo los dos contratos distintos a propósito, para que cada consumidor tenga su propio mapeo.

| Aspecto | Banco | ERP |
|---|---|---|
| Idioma / estilo | Inglés, camelCase | Español, snake_case |
| Unicidad | Header `Idempotency-Key` | Body: `documento_origen.numero` |
| Monto | Entero en centavos (`15075`) | Texto con 2 decimales (`"150.75"`) |
| Estructura del pago | Un monto total | `total` + `detalle[]` por rubro; la suma debe cuadrar |
| Período | No lo pide | `periodo_fiscal { anio, mes }` |
| Duplicado | `409 DUPLICATE_PAYMENT` | `409 DOCUMENTO_DUPLICADO` |
| Formato de error | `status`, `code`, `retryable`… | `estado_http`, `codigo_error`, `reintentable`… |
| Rutas | `/bank/v1/payments` | `/erp/v1/pagos` |
| Perfil de fallos | Más fallos | Falla menos pero es **más lento** |

Se parecen en: autenticación por `x-api-key`, reglas de reintento (`429/500/502/503/504` sí; `400/422` no), timeouts recomendados, header `X-Correlation-Id` y los escenarios de simulación.
