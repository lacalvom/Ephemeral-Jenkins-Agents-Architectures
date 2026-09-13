# Arquitecturas de Agentes Efímeros en Jenkins — Guías generales

Documentación **general** (compara los tres modelos y los reúne). La guía
específica de **cada modelo vive dentro de su laboratorio** (`<lab>/docs/`).

## Contenido de esta carpeta (`guides/`)

1. **[Ephemeral-jenkins-Agents-Architectures-Compartive-guide.md](./Ephemeral-jenkins-Agents-Architectures-Compartive-guide.md)**
   — Comparativa de los tres modelos. Punto de partida recomendado.
2. **[Ephemeral-jenkins-agents-Architectures-models-complete-guide.md](./Ephemeral-jenkins-agents-Architectures-models-complete-guide.md)**
   — Documento unificado, con todo en un solo lugar. Pensado como referencia
   de consulta.

## Guías por modelo

| Modelo | Guía |
|---|---|
| **Podman-Host** | [`Podman-Host/docs/Ephemeral-Jenkins-Agents-Podman-host.md`](../Podman-Host/docs/Ephemeral-Jenkins-Agents-Podman-host.md) |
| **Podman-Cloud** | [`Podman-Cloud/docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md`](../Podman-Cloud/docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md) |
| **Jenkins-Kubernetes** | [`Jenkins-Kubernetes/docs/Ephemeral-Jenkins-Agents-Kubernetes.md`](../Jenkins-Kubernetes/docs/Ephemeral-Jenkins-Agents-Kubernetes.md) |

## ADRs

Los *Architecture Decision Records* del laboratorio implementado están en
[`Podman-Host/docs/adr/README.md`](../Podman-Host/docs/adr/README.md).

## Licencia

Los documentos de las guías se distribuyen bajo la licencia
[Creative Commons Atribución 4.0 Internacional (CC BY 4.0)](./LICENSE).
El código del repositorio se distribuye por separado bajo licencia
Apache 2.0 ([`LICENSE`](../LICENSE)).

"Cloudsdoers" y su logotipo son marcas de Cloudsdoers (https://cloudsdoers.com).
Las licencias anteriores no conceden derechos sobre ellas.

---

*Licencia: [CC BY 4.0](./LICENSE). © 2026 Cloudsdoers.*
