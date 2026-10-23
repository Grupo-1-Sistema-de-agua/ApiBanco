# Ensayo general — SDGA-117

Registro del ensayo completo de la Feature 3, previo a la demo del **24/10/2026**.
Se ejecutó contra el stack real de Docker, sin mocks.

## 1. Objetivo

Validar de punta a punta el flujo:

**MariaDB (solo lectura) → productor → RabbitMQ → consumidor → banco simulado → DynamoDB (flag + log)**

y confirmar que los cinco escenarios de la demo funcionan en el orden previsto.

## 2. Fecha y entorno

| Dato | Valor |
|---|---|
| Fecha del ensayo | viernes **23/10/2026** |
| Sistema | Windows, Docker Desktop |
| Servicios | `rabbitmq`, `dynamodb-local`, `pagos-producer`, `pagos-consumer` |
| Configuración | `BANK_SCENARIO=SUCCESS`, `PRODUCER_LIMITE=1`, `PRODUCER_INTERVALO_MS=15000` |
| MariaDB del monolito | `localhost:3307`, usuario `agua_ro` (solo lectura) |
| Pagos reservados para la demo | **17 y 18** (no se tocaron en el ensayo) |

> Durante el ensayo se usaron los pagos **14, 15 y 16**, que ya estaban
> registrados en el monolito, para no consumir los pagos de la demo.

## 3. Cronología de lo ejecutado

| # | Acción | Resultado observado | Tiempo aprox. |
|---|---|---|---|
| 1 | `docker compose up -d` | 4 servicios arriba; RabbitMQ y DynamoDB `healthy` | 30–40 s |
| 2 | Primer ciclo del productor | Publica el pago 14 | +5–20 s |
| 3 | Consumidor procesa el 14 | `201`, flag, log `intento=1 EXITOSO` | < 2 s |
| 4 | Ciclos siguientes | Publica y entrega 15 y 16 | 15 s entre cada uno |
| 5 | Republicar `EQA-000014` | `El pago ... ya estaba enviado. Se ignora.` | < 2 s |
| 6 | `BANK_SCENARIO=ERROR_500` + recrear consumidor | Consumidor arriba con el nuevo escenario | 10–15 s |
| 7 | Publicar `EQA-DEMO-FAIL` | 5 intentos `FALLIDO` y mensaje en la DLQ | ~25 s |
| 8 | `fullName` > 150 caracteres | `400 VALIDATION_ERROR`, **un** intento, DLQ | < 5 s |
| 9 | `SUCCESS` + recrear + republicar | `intento=6 EXITOSO`, flag y receipt | ~15 s |
| 10 | `.\scripts\conciliar.ps1` | `RESULTADO: CONCILIADO` | < 5 s |
| 11 | Purgar DLQ y bajar el stack | Entorno limpio; datos en volúmenes | < 10 s |

## 4. Evidencia

### 4.1 Flujo feliz

```
Publicado en RabbitMQ: key=EQA-000014 paymentId=14 monto=500.00 canal=BANK_TRANSFER
Pago recibido: paymentId=14 idempotencyKey=EQA-000014
Flag guardado en DynamoDB: EQA-000014 -> enviado
Log guardado en DynamoDB: EQA-000014 intento=1 estado=EXITOSO
ENTREGADO al banco. receiptId=BNK-20261022-5VHTEL
```

### 4.2 Duplicado

```
Pago recibido: paymentId=14 idempotencyKey=EQA-000014
El pago EQA-000014 ya estaba enviado al banco. Se ignora.
```

No hubo llamada al banco ni segundo flag.

### 4.3 Banco caído (500)

```
Log guardado en DynamoDB: EQA-DEMO-FAIL intento=1 estado=FALLIDO
...
Log guardado en DynamoDB: EQA-DEMO-FAIL intento=5 estado=FALLIDO
Rechazo ... -> cola de errores
pagos.banco.dlq   1
```

Sin flag en `pagos_enviados` para esa clave.

### 4.4 Error de datos (400)

```
Log guardado en DynamoDB: EQA-... intento=1 estado=FALLIDO
Rechazo del banco. status=400 code=VALIDATION_ERROR -> cola de errores
```

Un solo intento: un `400` no se reintenta.

### 4.5 Recuperación

```
Flag guardado en DynamoDB: EQA-DEMO-FAIL -> enviado
Log guardado en DynamoDB: EQA-DEMO-FAIL intento=6 estado=EXITOSO
ENTREGADO al banco. receiptId=BNK-...
```

El contador continúa en 6: el historial se conserva.

### 4.6 Conciliación

```
Flags enviados    : 8
Claves en log     : 8
Logs exitosos     : 8
Intentos fallidos : 5

Flags sin log EXITOSO (0)      OK
Logs EXITOSOS sin flag (0)     OK
ReceiptId distinto (0)         OK
RESULTADO: CONCILIADO
```

Los 5 intentos fallidos son históricos, de claves reales que después sí se
entregaron: tienen flag y log `EXITOSO`, por eso no son inconsistencias.

## 5. Hallazgos y correcciones

| Hallazgo | Impacto | Corrección | Estado |
|---|---|---|---|
| `retryable-exceptions` ya no existe en Spring Boot 3.5 | Un `400` se reintentaba 5 veces | `RetryConfig.java` con `SimpleRetryPolicy` para `BancoReintentableException` | ✅ Verificado: 400 = 1 intento, 500 = 5 |
| El banco exige `fullName` (máx. 150) | `400 VALIDATION_ERROR` si falta o es muy largo | Se mapea desde `Tb_Clientes.nombre`; los payloads manuales lo incluyen | ✅ |
| `docker compose restart` no recarga `.env` | El escenario seguía en `SUCCESS` | Usar `docker compose up -d --force-recreate pagos-consumer` | ✅ Documentado |
| La DLQ conserva mensajes de prueba | Ruido en la demo | `rabbitmqctl purge_queue pagos.banco.dlq` al cerrar | ✅ |
| Claves de ensayo con flag | Reutilizarlas haría que se ignoraran | Usar claves nuevas en la demo o borrar su flag | ⚠️ Revisar antes de la demo |

## 6. Riesgos para la demo y mitigación

| Riesgo | Mitigación |
|---|---|
| El banco no responde | Se muestra el escenario de fallo y la DLQ; el flujo no se pierde |
| Docker no arranca | Video/screenshots del ensayo y los diagramas |
| Los pagos 17–18 ya tienen flag | Verificar `pagos_enviados` antes de empezar |
| Se reutiliza una clave de ensayo | Usar `EQA-DEMO-*` nuevas o borrar el flag |
| El productor tarda en el primer ciclo | Arrancarlo al inicio de la demo (retraso inicial 5 s) |

## 7. Antes de la demo

```bat
:: 1. Stack arriba
docker compose up -d
docker compose ps

:: 2. Confirmar que 17 y 18 NO tienen flag
docker run --rm -e AWS_ACCESS_KEY_ID=dummy -e AWS_SECRET_ACCESS_KEY=dummy ^
  amazon/aws-cli dynamodb scan --table-name pagos_enviados ^
  --endpoint-url http://host.docker.internal:8000 --region us-east-1

:: 3. DLQ vacía
docker exec rabbitmq rabbitmqctl list_queues name messages

:: 4. Configuración de demo en .env
::    BANK_SCENARIO=SUCCESS
::    PRODUCER_LIMITE=1
::    PRODUCER_INTERVALO_MS=15000
```

Si se va a reutilizar una clave de ensayo, borrar su flag:

```bat
docker run --rm -e AWS_ACCESS_KEY_ID=dummy -e AWS_SECRET_ACCESS_KEY=dummy ^
  amazon/aws-cli dynamodb delete-item --table-name pagos_enviados ^
  --key "{\"idempotencyKey\":{\"S\":\"EQA-DEMO-FAIL\"}}" ^
  --endpoint-url http://host.docker.internal:8000 --region us-east-1
```

Checklist:

- [ ] Docker Desktop encendido
- [ ] 4 servicios `Up` (RabbitMQ y DynamoDB `healthy`)
- [ ] Pagos 17 y 18 sin flag
- [ ] DLQ en 0
- [ ] `.env` con `SUCCESS`, límite 1 e intervalo 15000
- [ ] API key del banco vigente
- [ ] Terminales y consola de RabbitMQ abiertas

## 8. Resultado del ensayo

| Criterio | Estado |
|---|---|
| Flujo feliz extremo a extremo | ✅ |
| Duplicado ignorado sin llamar al banco | ✅ |
| 500 con 5 reintentos y DLQ | ✅ |
| 400 sin reintentos y DLQ | ✅ |
| Recuperación tras fallo (intento 6) | ✅ |
| Flag solo en éxito | ✅ |
| Conciliación sin diferencias | ✅ |
| Pagos 17 y 18 reservados para la demo | ✅ |

**Conclusión:** el ensayo pasó completo; la demo es viable con el guion de
`docs/pruebas-e2e.md`. La única tarea previa es confirmar que 17 y 18 no tengan
flag y que la DLQ esté vacía.

## 9. Limitaciones

- Las pruebas son manuales; los JUnit actuales solo cargan el contexto.
- DynamoDB es local (`dynamodb-local`), no AWS real.
- El banco es el simulador del curso, no un banco de producción.
- La mejora futura es automatizar con Testcontainers + WireMock.
