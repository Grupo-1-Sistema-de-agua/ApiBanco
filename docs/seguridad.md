# Seguridad y configuración — SDGA-116

Este documento describe cómo se manejan credenciales, permisos y datos en la
Feature 3, y qué queda pendiente para un despliegue real.

## 1. Principios

| Principio | Cómo se cumple |
|---|---|
| **No modificar el monolito** | El productor solo hace `SELECT` sobre `Tb_Pagos`; los flags viven en DynamoDB |
| **Mínimo privilegio** | Usuario `agua_ro` con `GRANT SELECT`; sin `INSERT`, `UPDATE` ni `DELETE` |
| **Secretos fuera del repositorio** | Todo va en `.env` (ignorado por git); el repo solo tiene `.env.example` |
| **No filtrar detalles internos** | `server.error.include-stacktrace: never` en ambos servicios |
| **Datos mínimos en logs** | Se registran claves, intentos y recibos; nunca nombre ni NIT del cliente |

## 2. Variables de entorno

| Variable | La usa | Valor de demo | Producción |
|---|---|---|---|
| `BANK_BASE_URL` | Consumidor | URL del banco simulado | URL HTTPS real |
| `BANK_API_KEY` | Consumidor | Llave entregada por el docente | Gestor de secretos |
| `BANK_SCENARIO` | Consumidor | `SUCCESS` / `ERROR_500` | No se usa |
| `TEAM_CODE` | Consumidor | `EQUIPO_A` | Código real del equipo |
| `DB_URL` | Productor | `jdbc:mariadb://host.docker.internal:3307/agua_db` | Host interno |
| `DB_USER` | Productor | `agua_ro` | Usuario de solo lectura |
| `DB_PASSWORD` | Productor | En `.env` | Gestor de secretos |
| `RABBIT_USER` / `RABBIT_PASSWORD` | Ambos | `guest` / `guest` | Usuario dedicado |
| `DYNAMO_ENDPOINT` | Ambos | `http://dynamodb-local:8000` | Endpoint de AWS |
| `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` | Ambos | `dummy` | Rol IAM (sin claves) |
| `AWS_REGION` | Ambos | `us-east-1` | Región real |
| `PRODUCER_LIMITE` / `PRODUCER_INTERVALO_MS` | Productor | `1` / `15000` en la demo | Según operación |

Reglas:

- `.env` está en `.gitignore` y **nunca** se commitea ni se muestra en capturas.
- `.env.example` es la plantilla, sin valores reales.
- Si una llave se filtra, se rota con el docente/equipo y se actualiza `.env`.
- Los valores no se escriben en el código ni en los `application.yaml`.

## 3. Acceso a MariaDB (solo lectura)

El productor se conecta a la base del monolito con un usuario restringido:

```sql
CREATE USER 'agua_ro'@'%' IDENTIFIED BY '<password>';
GRANT SELECT ON agua_db.* TO 'agua_ro'@'%';
FLUSH PRIVILEGES;
```

Además, en `pagos-producer/src/main/resources/application.yaml`:

```yaml
hikari:
  read-only: true
  maximum-pool-size: 3
jpa:
  hibernate:
    ddl-auto: validate
```

- `read-only: true` refuerza el SELECT a nivel de conexión.
- `ddl-auto: validate` impide que Hibernate cree o altere tablas.
- El pool es pequeño (3) para no cargar la base del monolito.
- El puerto `3307` es solo para la demo local; en producción debe estar
  detrás de firewall/VPN, nunca expuesto a Internet.

## 4. RabbitMQ

- En la demo usa `guest`/`guest`, que **solo** es aceptable en local.
- El puerto `15672` (consola) muestra colas y **contenido de mensajes**; no debe
  publicarse fuera de la red local.
- Los mensajes incluyen `fullName` y `taxId` (datos personales). Cualquiera con
  acceso a la consola puede verlos: restringir el acceso.
- La cola `pagos.banco` es durable; la DLQ `pagos.banco.dlq` conserva los
  rechazos definitivos para revisión.
- Para producción: usuario dedicado con permisos solo sobre el vhost/colas,
  TLS (`amqps`) y rotación de credenciales.

## 5. API del banco

- La autenticación es el header **`x-api-key`**, tomado de `BANK_API_KEY`.
- La llave no se escribe en el repositorio ni en el contrato OpenAPI.
- `BANK_BASE_URL` debe ser HTTPS; el cliente Feign usa timeouts de 2 s
  (conexión) y 3 s (lectura) para no colgar al consumidor.
- `bank-payments-api.yaml` es el contrato del docente: **no se edita**.
- Un `401/403` del banco no se reintenta: se registra como fallo y va a la DLQ.

## 6. DynamoDB

- En local se usan credenciales `dummy` porque DynamoDB Local no valida IAM.
- `pagos_enviados` guarda: `idempotencyKey`, `paymentId`, `enviado`,
  `receiptId`, `fecha`.
- `pagos_log` guarda: `idempotencyKey`, `momento`, `paymentId`, `destino`,
  `estado`, `intento`, `httpStatus`, `codigoError`, `correlationId`,
  `receiptId`.
- **No** se guardan `fullName` ni `taxId` en DynamoDB.
- Para AWS real: rol IAM con solo `GetItem`, `PutItem` y `Query` sobre esas dos
  tablas; nada de claves de root en el `.env`.

## 7. Superficie expuesta

| Puerto | Servicio | Recomendación |
|---|---|---|
| `8081` | `pagos-producer` | Solo local; no expone endpoints de negocio |
| `8082` | `pagos-consumer` | Solo local; no expone endpoints de negocio |
| `15672` | Consola RabbitMQ | No publicar; cambiar `guest` en producción |
| `8000` | DynamoDB Local | Solo local; en AWS no se expone |
| `3307` | MariaDB del monolito | Solo local; firewall/VPN en producción |

Los servicios se comunican dentro de la red de Docker por nombre
(`rabbitmq`, `dynamodb-local`), sin salir a Internet.

## 8. Datos y privacidad

- El mensaje y la petición al banco llevan `fullName` y `taxId`; son necesarios
  para el contrato, pero no se registran en logs ni en DynamoDB.
- Los logs muestran únicamente `paymentId`, `idempotencyKey`, `intento`,
  `estado`, `httpStatus`, `codigoError` y `receiptId`.
- Los volúmenes de la demo (`rabbitmq_data`, `dynamodb_data`) se pueden borrar
  al terminar: `docker compose down -v`.

## 9. Verificación rápida

```bat
:: .env y .vscode no deben aparecer como rastreados
git check-ignore -v .env .vscode

:: target/ tampoco se sube (cada módulo lo ignora)
git check-ignore -v pagos-producer/target pagos-consumer-banco/target
```

Salida esperada: una línea por cada ruta, con la regla que la ignora.

## 10. Checklist SDGA-116

- [x] `.env` ignorado por git
- [x] `.env.example` sin secretos
- [x] `.vscode/` ignorado
- [x] `target/` ignorado en cada módulo
- [x] Usuario de MariaDB con `GRANT SELECT`
- [x] Conexión Hikari en modo solo lectura
- [x] API key del banco por variable de entorno
- [x] Sin stacktraces en las respuestas
- [x] Logs sin datos personales
- [x] DynamoDB sin `fullName` ni `taxId`
- [ ] Usuario dedicado y TLS en RabbitMQ (pendiente para producción)
- [ ] Rol IAM en AWS real (pendiente para producción)
