# Palabras clave — Feature 3 (Oficina del Agua)

Guía corta para estudiar y defender la demo. No reemplaza al `README.md`:
aquí solo están los **términos**, los **comandos** y las **respuestas clave**.

---

## 1. Glosario

| Palabra | Qué significa | Dónde se ve |
|---|---|---|
| **Monolito** | El sistema viejo de la oficina del agua. No se modifica. | `Sistema_Oficina_De_Agua` (solo lectura) |
| **Microservicio** | Programa pequeño con una sola responsabilidad. | `pagos-producer`, `pagos-consumer` |
| **Productor** | Lee los pagos y los manda a la cola. | `pagos-producer` |
| **Consumidor** | Toma los pagos de la cola y llama al banco. | `pagos-consumer-banco` |
| **Broker** | Intermediario que guarda los mensajes. | RabbitMQ |
| **Exchange** | Punto de entrada de los mensajes. | `pagos.exchange` |
| **Routing key** | "Dirección" que decide a qué cola va el mensaje. | `banco` |
| **Cola (queue)** | Lista donde esperan los mensajes. | `pagos.banco` |
| **Binding** | Regla que une exchange + routing key + cola. | `RabbitConfig.java` |
| **ACK** | Confirmación de que el mensaje se procesó. | Automático en Spring AMQP |
| **DLQ** | *Dead Letter Queue*: cola de mensajes que fallaron definitivamente. | `pagos.banco.dlq` |
| **DLX** | Exchange que manda los mensajes a la DLQ. | `pagos.dlx` |
| **Idempotencia** | Repetir la misma operación no cambia el resultado. | Flag en DynamoDB |
| **Idempotency key** | Identificador único del pago para el banco. | `EQA-000009` |
| **Flag** | Marca de "este pago ya se envió". | Tabla `pagos_enviados` |
| **Log** | Historial de cada intento (fecha, estado, código HTTP). | Tabla `pagos_log` |
| **DynamoDB** | Base de datos NoSQL de AWS (aquí, versión local). | Puerto 8000 |
| **Partition key** | Llave principal de una tabla DynamoDB. | `idempotencyKey` |
| **Sort key** | Segunda parte de la llave (ordena los registros). | `momento` en `pagos_log` |
| **Reintento** | Volver a intentar una operación que falló. | `RetryConfig.java` |
| **Backoff exponencial** | Esperar más tiempo en cada reintento. | 1 s, 2 s, 4 s, 8 s |
| **Feign** | Cliente HTTP declarativo de Spring. | `BankPaymentsApiClient` |
| **Contrato OpenAPI** | Archivo que describe la API del banco. | `bank-payments-api.yaml` |
| **DTO** | Objeto para transportar datos entre capas. | `BankPaymentRequestDto` |
| **Mapeo** | Traducir nuestro pago al formato del banco. | `BancoService.mapear()` |
| **Scheduler** | Tarea que se ejecuta sola cada cierto tiempo. | `@Scheduled` |
| **Correlation ID** | Código para seguir un mensaje en los logs. | Header `X-Correlation-Id` |
| **Contenedor** | "Caja" aislada donde corre un programa. | Docker |
| **Imagen** | Plantilla para crear contenedores. | `apibanco-pagos-producer` |
| **Volumen** | Disco que sobrevive al borrar contenedores. | `dynamodb_data`, `rabbitmq_data` |
| **Healthcheck** | Prueba que dice si un contenedor está sano. | `docker-compose.yml` |
| **`depends_on`** | Orden de arranque entre contenedores. | `docker-compose.yml` |
| **Variable de entorno** | Configuración externa al código. | `.env` |

---

## 2. El flujo en 6 palabras

```
pago → productor → cola → consumidor → banco → flag
```

1. El productor lee `Tb_Pagos`.
2. Pregunta en DynamoDB si ya tiene flag.
3. Publica el pago en `pagos.exchange` con routing key `banco`.
4. El consumidor lo recibe desde `pagos.banco`.
5. Lo mapea y hace `POST /bank-payments` con `Idempotency-Key`.
6. Si el banco responde `201`, guarda flag + log en DynamoDB.

---

## 3. Comandos de bolsillo

```bat
:: Levantar / ver / detener
docker compose up -d
docker compose ps
docker compose logs -f pagos-producer
docker compose logs -f pagos-consumer
docker compose down

:: Ver mensajes en las colas
docker exec rabbitmq rabbitmqctl list_queues name messages

:: Ver los flags guardados
docker run --rm -e AWS_ACCESS_KEY_ID=dummy -e AWS_SECRET_ACCESS_KEY=dummy amazon/aws-cli dynamodb scan --table-name pagos_enviados --endpoint-url http://host.docker.internal:8000 --region us-east-1

:: Ver los logs de intentos
docker run --rm -e AWS_ACCESS_KEY_ID=dummy -e AWS_SECRET_ACCESS_KEY=dummy amazon/aws-cli dynamodb scan --table-name pagos_log --endpoint-url http://host.docker.internal:8000 --region us-east-1

:: Vaciar la DLQ antes de la demo
docker exec rabbitmq rabbitmqctl purge_queue pagos.banco.dlq
```

Consola de RabbitMQ: http://localhost:15672 (`guest` / `guest`).

---

## 4. Frases para la defensa

- "El productor **solo lee** el monolito; no lo modificamos."
- "La **idempotency key** es estable: `EQA-` + el id del pago."
- "El flag se escribe **solo** cuando el banco confirma; si falla, el pago se puede reenviar."
- "Los errores de red o 5xx se reintentan **5 veces** con espera creciente."
- "Un error de datos (400) **no** se reintenta: va directo a la cola de errores."
- "Si el consumidor se cae, el mensaje **queda en la cola** porque es durable."
- "Todo el flujo se levanta con **un solo comando** (`docker compose up -d`)."

---

## 5. Posibles preguntas del docente

| Pregunta | Respuesta corta |
|---|---|
| ¿Qué pasa si el banco está caído? | 5 reintentos (1, 2, 4, 8 s); si sigue fallando, va a `pagos.banco.dlq` y **no** se escribe el flag. |
| ¿Cómo evitan enviar dos veces el mismo pago? | `idempotencyKey` + flag en `pagos_enviados`. El consumidor y el productor consultan ese flag. |
| ¿Qué pasa si se cae el consumidor? | El mensaje sigue en la cola (durable) y se procesa al reiniciar. |
| ¿Por qué RabbitMQ y no llamar al banco directo? | Desacopla, absorbe picos y permite reintentos sin perder pagos. |
| ¿Por qué DynamoDB? | Guarda el flag y el log sin tocar la base del monolito. |
| ¿Qué pasa con un pago inválido? | El banco responde 400; se registra el intento y va directo a la DLQ. |
| ¿Se puede reprocesar un mensaje de la DLQ? | Sí, se vuelve a publicar en `pagos.banco` (o se reenvía cuando el productor vea que no hay flag). |
| ¿Cómo siguen un mensaje en los logs? | Con el `X-Correlation-Id` que se guarda en `pagos_log`. |

---

## 6. Archivos clave

| Archivo | Para qué |
|---|---|
| `docker-compose.yml` | Levanta RabbitMQ, DynamoDB, productor y consumidor |
| `.env` | Credenciales y configuración (no se sube a git) |
| `pagos-producer/.../PublicadorPagos.java` | Tarea programada que publica |
| `pagos-producer/.../FlagEnviadosService.java` | Consulta el flag en DynamoDB |
| `pagos-producer/.../PagoMapper.java` | Convierte la fila SQL en mensaje |
| `pagos-consumer/.../PagoBancoListener.java` | Recibe, reintenta y decide la DLQ |
| `pagos-consumer/.../BancoService.java` | Mapea al contrato y llama al banco |
| `pagos-consumer/.../RegistroPagoService.java` | Escribe flag y log en DynamoDB |
| `pagos-consumer/.../RetryConfig.java` | Política: solo 5xx/timeout se reintentan |
| `pagos-consumer/src/main/resources/openapi/bank-payments-api.yaml` | Contrato del banco (no se edita) |
