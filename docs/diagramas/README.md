# Diagramas

## Solución integrada

| Archivo | Qué es |
|---|---|
| `arquitectura-fase2.png` | Imagen del diagrama integrado (tema claro, 2×) |
| `arquitectura-fase2.architecture.json` | Fuente editable de Archify |

Muestra el monolito heredado (sin modificar), las cuatro features de Fase 2,
Next.js como BFF, las bases de datos, RabbitMQ y los sistemas externos
(banco, ERP y LLM).

## Feature 3

| Archivo | Qué es |
|---|---|
| `feature3-api-banco.png` | Contexto del API del banco: POST, respuestas y flag/DLQ |
| `feature3-api-banco.workflow.json` | Fuente editable de Archify |
| `feature3-secuencia.png` | Secuencia del camino feliz (productor → cola → consumidor → banco → flag/log) |
| `feature3-secuencia.sequence.json` | Fuente editable de Archify |
| `feature3-datos.png` | Dataflow: qué datos viajan y qué se guarda como evidencia |
| `feature3-datos.dataflow.json` | Fuente editable de Archify |

Los diagramas de **casos de uso, actividades, estados y componentes** siguen en
[`../diagramas.md`](../diagramas.md) como Mermaid; estos PNG son el complemento
visual para el README.

## Regenerar con Archify

```bat
git clone https://github.com/tt-a1i/archify %TEMP%\archify
cd /d %TEMP%\archify

node archify\bin\archify.mjs deliver architecture ^
  C:\Users\mario\Desktop\ApiBanco\docs\diagramas\arquitectura-fase2.architecture.json ^
  %TEMP%\arquitectura-fase2.html --quality showcase --open

node archify\bin\archify.mjs deliver workflow ^
  C:\Users\mario\Desktop\ApiBanco\docs\diagramas\feature3-api-banco.workflow.json ^
  %TEMP%\feature3-api-banco.html --quality showcase --open

node archify\bin\archify.mjs deliver sequence ^
  C:\Users\mario\Desktop\ApiBanco\docs\diagramas\feature3-secuencia.sequence.json ^
  %TEMP%\feature3-secuencia.html --quality showcase --open

node archify\bin\archify.mjs deliver dataflow ^
  C:\Users\mario\Desktop\ApiBanco\docs\diagramas\feature3-datos.dataflow.json ^
  %TEMP%\feature3-datos.html --quality showcase --open
```

Abre el HTML y usa **Export → PNG** (o captura el SVG en tema claro) para
reemplazar el PNG correspondiente.

> Las imágenes se generaron con Archify 3.2.0. El JSON es la fuente de verdad;
> si cambia la arquitectura, se edita el JSON y se vuelve a exportar.
