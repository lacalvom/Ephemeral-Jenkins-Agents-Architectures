# agent-node20

Imagen-agente **híbrida** para el modelo Podman-Cloud: runtime del agente
Jenkins (JDK 21) + toolchain **Node 20 / npm**.

## Contenido

- Base: `docker.io/jenkins/inbound-agent:latest-rhel-ubi9-jdk21`
  (UBI 9, JDK 21, agente Jenkins).
- Toolchain: `nodejs` 20 (NodeSource), `npm`, `git`.

> El agente necesita una JVM (la trae la base, JDK 21) aunque el proyecto sea
> Node; para compilar Node no se utiliza toolchain de Java. Ver ADR-0006
> sobre la separación JDK del agente / JDK de compilación.

## Construir

```bash
podman build --format docker -t localhost/agent-node20:latest \
  Podman-Cloud/agent-images/agent-node20/
```

En el laboratorio la construye automáticamente el rol `podman_host`
(Ansible).

## Uso

Plantilla `agent-node20` (label `node20`):

```groovy
agent { label 'node20' }
```

La caché de npm se monta como *named volume* (`npm-cache:/cache/.npm`) y el
pipeline ejecuta `npm config set cache /cache/.npm`.
