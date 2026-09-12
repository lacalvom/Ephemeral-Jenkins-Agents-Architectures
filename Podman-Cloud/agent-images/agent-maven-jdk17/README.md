# agent-maven-jdk17

Imagen-agente **híbrida** para el modelo Podman-Cloud: incluye el runtime del
agente Jenkins (`jenkins/inbound-agent`) **y** la toolchain Maven sobre Java 17.

## Contenido

- Base: `docker.io/jenkins/inbound-agent:latest-jdk17` (Debian + JDK 17 + agente).
- Toolchain: `maven`, `git`.

## Por qué es "híbrida"

En el modelo Podman-Cloud el contenedor **es** el agente Jenkins (conecta por
JNLP y ejecuta el build), a diferencia del modelo Podman-Host (donde el plugin
hace `docker exec` y sirve cualquier imagen). Por eso la imagen debe llevar el
agente además de la toolchain. Ver ADR-0002 del lab.

## Construir

```bash
# desde el podman-host (rootful) o en local
podman build --format docker -t localhost/agent-maven-jdk17:latest \
  Podman-Cloud/agent-images/agent-maven-jdk17/
```

En el laboratorio la construye automáticamente el rol `podman_host`
(Ansible) durante `deploy.sh`.

## Uso

Se usa a través de la **plantilla** `agent-maven-jdk17` de la Cloud
(label `maven-jdk17`). El `Jenkinsfile` solo hace:

```groovy
agent { label 'maven-jdk17' }
```

La caché de Maven se monta como *named volume* (`maven-cache:/cache/.m2`) y el
pipeline usa `-Dmaven.repo.local=/cache/.m2/repository` (ver la plantilla en
`create-cloud.groovy` y la guía del modelo, sección de cachés).
