# agent-podman

Imagen-agente **híbrida** para el modelo Podman-Cloud, orientada a **empaquetar
imágenes de contenedor** y a **operar contra Kubernetes**.

## Contenido

- Base: `docker.io/jenkins/inbound-agent:latest-jdk17` (Debian + JDK 17 + agente).
- Toolchain:
  - **Podman** (cliente): habla con el motor del **host** a través del socket
    que monta la plantilla (`CONTAINER_HOST=unix:///run/podman/podman.sock`).
  - **kubectl** (cliente oficial de Kubernetes).
  - **kubectx** y **kubens** (cambio rápido de contexto y namespace).
  - `git`, `jq`, `curl`, `gzip`.

## Podman-out-of-Podman

El contenedor no corre su propio motor: el `podman` de dentro usa el del host
mediante el socket rootful montado por la plantilla. Así el `podman build` del
pipeline construye la imagen en el almacén del host (`reference-backend:latest`,
`reference-frontend:latest`) y queda disponible para `podman compose`.

## Kubernetes (kubeconfig)

Las herramientas de Kubernetes (kubectl/kubectx/kubens) **no** llevan ningún
kubeconfig dentro: la configuración se **inyecta en tiempo de ejecución** en el
pipeline con `configFileProvider(...)` (buena práctica: no hornear credenciales
en la imagen). Ver `reference-pipeline.groovy`, stage de empaquetado, y la guía
del modelo.

## Construir

```bash
podman build --format docker -t localhost/agent-podman:latest \
  Podman-Cloud/agent-images/agent-podman/
```

En el laboratorio la construye automáticamente el rol `podman_host` (Ansible).

## Uso

Plantilla `agent-podman` (label `podman-build`):

```groovy
agent { label 'podman-build' }
```
