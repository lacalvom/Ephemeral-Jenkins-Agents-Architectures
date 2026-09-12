# ADRs — Laboratorio Podman-Cloud

Architecture Decision Records del laboratorio **Podman-Cloud** (modelo *Cloud*
con `docker-plugin`). Recogen las decisiones tecnicas especificas de su
implementacion.

## Indice

| ADR | Titulo | Estado |
|-----|--------|--------|
| [ADR-0001](./0001-rootful-api-y-motor.md) | API y motor Podman **rootful** (rootful vs rootless) | Aceptado |
| [ADR-0002](./0002-imagenes-agente-hibridas.md) | Imagenes-agente hibridas (inbound-agent + toolchain) y `user: 0` | Aceptado |
| [ADR-0003](./0003-api-tcp-sin-tls.md) | Exposicion de la API por TCP sin TLS (`podman-tcp.service`) | Sustituido por ADR-0005 |
| [ADR-0004](./0004-aprovisionamiento-via-cloud.md) | Aprovisionamiento via Cloud (sin nodo JNLP permanente) | Aceptado |
| [ADR-0005](./0005-mtls-api-podman.md) | **mTLS obligatorio** en la API de Podman (PKI propia + puerto 2376) | Aceptado |

## Como escribir un nuevo ADR

Copia [`TEMPLATE.md`](./TEMPLATE.md) con el siguiente numero correlativo y
replica el estilo de los ADRs de `Podman-Host`.

## Guias relacionadas

- Guia del modelo:
  [`../Ephemeral-Jenkins-Agents-Podman-Cloud.md`](../Ephemeral-Jenkins-Agents-Podman-Cloud.md)
- Comparativa general:
  [`../../../guides/Ephemeral-jenkins-Agents-Architectures-Compartive-guide.md`](../../../guides/Ephemeral-jenkins-Agents-Architectures-Compartive-guide.md)
