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

1. **Imágenes-agente híbridas**: `FROM docker.io/jenkins/inbound-agent:latest-jdk17`
   + la toolchain por `apt-get`:
   - `agent-maven-jdk17` (Maven + git),
   - `agent-node20` (Node 20 vía NodeSource + git),
   - `agent-podman` (cliente Podman + git).
   Se versionan en `agent-images/` y las construye Ansible con `podman build`.

2. **Usuario de contenedor `user: 0` en las plantillas**: con el motor rootful
   (ADR-0001), el root del contenedor es root real del host, por lo que tiene
   permisos sobre el workspace (propiedad de `jenkins`/1100) y sobre las cachés
   (named volumes) sin necesitar trucos de UID.

## Consecuencias

### Positivas

- Las imágenes valen tanto para la Cloud como para cualquier uso de agente
  inbound; el agente corre dentro del contenedor (aislado).
- El workspace y las cachés funcionan sin `--userns=keep-id`.
- Build de las imágenes reproducible (Ansible + Containerfiles versionados).

### Negativas

- Las imágenes pesan más (llevan JDK + agente + toolchain) y hay que mantenerlas
  (una por toolchain).
- Correr como `user: 0` reduce el aislamiento dentro del contenedor (coherente
  con la decisión rootful del ADR-0001).

### Neutras / trade-offs

- Node se instala desde NodeSource porque el de los repos base (Debian) es
  demasiado antiguo para Angular 20.

## Alternativas consideradas

- **Imágenes de toolchain puras (sin agente)**: no sirven: en este modelo el
  contenedor es el agente.
- **`--userns=keep-id`**: descartado, el plugin no lo soporta.
- **Workspace/cachés world-writable (0777)**: evitaría `user: 0`, pero es más
  sucio y menos seguro.

## Referencias

- `docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md` (secciones 5 y 8)
- `agent-images/` del lab
- https://github.com/jenkinsci/docker-plugin
