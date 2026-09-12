# Arquitecturas de Agentes Efímeros en Jenkins — Guías generales

Documentación **general** (compara los tres modelos y los reúne). La guía
específica de **cada modelo vive dentro de su laboratorio** (`<lab>/docs/guides/`).

## En esta carpeta (raíz `guides/`)

1. **[1_Ephemeral-jenkins-Agents-Architectures-Compartive.md](./1_Ephemeral-jenkins-Agents-Architectures-Compartive.md)**
   — Comparativa de los tres modelos. **Empieza aquí.**
2. **[5_Ephemeral-jenkins-agents-Architectures-models.md](./5_Ephemeral-jenkins-agents-Architectures-models.md)**
   — **Documento unificado** (todo en uno). Úsalo como referencia/consulta.

## Guías por modelo (en cada laboratorio)

| # | Modelo | Guía |
|---|---|---|
| 2 | **Podman-Host** | [`Podman-Host/docs/guides/2_Ephemeral-Jenkins-Agents-Podman-host.md`](../Podman-Host/docs/guides/2_Ephemeral-Jenkins-Agents-Podman-host.md) |
| 3 | **Podman-Cloud** | [`Podman-Cloud/docs/guides/3_Ephemeral-Jenkins-Agents-Podman-Cloud.md`](../Podman-Cloud/docs/guides/3_Ephemeral-Jenkins-Agents-Podman-Cloud.md) |
| 4 | **Jenkins-Kubernetes** | [`Jenkins-Kubernetes/docs/guides/4_Ephemeral-Jenkins-Agents-Kubernetes.md`](../Jenkins-Kubernetes/docs/guides/4_Ephemeral-Jenkins-Agents-Kubernetes.md) |

## ADRs

Los *Architecture Decision Records* del laboratorio implementado están en
[`Podman-Host/docs/adr/README.md`](../Podman-Host/docs/adr/README.md).

## Licencia

Los documentos de las guías se distribuyen bajo la licencia
[Creative Commons Atribución 4.0 Internacional (CC BY 4.0)](./LICENSE).
El código del repositorio usa por separado la licencia
Apache 2.0 ([`LICENSE`](../LICENSE)).

"Cloudsdoers" y su logotipo son marcas de Cloudsdoers (https://cloudsdoers.com).
Las licencias anteriores no conceden derechos sobre ellas.

---

*Licencia: [CC BY 4.0](./LICENSE). © 2026 Cloudsdoers.*
