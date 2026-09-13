# Podman-Cloud — laboratorio del modelo Cloud

> Modelo **Podman-Cloud** de
> [`Ephemeral-Jenkins-Agents-Architectures`](../README.md): Jenkins
> aprovisiona **contenedores-agente bajo demanda** sobre un Podman Host
> mediante el plugin **`docker-plugin`** (Cloud + Docker Agent Templates),
> seleccionables por *label*. Ver la
> [guía del modelo](docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md).

## El modelo

A diferencia de Podman-Host (donde el pipeline crea contenedores con
`agent { docker }`), aquí el **controller habla la API de Podman** y el plugin
crea un contenedor-agente por build. Implicaciones:

- Las imágenes deben ser **agentes Jenkins** (JDK + `jenkins/inbound-agent`).
  Se construyen **imágenes-agente híbridas** (`agent-images/`), todas sobre
  `jenkins/inbound-agent:latest-rhel-ubi9-jdk21` (UBI 9 + JDK 21).
- El **JDK del agente** (21) es independiente del **JDK de compilación**: si
  el build necesita otro (Java 17 en el backend), se aporta como **Maven
  Toolchain** (ver ADR-0006 y la sección 5.3 de la guía).
- El **workspace y las cachés** se configuran en la **plantilla**, no en el
  pipeline (que se limita a `agent { label '...' }`).
- La API y el motor son **rootful** (ver ADR-0001): encaja con `docker-plugin`
  y con los **Podman Secrets** del laboratorio.
- La API se expone con **mTLS** (PKI propia del laboratorio; ver ADR-0005).

## Estructura

```
Podman-Cloud/
├── README.md
├── deploy.sh / destroy.sh / .env.example
├── ansible/                 # common, podman_tls, jenkins_controller, podman_host, podman_secrets_tooling
├── agent-images/            # Containerfiles híbridos (inbound-agent + toolchain) + README por imagen
├── jenkins-config/          # plugins, groovy (cloud/tls/config-files), pipeline, reference-app
└── docs/                    # guía del modelo + ADRs
```

## Despliegue

```bash
cp .env.example .env         # define VM_PASSWORD y JENKINS_ADMIN_PASSWORD
./destroy.sh                 # por si hubiera restos
./deploy.sh                  # VMs + ansible-playbook site.yml
```

> **Reejecutar solo el provisionamiento (sin recrear VMs):** si las VMs ya
> están levantadas y se desea aplicar cambios de configuración (Cloud,
> pipeline, `init.groovy.d`...), **no** se utiliza `./deploy.sh` (destruye y
> recrea las VMs). Se ejecuta el playbook directamente; Ansible lee el
> `.env` del lab:
>
> ```bash
> cd ansible && ansible-playbook site.yml
> ```
>
> Jenkins se reinicia automáticamente si cambian los scripts `init.groovy.d/`.

Direccionamiento (distinto de Podman-Host para permitir coexistencia):
`jenkins-controller-cloud` = `192.168.122.30`,
`podman-cloud-host` = `192.168.122.31`.

Al terminar: Jenkins arrancado, la **Cloud `podman-cloud`** con 3 plantillas
(`maven-jdk17`, `node20`, `podman-build`), las **imágenes-agente**
construidas, la **PKI/mTLS** de la API configurada y los **3 drivers de
Podman Secrets** (`file`, `pass`, `shell`) operativos.

## Pipeline de ejemplo

`jenkins-config/jobs/reference-pipeline.groovy` usa `agent { label '...' }` en
cada stage; la Cloud aprovisiona el contenedor-agente correspondiente. El
workspace compartido (`/datos/jenkins/pipelines-workspace`) y las cachés
(`maven-cache`, `npm-cache`) los define la plantilla.

Incluye una fase final que demuestra el **consumo de un Podman Secret** y la
**inyección de kubeconfig** para `kubectl`, `kubectx` y `kubens` (el agente
`agent-podman` lleva esas herramientas).

El job `reference-pipeline` se lanza desde la UI
(`http://192.168.122.30:8080`) o por API.

## Documentación

- **Guía del modelo**: [`docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md`](docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md)
  (incluye la comparativa **rootful vs rootless**, sección 4.1, y la **PKI/mTLS**,
  sección 4.2).
- **ADRs**: [`docs/adr/`](docs/adr/README.md).

## Licencia

- **Código**: [Apache 2.0](../LICENSE) (aviso en [NOTICE](../NOTICE)).
- **Documentación y guías**: [CC BY 4.0](../guides/LICENSE).
