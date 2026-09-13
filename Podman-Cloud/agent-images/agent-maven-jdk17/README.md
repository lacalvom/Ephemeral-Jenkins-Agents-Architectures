# agent-maven-jdk17

Imagen-agente **híbrida** para el modelo Podman-Cloud: runtime del agente
Jenkins (JDK 21) + Maven + **toolchain JDK 17** para compilar.

## Contenido

- Base: `docker.io/jenkins/inbound-agent:latest-rhel-ubi9-jdk21`
  (UBI 9, JDK 21, agente Jenkins).
- Toolchain del build: `maven` + `java-17-openjdk-devel` + `git`.
- `toolchains.xml` en `/opt/toolchains/toolchains.xml` que declara el JDK 17.

## JDK del agente frente a JDK de compilación

- El **agente** se ejecuta sobre el **JDK 21** de la imagen base (coherente
  con el JDK del Jenkins Controller).
- El **build** se compila con **JDK 17** seleccionado por **Maven
  Toolchains**, con independencia del JDK que ejecuta Maven. Ver ADR-0006.

El `pom.xml` activa `maven-toolchains-plugin` (JDK 17) y el pipeline invoca
Maven con `-t /opt/toolchains/toolchains.xml`. Ver la guía del modelo,
sección 5.3.

## Construir

```bash
podman build --format docker -t localhost/agent-maven-jdk17:latest \
  Podman-Cloud/agent-images/agent-maven-jdk17/
```

En el laboratorio la construye automáticamente el rol `podman_host`
(Ansible) durante `deploy.sh`.

## Uso

Plantilla `agent-maven-jdk17` (label `maven-jdk17`):

```groovy
agent { label 'maven-jdk17' }
```

La caché de Maven se monta como *named volume* (`maven-cache:/cache/.m2`) y
el pipeline utiliza `-Dmaven.repo.local=/cache/.m2/repository`.
