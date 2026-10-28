# Diagramas — Feature 3 (SDGA-104)

Diagramas de la solución: **casos de uso**, **actividades**, **estados** y
**componentes**. Están en Mermaid para que GitHub los renderice directamente.

> Versiones en imagen (Archify): [secuencia](diagramas/feature3-secuencia.png)
> y [dataflow](diagramas/feature3-datos.png).

Contexto: los pagos pendientes del monolito viajan por RabbitMQ, el consumidor
los envía al banco simulado y se guarda un flag + log en DynamoDB.

---

## 1. Casos de uso

Actores y acciones que cubre la feature.

```mermaid
flowchart LR
    Admin["Administrador<br/>(oficina)"]
    QA["QA"]
    Prod["Productor"]
    Cons["Consumidor"]
    Banco["Banco simulado"]

    UC1(["Consultar pagos pendientes"])
    UC2(["Publicar pago en la cola"])
    UC3(["Recibir pago de la cola"])
    UC4(["Enviar pago al banco"])
    UC5(["Registrar flag y log"])
    UC6(["Reintentar o enviar a DLQ"])
    UC7(["Consultar estado de un pago"])
    UC8(["Conciliar enviados vs registrados"])
    UC9(["Revisar cola de errores"])

    Admin --> UC1
    Admin --> UC7
    Admin --> UC9
    QA --> UC7
    QA --> UC8
    Prod --> UC1
    Prod --> UC2
    Cons --> UC3
    Cons --> UC4
    Cons --> UC5
    Cons --> UC6
    UC4 --> Banco
    UC2 -.->|incluye| UC3
    UC3 -.->|incluye| UC4
    UC4 -.->|incluye| UC5
```

**Lectura:**
- El **productor** consulta y publica; no llama al banco.
- El **consumidor** recibe, envía, registra y decide reintentos.
- El **administrador** y **QA** solo consultan (no hay interfaz propia: se usan
  logs, RabbitMQ y DynamoDB).

---

## 2. Actividades

Flujo completo, desde la lectura del pago hasta el flag o la DLQ.

```mermaid
flowchart TD
    subgraph P["Productor (cada N segundos)"]
        A([Inicio]) --> B[Leer pagos no anulados]
        B --> C{¿Ya tiene flag?}
        C -->|Sí| D[Omitir pago]
        C -->|No| E[Publicar en pagos.exchange]
    end

    subgraph R["RabbitMQ"]
        Q[(pagos.banco)]
    end

    subgraph K["Consumidor"]
        F[Recibir pago] --> G{¿Ya enviado<br/>localmente?}
        G -->|Sí| H[Ignorar duplicado]
        G -->|No| I[Mapear al contrato del banco]
        I --> J[POST /bank-payments]
        J --> L{Respuesta del banco}
        L -->|201| M[Flag + log EXITOSO]
        L -->|409 DUPLICATE_PAYMENT| M
        L -->|429 / 5xx / timeout| N{¿Quedan intentos?}
        N -->|Sí| O[Esperar 1, 2, 4 u 8 s]
        O --> J
        N -->|No| DLQ[Enviar a pagos.banco.dlq]
        L -->|400 / 422 / otro| FALLO[Log FALLIDO]
        FALLO --> DLQ
    end

    E --> Q
    Q --> F
    D --> Z([Fin del ciclo])
    M --> Z
    H --> Z
    DLQ --> Z
```

**Reglas que representa:**
- Un pago con flag se omite antes de publicar.
- Un duplicado local se ignora **sin** llamar al banco.
- `201` y `409 DUPLICATE_PAYMENT` se consideran éxito.
- Solo `429`, `5xx` y timeout se reintentan (5 intentos).
- `400/422` y mensajes inválidos van directo a la DLQ.
- La DLQ **no** escribe flag: el pago puede reenviarse.

---

## 3. Estados de un pago

Ciclo de vida desde que está pendiente hasta que se envía o queda en la DLQ.

```mermaid
stateDiagram-v2
    [*] --> Pendiente: pago en Tb_Pagos sin flag
    Pendiente --> Publicado: el productor publica
    Publicado --> EnProceso: el consumidor recibe
    EnProceso --> Enviado: 201 o 409 DUPLICATE_PAYMENT
    EnProceso --> Reintentando: 429 / 5xx / timeout
    Reintentando --> EnProceso: espera 1, 2, 4 u 8 s
    Reintentando --> EnDLQ: 5 intentos agotados
    EnProceso --> EnDLQ: 400 / 422 / mensaje inválido
    EnDLQ --> EnProceso: republicar manualmente
    Enviado --> [*]
    EnDLQ --> [*]

    note right of Enviado
        Flag en pagos_enviados
        + log EXITOSO
    end note

    note right of EnDLQ
        Sin flag:
        se puede reenviar
    end note
```

**Invariante clave:** el flag se escribe **solo** en `Enviado`. Si el pago
termina en `EnDLQ`, no hay flag y el productor lo volverá a publicar en otro
ciclo (o se republica a mano).

---

## 4. Componentes

Piezas desplegadas y cómo se comunican.

```mermaid
flowchart LR
    subgraph MONO["Monolito Oficina del Agua"]
        DB[("MariaDB<br/>Tb_Pagos · :3307")]
    end

    subgraph PROD["pagos-producer :8081"]
        SCH["@Scheduled<br/>PublicadorPagos"]
        REPO["PagoRepository<br/>(solo lectura)"]
        MAP["PagoMapper"]
        FLAG["FlagEnviadosService"]
        RCONF["RabbitConfig"]
    end

    subgraph RMQ["RabbitMQ :5672 / :15672"]
        EX{{"pagos.exchange"}}
        Q["pagos.banco"]
        DLX{{"pagos.dlx"}}
        DLQ["pagos.banco.dlq"]
    end

    subgraph CONS["pagos-consumer :8082"]
        LIST["PagoBancoListener"]
        RETRY["RetryConfig"]
        BS["BancoService"]
        FEIGN["BankPaymentsApiClient<br/>(Feign)"]
        REG["RegistroPagoService"]
    end

    BANK["Banco simulado<br/>API Gateway"]
    DDB[("DynamoDB Local :8000<br/>pagos_enviados · pagos_log")]

    DB -->|JDBC read-only| REPO
    SCH --> REPO
    SCH --> MAP
    SCH --> FLAG
    FLAG -->|GetItem| DDB
    SCH --> RCONF
    RCONF -->|AMQP| EX
    EX -->|routing key banco| Q
    Q -->|AMQP| LIST
    LIST --> RETRY
    LIST --> BS
    BS --> FEIGN
    FEIGN -->|HTTPS POST| BANK
    LIST --> REG
    REG -->|flag + log| DDB
    LIST -->|rechazo definitivo| DLX
    DLX --> DLQ
```

**Notas:**
- El productor **solo lee** MariaDB (`read-only: true`) y **solo lee** el flag
  en DynamoDB.
- El consumidor es el único que escribe en DynamoDB.
- RabbitMQ desacopla productor y consumidor; la DLX/DLQ captura lo irrecuperable.
- El banco es externo (API Gateway) y se llama por HTTPS con el cliente Feign.

---

## 5. Cómo visualizarlos

- **GitHub:** los bloques `mermaid` se renderizan solos al abrir este archivo.
- **VS Code:** instalar la extensión *Markdown Preview Mermaid Support* y abrir
  la vista previa.
- **Mermaid Live Editor:** https://mermaid.live (pegar cada bloque).

> Si un diagrama no renderiza, revisa que el bloque empiece con
> ```` ```mermaid ```` y termine con ```` ``` ````.
