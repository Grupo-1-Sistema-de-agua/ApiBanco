# Diagramas

## Arquitectura de la solución integrada

| Archivo | Qué es |
|---|---|
| `arquitectura-fase2.png` | Imagen del diagrama integrado (tema claro, 2×) |
| `arquitectura-fase2.architecture.json` | Fuente editable de Archify |

El diagrama muestra el monolito heredado (sin modificar), las cuatro features
de Fase 2, Next.js como BFF, las bases de datos, RabbitMQ y los sistemas
externos (banco, ERP y LLM).

### Regenerarlo con Archify

```bat
git clone https://github.com/tt-a1i/archify C:\Temp\archify
cd C:\Temp\archify
node archify\bin\archify.mjs validate architecture ^
  C:\Users\mario\Desktop\ApiBanco\docs\diagramas\arquitectura-fase2.architecture.json
node archify\bin\archify.mjs finalize architecture ^
  C:\Users\mario\Desktop\ApiBanco\docs\diagramas\arquitectura-fase2.architecture.json ^
  C:\Temp\arquitectura-fase2.html --quality showcase
```

Abre el HTML y usa **Export → PNG** (o captura el SVG en tema claro) para
reemplazar `arquitectura-fase2.png`.

> La imagen del README se generó con Archify 3.2.0. El JSON es la fuente de
> verdad; si cambia la arquitectura, se edita el JSON y se vuelve a exportar.
