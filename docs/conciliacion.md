# Conciliación de pagos — SDGA-110

Script de QA que compara los **pagos enviados** (flag en `pagos_enviados`)
contra los **intentos registrados** (filas de `pagos_log`).

## 1. Qué detecta

| Revisión | Significado |
|---|---|
| **Flag sin log EXITOSO** | Se marcó como enviado, pero no hay evidencia de entrega |
| **Log EXITOSO sin flag** | Se entregó, pero falta el flag de idempotencia |
| **ReceiptId distinto** | El comprobante del flag no coincide con el del log |
| **Fallidos sin flag** | Informativo: intentos que fallaron y no llegaron a enviarse (pueden estar en la DLQ) |

Por defecto solo analiza **pagos reales** (`EQA-000123`). Las claves de ensayo
(`EQA-REHEARSAL-*`, `EQA-DEMO-*`) se ignoran para que la demo salga limpia.

## 2. Requisitos

- Docker Desktop encendido.
- DynamoDB Local arriba:

```bat
docker compose up -d dynamodb-local
```

- Las tablas `pagos_enviados` y `pagos_log` con datos.

## 3. Uso

```powershell
cd C:\Users\mario\Desktop\ApiBanco
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\conciliar.ps1
```

Si tu política de ejecución lo permite, basta con:

```powershell
.\scripts\conciliar.ps1
```

### Parámetros

| Parámetro | Default | Para qué |
|---|---|---|
| `-Endpoint` | `http://host.docker.internal:8000` | URL de DynamoDB vista desde el contenedor |
| `-Region` | `us-east-1` | Región de AWS |
| `-TablaFlags` | `pagos_enviados` | Tabla de flags |
| `-TablaLog` | `pagos_log` | Tabla de logs |
| `-IncluirPruebas` | apagado | Incluye claves que no son `EQA-######` |

Ejemplo con claves de ensayo:

```powershell
.\scripts\conciliar.ps1 -IncluirPruebas
```

## 4. Salida real (23/10/2026)

```
=== Conciliacion Feature 3 ===
Endpoint          : http://host.docker.internal:8000
Alcance           : solo pagos reales EQA-######
Flags enviados    : 8
Claves en log     : 8
Logs exitosos     : 8
Intentos fallidos : 5

Flags sin log EXITOSO (0)
  OK: ninguna

Logs EXITOSOS sin flag (0)
  OK: ninguna

ReceiptId distinto (0)
  OK: ninguna

Fallidos sin flag (informativo, pueden estar en DLQ) (0)
  OK: ninguna

RESULTADO: CONCILIADO
```

**Lectura:** los pagos 9–16 tienen flag y log exitoso; no hay diferencias.
Los 5 intentos fallidos pertenecen a claves que después sí se entregaron, por
eso no aparecen como "fallidos sin flag".

## 5. Código de salida

| Código | Significado |
|---|---|
| `0` | Todo conciliado |
| `1` | Hay diferencias (flag sin log, log sin flag o receipt distinto) |
| otro | Error de conexión, tabla inexistente o Docker apagado |

El código `1` permite usar el script en un pipeline de QA.

## 6. Cómo interpretar los resultados

- **Flags sin log EXITOSO** → revisar si el consumidor falló justo después de
  escribir el flag, o si se borró el log por error. Es la inconsistencia más
  grave.
- **Logs EXITOSOS sin flag** → el flag no se escribió; el productor podría
  reenviar el pago. El banco respondería `409 DUPLICATE_PAYMENT` (se tolera),
  pero conviene corregirlo.
- **ReceiptId distinto** → el flag y el log apuntan a comprobantes diferentes;
  revisar la clave de idempotencia.
- **Fallidos sin flag** → normal si el pago está en la DLQ. Se revisa con:

```bat
docker exec rabbitmq rabbitmqctl list_queues name messages
```

## 7. Limitaciones

- `Scan` de DynamoDB devuelve como máximo 1 MB; suficiente para la demo, pero
  para volúmenes grandes habría que paginar.
- **No** consulta el banco: compara flag contra log, no contra el estado real
  del banco (la API no expone un listado de pagos).
- **No** revisa la DLQ automáticamente; se consulta con `rabbitmqctl`.
- Requiere Docker (usa la imagen `amazon/aws-cli`).

## 8. Checklist SDGA-110

- [x] Compara flags contra logs
- [x] Detecta flag sin log exitoso
- [x] Detecta log exitoso sin flag
- [x] Compara `receiptId`
- [x] Ignora claves de ensayo por defecto
- [x] Devuelve código de salida para QA
- [x] Probado contra DynamoDB Local (8/8 conciliados)
