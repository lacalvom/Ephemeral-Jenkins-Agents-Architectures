# ADR-0002: Imágenes-agente híbridas y usuario de contenedor

- **Estado:** Aceptado
- **Fecha:** 2026-09-12
- **Decisor:** DevOps Team

## Contexto

En el modelo Podman-Cloud el contenedor que crea la Cloud **es el agente
Jenkins** (conecta por JNLP y ejecuta el build). A diferencia del modelo
Podman-Host (donde el plugin hace `docker exec` y sirve cualquier imagen), aquí
la imagen debe **llevar el runtime del agente** además de la toolchain.

Además, el plugin `docker-plugin` **no soporta `--userns=keep-id`** ni un campo
`userns`, así que no se puede mapear el UID del contenedor al usuario del host
como en Podman-Host. Hay que resolver los permisos sobre el workspace
compartido y las cachés de otra forma.

## Decisión

1. **Imágenes-agente híbridas**: `FROM docker.io/jenkins/inbound-agent:latest-rhel-ubi9-jdk21`
   (UBI 9, JDK 21) + la toolchain por `dnf`:
   - `agent-maven-jdk17` (Maven + **toolchain JDK 17** + git),
   - `agent-node20` (Node 20 vía NodeSource + git),
   - `agent-podman` (cliente Podman + kubectl/kubectx/kubens + git).
   Se versionan en `agent-images/` y las construye Ansible con `podman build`.

   Se elige **UBI 9** por coherencia con AlmaLinux 9 del resto del laboratorio
   (RHEL 9) y porque es una base soportada por Red Hat. El **JDK del agente es
   21** (alineado con el del controller); cuando el build necesita otro JDK
   (p. ej. Java 17) se aporta como **toolchain** (ver ADR-0006).

2. **Usuario de contenedor `user: 0` en las plantillas**: con el motor rootful
   (ADR-0001), el root del contenedor es root real del host, por lo que tiene
   permisos sobre el workspace (propiedad de `jenkins`/1100) y sobre las cachés
   (named volumes) sin necesitar trucos de UID. El usuario se fija en el
   **`DockerTemplateBase`** (campo *User* de la plantilla,
   `containerConfig.withUser`), **no** en el `DockerComputerJNLPConnector`: el
   `user` del conector es legacy y no afecta al contenedor. Si se pone solo en
   el conector, el agente corre como el usuario por defecto de la imagen
   (`jenkins`, UID 1000) y falla al escribir en el workspace (UID 1100).

3. **`securityOpts = "label=disable"` en las TRES plantillas**: el workspace se
   monta como *bind* del host y, con SELinux en `enforcing`, el contenedor no
   puede escribir en él salvo que se relabele. El `docker-plugin` no puede
   expresar `:z`, por lo que se desactiva SELinux para el contenedor. Aplica
   también a las plantillas que no montan el socket; si no, el primer build
   falla con `java.nio.file.AccessDeniedException` sobre `<workspace>/<job>@tmp`.

## Consecuencias

### Positivas

- Las imágenes valen tanto para la Cloud como para cualquier uso de agente
  inbound; el agente corre dentro del contenedor (aislado).
- El workspace y las cachés funcionan sin `--userns=keep-id`.
- Build de las imágenes reproducible (Ansible + Containerfiles versionados).
- Base **UBI 9** coherente con AlmaLinux 9; el **JDK del agente (21)** se
  desacopla del **JDK de compilación (17)** vía Maven Toolchains (ADR-0006).

### Negativas

- Las imágenes pesan más (llevan JDK + agente + toolchain) y hay que mantenerlas
  (una por toolchain).
- Correr como `user: 0` y con `label=disable` reduce el aislamiento dentro del
  contenedor (coherente con la decisión rootful del ADR-0001). Alternativa más
  limpia: políticas SELinux con `container_file_t` en el workspace, fuera del
  alcance del laboratorio.

### Neutras / trade-offs

- Node se instala desde NodeSource porque da una versión 20 controlada
  (los repos base pueden traer una más antigua).
- `label=disable` es un parche pragmático al no poder expresar `:z` desde el
  `docker-plugin`; en el lab Podman-Host se usa el mismo enfoque (ADR-011).

## Alternativas consideradas

- **Imágenes de toolchain puras (sin agente)**: no sirven: en este modelo el
  contenedor es el agente.
- **`--userns=keep-id`**: descartado, el plugin no lo soporta.
- **Workspace/cachés world-writable (0777)**: evitaría `user: 0`, pero es más
  sucio y menos seguro.

## Referencias

- `docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md` (secciones 5 y 8)
- [`ADR-0006`](./0006-jdk-agente-vs-jdk-compilacion-toolchains.md) (JDK del agente vs JDK de compilación)
- `agent-images/` del lab
- https://github.com/jenkinsci/docker-plugin
