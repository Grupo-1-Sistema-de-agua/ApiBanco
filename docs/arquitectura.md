# Arquitectura de la solución — Fase 2

Documento de apoyo del README. Explica cómo está construida la solución
integrada de la Oficina del Agua y dónde encaja la Feature 3.

> **Fuentes:** arquitectura de Fase 2 (Clase 10), código real de este repositorio
> y `docker-compose.yml` de la Feature 3. Las Features 1, 2 y 4 son módulos de
> otros integrantes; su descripción es de integración, no de código presente
> aquí. Documento y diagrama elaborados con asistencia de IA y verificados
> contra el código y el compose reales.

## 1. Diagrama

![Arquitectura de la solución integrada](diagramas/arquitectura-fase2.png)

Fuente editable (Archify):
[`diagramas/arquitectura-fase2.architecture.json`](diagramas/arquitectura-fase2.architecture.json).

## 2. Contexto y restricciones

La Fase 1 entregó un **monolito** funcional (CodeIgniter 4, PHP 8.2, MariaDB
10.11) con auth, tarifas, clientes, contadores, lecturas, recibos y pagos. La
Fase 2 no lo reescribe: le agrega cuatro features alrededor. El monolito está
en [`Grupo-1-Sistema-de-agua/Sistema_Oficina_De_Agua`](https://github.com/Grupo-1-Sistema-de-agua/Sistema_Oficina_De_Agua).

Restricciones que gobiernan el diseño:

1. **El monolito no se modifica** (ni esquema ni datos).
2. Los servicios nuevos lo leen con un usuario de **solo lectura**.
3. Todo corre **local en Docker Compose**; no hay AWS real.
4. Los contratos de los simuladores (banco/ERP) son **contract-first** y no se
   editan.
5. Las credenciales viven fuera del repositorio (`.env`).
6. La solución debe ser **tolerante a fallos**: una caída del banco no puede
   perder un pago ni duplicarlo.

## 3. Componentes

| Componente | Tecnología | Función | Frontera |
|---|---|---|---|
| **Monolito** | CodeIgniter 4 · PHP 8.2 | Sistema heredado; fuente de pagos y datos del cliente | No se toca |
| **MariaDB** | MariaDB 10.11 | Base `agua_db` (`Tb_Pagos`, `Tb_Lecturas`, etc.) | Solo lectura para Fase 2 |
| **Next.js BFF** | Next.js | Página pública, captcha y proxy del chatbot | No accede a la BD |
| **consulta-service** | Spring Boot | Estado de cuenta por código de contador | Solo lectura |
| **chatbot-service** | Spring Boot + RAG | Embeddings, búsqueda y prompt al LLM | API key en el servidor |
| **PostgreSQL + pgvector** | PostgreSQL + pgvector | Base vectorial del chatbot | Base nueva, no la del monolito |
| **pagos-producer** | Spring Boot · `@Scheduled` | Detecta pendientes y publica en RabbitMQ | Solo lectura |
| **RabbitMQ** | RabbitMQ 3 Management | Exchange `pagos.exchange`, cola `pagos.banco`, DLQ | Desacopla F3 |
| **pagos-consumer** | Spring Boot · OpenFeign | Llama al banco/ERP, reintenta y registra | Un consumidor por destino |
| **DynamoDB Local** | AWS SDK v2 | Flag `pagos_enviados` y log `pagos_log` | Fuera del monolito |
| **sync-service** | Spring Boot | Réplica asíncrona con diff + upsert | No borra registros |
| **MongoDB** | MongoDB | Destino NoSQL alternativo | Opcional |
| **Banco / ERP** | API Gateway + OpenAPI | Sistemas externos simulados | Los provee el docente |
| **LLM** | OpenAI / Bedrock / Ollama | Respuestas del chatbot | Única llamada externa real |

## 4. Flujos

### 4.1 Consulta pública (Feature 1)

1. El cliente entra a la página de Next.js y resuelve el captcha.
2. Next.js (BFF) llama a `consulta-service`; el navegador nunca ve la BD.
3. `consulta-service` hace `SELECT` sobre MariaDB con el código del contador.
4. El servicio aplica **rate limit por IP** como segunda barrera contra scraping.

### 4.2 Chatbot con RAG (Feature 2)

1. El widget de Next.js envía la pregunta al `chatbot-service`.
2. El servicio genera el embedding y busca los fragmentos más similares en
   `pgvector`.
3. Arma el prompt con ese contexto y llama al LLM externo.
4. La API key del LLM queda en el servidor; Next.js solo hace de proxy.

### 4.3 Envío de pagos (Feature 3, este repositorio)

1. `pagos-producer` corre cada 15–30 s y hace `SELECT` de pagos no anulados.
2. Omite los que ya tienen flag en `pagos_enviados`.
3. Publica un mensaje JSON en `pagos.exchange` (routing key `banco`).
4. `pagos-consumer` lo recibe, mapea al contrato del banco y hace el `POST`.
5. Si el banco responde `201` o `409 DUPLICATE_PAYMENT`, guarda el flag y un log
   `EXITOSO`. Si falla, guarda un log `FALLIDO`.
6. Los fallos temporales se reintentan (5 intentos, 1/2/4/8 s); los definitivos
   van a `pagos.banco.dlq` sin flag, para poder reenviar después.

### 4.4 Replicación asíncrona (Feature 4)

1. `POST /api/sync/resumen` responde `202 Accepted` con un `jobId`.
2. `sync-service` compara el monolito contra el destino NoSQL y calcula el diff.
3. Aplica **solo upserts**; nunca borra registros existentes.
4. `GET /api/sync/jobs/{jobId}` informa `PENDIENTE`, `EN_PROGRESO`,
   `COMPLETADO` o `FALLIDO`.

## 5. Fronteras y seguridad

- **Monolito aislado:** los servicios nuevos solo leen; no hay migraciones ni
  columnas nuevas en `Tb_Pagos`.
- **Next.js como BFF:** no accede a la base; centraliza captcha y proxy.
- **Credenciales:** `.env` fuera de Git; `x-api-key` solo en el servidor.
- **Datos personales:** los logs y DynamoDB no guardan `fullName` ni `taxId`.
- **Errores:** `server.error.include-stacktrace: never`.
- **Colas:** mensajes durables; DLQ para los rechazos definitivos.

## 6. Ejecución

### 6.1 Solución integrada

El equipo usa un `docker-compose.yml` común con Next.js, los cinco servicios
Spring Boot, PostgreSQL + pgvector, RabbitMQ, DynamoDB Local y MongoDB. El
monolito se levanta aparte (su propio compose) y comparte red con la Fase 2.
Los puertos y variables se documentan en el compose del equipo.

### 6.2 Feature 3 aislada (este repositorio)

```bat
cd C:\Users\mario\Desktop\ApiBanco
copy .env.example .env
:: completar BANK_BASE_URL, BANK_API_KEY y DB_PASSWORD
docker compose up -d --build
docker compose logs -f pagos-producer
docker compose logs -f pagos-consumer
```

Servicios: `rabbitmq` (5672/15672), `dynamodb-local` (8000), `pagos-producer`
(8081) y `pagos-consumer` (8082). La MariaDB del monolito se consume en
`host.docker.internal:3307` (ajusta `DB_URL` si tu monolito publica `3306`).

## 7. Qué está implementado aquí

| Elemento | Estado |
|---|---|
| Productor que lee MariaDB y publica en RabbitMQ | ✅ |
| Consumidor con reintentos, DLQ e idempotencia | ✅ |
| Flag y log en DynamoDB | ✅ |
| Contrato del banco consumido con OpenAPI | ✅ |
| Conciliación flags vs. logs | ✅ |
| Pruebas E2E y ensayo documentados | ✅ |
| Next.js, consulta, chatbot, pgvector y sync | ❌ (otros módulos) |
| ERP simulado | ⚠️ Contemplado; la implementación actual entrega al banco |
| AWS real (SQS/Lambda/DynamoDB) | ❌ (Fase 2 es local) |

## 8. Decisiones y alternativas

| Decisión | Alternativa descartada | Motivo |
|---|---|---|
| Flag en DynamoDB | Columna en `Tb_Pagos` | Prohibido modificar el monolito |
| Cola RabbitMQ | Llamada síncrona desde el monolito | El pago se pierde si el banco falla |
| Reintentar solo 5xx/429/timeout | Reintentar todo | Un 400/422 es error de datos; reintentarlo no ayuda |
| DLQ | Reencolar indefinidamente | Evita bucles y permite revisar los rechazos |
| `409 DUPLICATE_PAYMENT` = éxito | Tratarlo como error | El banco ya tiene el pago; no se debe cobrar dos veces |
| Next.js BFF | Next.js accediendo a MariaDB | Expone la BD y las credenciales al navegador |
| pgvector | Buscar en el LLM sin contexto | RAG reduce alucinaciones y usa contenido real |
| Diff + upsert | Borrar y recrear la réplica | Evita pérdida de datos y ventanas sin información |

## 9. Verificación contra el código

| Qué | Dónde |
|---|---|
| Productor y `@Scheduled` | `pagos-producer/src/main/java/.../service/PublicadorPagos.java` |
| Clave de idempotencia | `pagos-producer/src/main/java/.../service/PagoMapper.java` |
| Consumidor, reintentos y DLQ | `pagos-consumer-banco/src/main/java/.../listener/PagoBancoListener.java` |
| Política de reintentos | `pagos-consumer-banco/src/main/java/.../config/RetryConfig.java` |
| Mapeo al contrato del banco | `pagos-consumer-banco/src/main/java/.../service/BancoService.java` |
| Flag y log | `pagos-consumer-banco/src/main/java/.../service/RegistroPagoService.java` |
| Colas y DLQ | `pagos-consumer-banco/src/main/java/.../config/RabbitConfig.java` |
| Servicios y puertos | `docker-compose.yml` |
| Contrato del banco | `pagos-consumer-banco/src/main/resources/openapi/bank-payments-api.yaml` |

## 10. Referencias

- [README principal](../README.md)
- [Pruebas E2E](pruebas-e2e.md)
- [Ensayo general](ensayo.md)
- [Seguridad](seguridad.md)
- [Conciliación](conciliacion.md)
- [Datos de prueba](datos-prueba.md)
- [Diagramas de la Feature 3](diagramas.md)
