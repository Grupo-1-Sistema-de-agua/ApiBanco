# Oficina del Agua — Feature 3: envío de pagos al banco

Flujo completo: los pagos pendientes del monolito viajan por **RabbitMQ**, el
**consumidor** los envía al **banco simulado** y se guarda un **flag + log en
DynamoDB** para no enviarlos dos veces.

> Guía rápida de términos, comandos y preguntas de defensa:
> [`README-palabras-clave.md`](README-palabras-clave.md).

## Arquitectura

```mermaid
flowchart LR
    DB[(MariaDB del monolito<br/>Tb_Pagos · puerto 3307)] -->|SELECT solo lectura| P[pagos-producer<br/>@Scheduled]
    P -->|1. ¿ya enviado?| DY[(DynamoDB Local<br/>pagos_enviados)]
    P -->|2. publica JSON| RMQ{{RabbitMQ<br/>pagos.exchange}}
    RMQ -->|routing key: banco| Q[pagos.banco]
    Q --> C[pagos-consumer]
    C -->|3. POST /bank-payments| BANK[Banco simulado<br/>API Gateway]
    BANK -->|201 + receiptId| C
    C -->|4. flag + log| DY
    C -->|falla tras 5 intentos| DLQ[pagos.banco.dlq]
```

### Qué hace cada pieza

| Pieza | Responsabilidad |
|---|---|
| **pagos-producer** | Cada 30 s (configurable; en la demo, 15 s) lee `Tb_Pagos` (solo lectura), arma el mensaje, omite los pagos que ya tienen flag y publica el resto en RabbitMQ. |
| **RabbitMQ** | Cola `pagos.banco` con exchange directo `pagos.exchange`. Si algo falla definitivamente, el mensaje va a `pagos.banco.dlq`. |
| **pagos-consumer** | Escucha la cola, mapea al contrato del banco, llama a `POST /bank-payments` con reintentos, y guarda el resultado. |
| **DynamoDB Local** | Tabla `pagos_enviados` (flag de idempotencia) y `pagos_log` (historial de intentos). |
| **Banco simulado** | API del docente. Responde `201` con `receiptId`; se puede forzar un error con el header `X-Simulation-Scenario`. |

### Idempotencia (lo más importante)

- La llave es `idempotencyKey = "EQA-" + id del pago` (ej. `EQA-000009`).
- El consumidor escribe el flag **solo** si el banco responde `201` o `409 DUPLICATE_PAYMENT`.
- Si el mensaje se repite, el consumidor responde "ya estaba enviado" y no llama al banco.
- El productor también consulta el flag antes de publicar.

### Reintentos

- Solo `BancoReintentableException` (timeout, 5xx, 429) se reintenta.
- 5 intentos: espera 1 s, 2 s, 4 s y 8 s entre ellos.
- Un error de datos (400/422) **no** se reintenta: va directo a la DLQ.
- Si se agotan los intentos, el mensaje se rechaza sin reencolar y RabbitMQ lo
  mueve a `pagos.banco.dlq`.
- **El flag no se escribe**, así que el productor volverá a publicarlo en un
  próximo ciclo/arranque.

> Spring Boot 3.5 ya no tiene la propiedad `retryable-exceptions`, por eso la
> política está en `consumer/config/RetryConfig.java`.

---

## Requisitos

- Docker Desktop
- La MariaDB del monolito corriendo y expuesta en el puerto **3307**
  (contenedor `agua_monolito_db`, volumen `sistema_oficina_de_agua_db_data`)
- Usuario de **solo lectura** sobre `agua_db`

## Configuración

1. Copia `.env.example` a `.env`:

```bat
copy .env.example .env
```

2. Llena los valores reales en `.env` (ese archivo **no** se sube al repositorio):

```env
BANK_BASE_URL=https://TU_API_ID.execute-api.us-east-1.amazonaws.com/prod
BANK_API_KEY=tu_api_key
BANK_SCENARIO=SUCCESS
TEAM_CODE=EQUIPO_A
DB_USER=agua_ro
DB_PASSWORD=tu_password
PRODUCER_LIMITE=20
PRODUCER_INTERVALO_MS=30000
```

## Levantar todo

```bat
docker compose up -d --build
```

La primera vez tarda varios minutos (descarga dependencias dentro de Docker).
Después, si no cambió el código, basta con:

```bat
docker compose up -d
```

| Servicio | URL / puerto |
|---|---|
| RabbitMQ (consola) | http://localhost:15672 — `guest` / `guest` |
| DynamoDB Local | http://localhost:8000 |
| pagos-consumer | http://localhost:8082 |
| pagos-producer | http://localhost:8081 |

> En el `.env` de la demo dejamos `PRODUCER_LIMITE=1` y
> `PRODUCER_INTERVALO_MS=15000` para ver un pago cada 15 segundos.
> Para producción se puede usar `20` y `30000`.

Ver logs:

```bat
docker compose logs -f pagos-producer
docker compose logs -f pagos-consumer
```

Detener:

```bat
docker compose down
```

> `docker compose down` **no** borra los datos (volúmenes). Para empezar de cero:
> `docker compose down -v` (esto borra los flags y los logs de DynamoDB).

---

## Guion de demo (24 de octubre)

### 1. Arranque limpio

```bat
docker compose up -d
docker compose logs -f pagos-producer
```

> Si cambió el código desde la última vez, usa `docker compose up -d --build`.

Se debe ver:

```
Publicado en RabbitMQ: key=EQA-0000XX paymentId=XX monto=... canal=...
```

Y en el consumidor:

```
Pago recibido: paymentId=XX idempotencyKey=EQA-0000XX
Flag guardado en DynamoDB: EQA-0000XX -> enviado
ENTREGADO al banco. receiptId=BNK-...
```

### 2. Idempotencia (no se envía dos veces)

Desde la consola de RabbitMQ, publica **otra vez** un pago que ya fue enviado.
Usa `content_type = application/json` y este payload (la clave es lo que importa):

```json
{
  "idempotencyKey": "EQA-000012",
  "paymentId": 12,
  "customerCode": "CLI-0001",
  "fullName": "Cliente de Prueba",
  "taxId": "CF",
  "amount": "1010.00",
  "channel": "BANK_TRANSFER",
  "description": "Servicio de agua 2026-01",
  "transactionDate": "2026-10-24T10:00:00-06:00"
}
```

El consumidor responde:

```
ya estaba enviado al banco. Se ignora.
```

No se llama al banco y el flag no cambia. También puedes reiniciar el
productor: los pagos con flag no se vuelven a publicar.

### 3. Banco caído → reintentos → DLQ

En `.env` cambia:

```env
BANK_SCENARIO=ERROR_500
```

Recrea el consumidor:

```bat
docker compose up -d --force-recreate pagos-consumer
```

Publica un mensaje de prueba desde la consola de RabbitMQ
(http://localhost:15672 → *Queues* → `pagos.banco` → *Publish message*),
con `content_type = application/json` y este payload:

```json
{
  "idempotencyKey": "EQA-DEMO-FAIL",
  "paymentId": 999999,
  "customerCode": "CLI-0001",
  "fullName": "Cliente de Prueba",
  "taxId": "CF",
  "amount": "100.00",
  "channel": "CASH",
  "description": "Servicio de agua 2026-10",
  "transactionDate": "2026-10-24T10:00:00-06:00"
}
```

En el log se ven los **5 intentos** y luego el mensaje aparece en la cola
`pagos.banco.dlq`. En DynamoDB **no** aparece el flag `EQA-DEMO-FAIL`.

### 4. Recuperación

Vuelve a `BANK_SCENARIO=SUCCESS`, recrea el consumidor y publica **el mismo
mensaje** otra vez desde la consola. Ahora el banco responde `201`, el
consumidor guarda el flag y el log, y el pago queda entregado.

---

## Estructura

```
ApiBanco/
├─ docker-compose.yml
├─ .env.example          # plantilla (sin secretos)
├─ pagos-producer/       # lee MariaDB y publica en RabbitMQ
│  ├─ Dockerfile
│  └─ src/main/java/.../producer/
│     ├─ config/         # RabbitConfig, DynamoConfig
│     ├─ repository/     # consulta SQL de solo lectura
│     └─ service/        # PagoMapper, FlagEnviadosService, PublicadorPagos
└─ pagos-consumer-banco/ # escucha RabbitMQ y llama al banco
   ├─ Dockerfile
   ├─ src/main/resources/openapi/bank-payments-api.yaml   # contrato (no se edita)
   └─ src/main/java/.../consumer/
      ├─ config/         # RetryConfig (solo 5xx/timeout se reintentan)
      ├─ listener/       # PagoBancoListener (reintentos y DLQ)
      └─ service/        # BancoService, RegistroPagoService
```

## Notas

- El monolito **no se modifica**: el productor solo hace `SELECT`.
- El `bank-payments-api.yaml` no se edita; el cliente se genera en `target/`.
- Las credenciales viven en `.env` (ignorado por git), nunca en el código.
- Puertos elegidos para no chocar con el monolito: consumidor en **8082**
  (phpMyAdmin usa 8080) y DynamoDB en **8000** (el monolito usa 8000, no pueden
  correr juntos).

## Problemas comunes

| Síntoma | Causa / solución |
|---|---|
| `Access denied for user 'agua_ro'` | `.env` con contraseña incorrecta o usuario sin `GRANT SELECT`. |
| El productor no publica nada | Todos los pagos ya tienen flag, o `PRODUCER_LIMITE=0`. Revisa `pagos_enviados`. |
| `Connection refused` a RabbitMQ | El contenedor no está sano; `docker compose ps` y `docker compose logs rabbitmq`. |
| El consumidor no arranca | DynamoDB aún no está listo; `docker compose restart pagos-consumer`. |
| Mensajes acumulados en `pagos.banco.dlq` | Fueron fallos definitivos. Revisa `pagos_log` y republica si corresponde. |
