# Podman-Cloud — Laboratorio del modelo Podman-Cloud

> **Estado: en preparacion.** Este directorio contendrá el laboratorio
> reproducible del modelo **Podman-Cloud**. La guía ya está escrita
> ([`docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md`](docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md));
> el codigo del lab se implementará después.

## El modelo

Jenkins aprovisiona **contenedores-agente bajo demanda** sobre un Podman Host
usando el plugin **`docker-plugin`** (una *Cloud* + *Docker Agent Templates*),
seleccionables por *label*. A diferencia de Podman-Host, aquí las imágenes
deben ser **agentes Jenkins** (JDK + `jenkins/inbound-agent`) y el workspace y
las cachés se configuran en la **plantilla**, no en el `Jenkinsfile`.

Referencia: [guía del modelo Podman-Cloud](docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md).

## Roadmap del laboratorio

Pendiente de implementar. Piezas previstas:

1. **Provisioning de VMs** (reutilizando el patrón de `Podman-Host/`): un
   Jenkins Controller y un Podman Host.
2. **Exponer la API de Podman** de forma segura (túnel SSH o TCP+TLS) para que
   el controller pueda hablar con `docker-plugin`.
3. **Imágenes-agente híbridas** (toolchain + `jenkins/inbound-agent`):
   `agent-maven-jdk17`, `agent-node20`, `agent-podman`.
4. **Configuración de la Cloud** por JCasC (`jenkins.clouds.docker`) con una
   plantilla por toolchain (labels, `remoteFs`, volúmenes de workspace y cachés).
5. **Pipeline de ejemplo** con `agent { label '...' }` y etapas por toolchain.
6. **Documentación y ADRs** de las decisiones del lab.

> Mientras tanto, la guía explica el modelo, cómo configurarlo y cómo se
> gestionan workspace, cachés y selección de agente.
