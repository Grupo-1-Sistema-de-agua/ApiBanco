# Datos de prueba — SDGA-105

Documento del conjunto de datos que usa la Feature 3. **No se crean ni se
modifican datos**: el productor solo lee del monolito.

## Origen

| Dato | Valor |
|---|---|
| Motor | MariaDB (base del monolito) |
| Base de datos | `agua_db` |
| Puerto local | `3307` |
| Usuario | `agua_ro` (solo lectura) |
| Acceso del productor | `SELECT` únicamente, conexión `read-only: true` |

La información es la del entorno de prueba del curso (clientes y pagos
simulados). En la documentación y en los logs **no** se incluyen DPI ni
nombres reales.

## Tablas utilizadas

| Tabla | Para qué |
|---|---|
| `Tb_Pagos` | Pago: id, monto, fecha, método, lectura, anulado |
| `Tb_Lecturas` | Lectura del contador asociada al pago |
| `Tb_Contadores` | Contador del cliente |
| `Tb_Clientes` | Cliente: id, nombre, DPI |
| `Tb_Metodos_Pago` | Método: Efectivo, Transferencia, Depósito |

## Consulta del productor

El productor lee solo los pagos **no anulados**, ordenados por id:

```sql
SELECT p.id, p.monto, p.fecha_pago,
       c.id AS cliente_id, c.nombre AS cliente_nombre, c.dpi AS cliente_dpi,
       m.nombre AS metodo_nombre, l.numero_recibo
  FROM Tb_Pagos p
  JOIN Tb_Lecturas l     ON l.id = p.lectura_id
  JOIN Tb_Contadores ct  ON ct.id = l.contador_id
  JOIN Tb_Clientes c     ON c.id = ct.cliente_id
  JOIN Tb_Metodos_Pago m ON m.id = p.metodo_id
 WHERE p.anulado = 0
 ORDER BY p.id
 LIMIT :limite;
```

El límite es configurable (`PRODUCER_LIMITE`, por defecto 20). Los pagos que ya
tienen flag en DynamoDB se omiten antes de publicar.

## Mapeo al mensaje de RabbitMQ

| Campo del mensaje | Origen | Ejemplo |
|---|---|---|
| `paymentId` | `Tb_Pagos.id` | `9` |
| `idempotencyKey` | `"EQA-" + id` (6 dígitos) | `EQA-000009` |
| `customerCode` | `"CLI-" + cliente_id` (4 dígitos) | `CLI-0001` |
| `fullName` | `Tb_Clientes.nombre` | `Cliente de Prueba` |
| `taxId` | `Tb_Clientes.dpi` o `CF` si viene vacío | `CF` |
| `amount` | `Tb_Pagos.monto` | `500.00` |
| `transactionDate` | `Tb_Pagos.fecha_pago` en zona `America/Guatemala` | `2026-10-01T10:00:00-06:00` |
| `channel` | `Tb_Metodos_Pago.nombre` | `CASH` / `BANK_TRANSFER` |
| `description` | `"Servicio de agua " + yyyy-MM` | `Servicio de agua 2026-10` |

Regla de canal:

- `Efectivo` → `CASH`
- `Transferencia` o `Depósito` → `BANK_TRANSFER`
- Cualquier otro método → error (no se publica)

## Conjunto de datos de la demo

- Pagos de prueba: **ids 1 al 18**.
- Al **23/10/2026**, el estado de referencia es:
  - **9 al 16**: ya enviados (flag en `pagos_enviados`).
  - **17 y 18**: pendientes (los que se usan en la demo en vivo).

> El estado real de "enviado" vive en DynamoDB, no en MariaDB. MariaDB es la
> fuente de los pagos; DynamoDB es la fuente de la idempotencia.

## Cómo verificar el conjunto

Con cualquier cliente SQL (DBeaver, phpMyAdmin del monolito, etc.):

```sql
-- Total de pagos no anulados
SELECT COUNT(*) FROM Tb_Pagos WHERE anulado = 0;

-- Pagos con su cliente y método
SELECT p.id, p.monto, p.fecha_pago, c.nombre, m.nombre AS metodo
  FROM Tb_Pagos p
  JOIN Tb_Lecturas l     ON l.id = p.lectura_id
  JOIN Tb_Contadores ct  ON ct.id = l.contador_id
  JOIN Tb_Clientes c     ON c.id = ct.cliente_id
  JOIN Tb_Metodos_Pago m ON m.id = p.metodo_id
 WHERE p.anulado = 0
 ORDER BY p.id;
```

Flags en DynamoDB Local:

```bat
docker run --rm -e AWS_ACCESS_KEY_ID=dummy -e AWS_SECRET_ACCESS_KEY=dummy ^
  amazon/aws-cli dynamodb scan --table-name pagos_enviados ^
  --endpoint-url http://host.docker.internal:8000 --region us-east-1
```

## Reglas

- **Prohibido** `INSERT`, `UPDATE` o `DELETE` sobre el monolito.
- Los datos de prueba se documentan aquí; no se versionan DPI ni nombres reales.
- Si se necesita más volumen para una prueba, se solicita al docente o se usa
  una base aparte, nunca la del monolito en producción.
