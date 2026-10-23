# Pruebas E2E — Feature 3

Evidencia para **SDGA-114**. Las pruebas se ejecutaron **manualmente** contra el
stack real de Docker (RabbitMQ + DynamoDB Local + consumidor + banco simulado),
no con mocks.

> Los dos tests JUnit del repositorio (`*ApplicationTests`) son *smoke tests*:
> solo verifican que el contexto de Spring carga. La cobertura del flujo está
> en esta guía y en los logs del ensayo.

## 1. Entorno

```bat
cd C:\Users\mario\Desktop\ApiBanco
docker compose up -d
docker compose ps
```

| Componente | URL / puerto |
|---|---|
| RabbitMQ (UI) | http://localhost:15672 (`guest` / `guest`) |
| DynamoDB Local | http://localhost:8000 |
| Consumidor | http://localhost:8082 |
| Productor | http://localhost:8081 |
| MariaDB del monolito | `localhost:3307` (solo lectura) |

Configuración base del `.env`:

```
BANK_SCENARIO=SUCCESS
PRODUCER_LIMITE=1
PRODUCER_INTERVALO_MS=15000
```

## 2. Resumen de escenarios

| # | Escenario | Entrada | Resultado esperado | Resultado observado | Estado |
|---|---|---|---|---|---|
| 1 | Éxito | Pago real del productor | 201 + flag + log `EXITOSO` | `intento=1`, receipt `BNK-20261022-5VHTEL` | ✅ |
| 2 | Duplicado | Republicar `EQA-000014` | Se ignora, sin llamar al banco | `El pago ... ya estaba enviado. Se ignora.` | ✅ |
| 3 | Banco 500 | Pago con `ERROR_500` | 5 intentos, DLQ, **sin** flag | Intentos 1–5 `FALLIDO`, DLQ = 1 | ✅ |
| 4 | Banco 400 | `fullName` > 150 caracteres | 1 intento, DLQ | 1 intento, DLQ | ✅ |
| 5 | Recuperación | `SUCCESS` + republicar el mismo pago | 201, `intento=6`, flag | `intento=6 EXITOSO`, receipt `BNK-20261022-Z87SUS` | ✅ |

Pagos reales entregados durante el ensayo:

| Pago | Monto | Canal | Receipt |
|---|---|---|---|
| 14 | 500.00 | `BANK_TRANSFER` | `BNK-20261022-5VHTEL` |
| 15 | 750.00 | `CASH` | `BNK-20261022-GL1QHU` |
| 16 | — | — | `BNK-20261022-IXK2W7` |

## 3. Escenario 1 — Éxito

1. `docker compose up -d`
2. Esperar el primer ciclo del productor (15 s en la demo).
3. Verificar:

```bat
docker compose logs --tail 20 pagos-producer
docker compose logs --tail 20 pagos-consumer
```

Salida esperada:

```
Publicado en RabbitMQ: key=EQA-000014 paymentId=14 monto=500.00 canal=BANK_TRANSFER
Pago recibido: paymentId=14 idempotencyKey=EQA-000014
Flag guardado en DynamoDB: EQA-000014 -> enviado
Log guardado en DynamoDB: EQA-000014 intento=1 estado=EXITOSO
ENTREGADO al banco. receiptId=BNK-20261022-5VHTEL
```

## 4. Escenario 2 — Duplicado (idempotencia)

Publicar de nuevo un pago ya enviado. Desde la UI de RabbitMQ:

**Queues → `pagos.banco` → Publish message**, con este payload:

```json
{
  "idempotencyKey": "EQA-000014",
  "paymentId": 14,
  "customerCode": "CLI-0001",
  "fullName": "Cliente de Prueba",
  "taxId": "CF",
  "amount": "500.00",
  "channel": "BANK_TRANSFER",
  "description": "Servicio de agua 2026-01",
  "transactionDate": "2026-10-24T10:00:00-06:00"
}
```

O por HTTP:

```bat
curl -s -u guest:guest -H "Content-Type: application/json" ^
  --data-binary @dup.json ^
  http://localhost:15672/api/exchanges/%2F/pagos.exchange/publish
```

Salida esperada en el consumidor:

```
Pago recibido: paymentId=14 idempotencyKey=EQA-000014
El pago EQA-000014 ya estaba enviado al banco. Se ignora.
```

No se llama al banco y no se duplica el flag.

## 5. Escenario 3 — Banco caído (500)

1. Cambiar en `.env`:

```
BANK_SCENARIO=ERROR_500
```

2. Recrear solo el consumidor:

```bat
docker compose up -d --force-recreate pagos-consumer
```

3. Publicar un pago de prueba (clave nueva, por ejemplo `EQA-DEMO-FAIL`).
4. Esperar ~25 s (esperas de 1, 2, 4 y 8 s) y verificar:

```bat
docker compose logs --tail 30 pagos-consumer
docker exec rabbitmq rabbitmqctl list_queues name messages
```

Esperado:

```
Log guardado en DynamoDB: EQA-DEMO-FAIL intento=1 estado=FALLIDO
...
Log guardado en DynamoDB: EQA-DEMO-FAIL intento=5 estado=FALLIDO
Rechazo ... -> cola de errores
pagos.banco.dlq   1
```

Y **sin** flag en `pagos_enviados`.

## 6. Escenario 4 — Error de datos (400)

Con `BANK_SCENARIO=SUCCESS`, publicar un pago con `fullName` de más de 150
caracteres:

Esperado:

```
Log guardado en DynamoDB: EQA-... intento=1 estado=FALLIDO
Rechazo del banco. status=400 code=VALIDATION_ERROR -> cola de errores
```

**Un solo intento**: un 400 no se reintenta.

> Este escenario encontró un bug: en Spring Boot 3.5 la propiedad
> `retryable-exceptions` ya no existe, así que el 400 se reintentaba 5 veces.
> Se corrigió con `RetryConfig.java` (`SimpleRetryPolicy`). Verificado: 400 = 1
> intento, 500 = 5 intentos.

## 7. Escenario 5 — Recuperación

1. Volver a `.env`:

```
BANK_SCENARIO=SUCCESS
```

2. Recrear el consumidor:

```bat
docker compose up -d --force-recreate pagos-consumer
```

3. Republicar el mismo pago que falló (`EQA-DEMO-FAIL`).
4. Verificar:

```
Flag guardado en DynamoDB: EQA-DEMO-FAIL -> enviado
Log guardado en DynamoDB: EQA-DEMO-FAIL intento=6 estado=EXITOSO
ENTREGADO al banco. receiptId=BNK-...
```

El contador de intentos continúa (6), lo que demuestra que el historial se
conserva entre ejecuciones.

## 8. Limpieza después de las pruebas

```bat
:: Vaciar la cola de errores
docker exec rabbitmq rabbitmqctl purge_queue pagos.banco.dlq

:: Apagar el stack (los datos quedan en los volúmenes)
docker compose down
```

Si se usaron claves de ensayo (`EQA-REHEARSAL-*`), se pueden borrar sus flags
con `aws-cli delete-item` para dejar la demo limpia.

## 9. Resultado

| Criterio | Estado |
|---|---|
| Flujo feliz extremo a extremo | ✅ |
| Duplicado ignorado sin llamar al banco | ✅ |
| 500 con 5 reintentos y DLQ | ✅ |
| 400 sin reintentos y DLQ | ✅ |
| Recuperación tras fallo | ✅ |
| Flag solo en éxito | ✅ |

**Limitación:** no hay pruebas automatizadas del flujo; los JUnit actuales solo
cargan el contexto. Queda como mejora futura (Testcontainers + WireMock).
