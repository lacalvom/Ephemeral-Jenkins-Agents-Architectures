# agent-node20

Imagen-agente **híbrida** para el modelo Podman-Cloud: runtime del agente
Jenkins + toolchain **Node 20 / npm**.

## Contenido

- Base: `docker.io/jenkins/inbound-agent:latest-jdk17` (Debian + JDK 17 + agente).
- Toolchain: `nodejs` 20 (NodeSource), `npm`, `git`.

> Nota: Node se instala desde **NodeSource** porque el Node de los repos base
> de Debian es demasiado antiguo para Angular 20. La imagen siempre lleva un
> JDK (para el propio agente), aunque el proyecto sea Node (ver ADR-0002).

## Construir

```bash
podman build --format docker -t localhost/agent-node20:latest \
  Podman-Cloud/agent-images/agent-node20/
```

En el laboratorio la construye automáticamente el rol `podman_host` (Ansible).

## Uso

Plantilla `agent-node20` (label `node20`):

```groovy
agent { label 'node20' }
```

La caché de npm se monta como *named volume* (`npm-cache:/cache/.npm`) y el
pipeline hace `npm config set cache /cache/.npm` (ver `create-cloud.groovy`).
