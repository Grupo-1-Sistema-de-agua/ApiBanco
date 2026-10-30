# Postman — Feature 3

Colección para publicar mensajes en RabbitMQ y probar el contrato del banco.

> **No dispara al productor ni al consumidor.** El productor es un `@Scheduled`
> y el consumidor escucha la cola; su trabajo se ve en los logs y en los
> efectos (cola, DynamoDB y banco). La colección sirve para los casos borde y
> para probar el API del banco.

## 1. Importar

1. Abre Postman → **Import**.
2. Importa:
   - `ApiBanco-Feature3.postman_collection.json`
   - `ApiBanco-Feature3.postman_environment.json`
3. Selecciona el environment **ApiBanco — Feature 3 (local)**.
4. Completa `bankApiKey` con la API key del docente. `bankBaseUrl` ya debe
   apuntar al API Gateway del banco.

> Si tienes el environment local con la clave real (fuera del repositorio),
> importa ese archivo y no tendrás que escribirla a mano.

## 2. Antes de ejecutar

```bat
cd C:\Users\mario\Desktop\ApiBanco
docker compose up -d
docker compose ps
```

Debe estar arriba `rabbitmq`, `dynamodb-local`, `pagos-consumer` y, si quieres
el flujo automático, `pagos-producer` con la MariaDB del monolito.

## 3. Orden de prueba

| # | Request | Qué debe pasar |
|---|---|---|
| 1 | **Health del banco** | `200` |
| 2 | **Éxito (201)** | `routed: true`; el consumidor entrega y guarda flag/log |
| 3 | **Duplicado (misma clave)** | El consumidor ignora el mensaje |
| 4 | **Fallo 500** | 5 intentos y DLQ; sin flag |
| 5 | **Error 400** | 1 intento y DLQ; sin flag |
| 6 | **Cola / DLQ** | `pagos.banco` en 0; la DLQ con los rechazos |
| 7 | **Purgar DLQ** | Limpia la cola de errores |

### Para el 500 y el 400

El escenario se controla con `BANK_SCENARIO` en `.env`, no con el mensaje:

```env
BANK_SCENARIO=ERROR_500
```

```bat
docker compose up -d --force-recreate pagos-consumer
```

Después de la prueba, vuelve a `SUCCESS` y recrea el consumidor.

## 4. Dónde verificar

- **Consumidor:** `docker compose logs -f pagos-consumer`
- **Productor:** `docker compose logs -f pagos-producer`
- **RabbitMQ:** http://localhost:15672 → Queues
- **DynamoDB:** `scripts/conciliar.ps1 -IncluirPruebas`
- **Banco:** request **Consultar pago por clave**

## 5. Notas

- La request **Enviar pago directo (contrato)** llama al banco sin pasar por el
  consumidor; sirve para probar el contrato, no para escribir flag/log.
- Las claves `EQA-DEMO-*` y `EQA-POSTMAN-*` son de prueba. La conciliación las
  ignora por defecto; usa `-IncluirPruebas`.
- No subas al repositorio el environment con la API key real. El environment
  del repositorio tiene placeholders.
- Si el banco no responde, revisa `BANK_BASE_URL` y `BANK_API_KEY` en `.env`.
