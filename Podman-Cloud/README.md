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

- Las imágenes deben ser **agentes Jenkins** (JDK + `jenkins/inbound-agent`) →
  se construyen **imágenes-agente híbridas** (`agent-images/`).
- El **workspace y las cachés** se configuran en la **plantilla**, no en el
  pipeline (que solo usa `agent { label '...' }`).
- La API y el motor son **rootful** (ver ADR-0001): encaja con `docker-plugin`
  y con los **Podman Secrets** del laboratorio.
- La API se expone con **mTLS** (PKI propia del laboratorio; ver ADR-0005).

## Estructura

```
Podman-Cloud/
├── README.md
├── deploy.sh / destroy.sh / .env.example
├── ansible/                 # common, podman_tls, jenkins_controller, podman_host, podman_secrets_tooling
├── agent-images/            # Containerfiles hibridos (inbound-agent + toolchain) + README por imagen
├── jenkins-config/          # plugins, groovy (cloud/tls/config-files), pipeline, reference-app
└── docs/                    # guia del modelo + ADRs
```

## Despliegue

```bash
cp .env.example .env         # define VM_PASSWORD y JENKINS_ADMIN_PASSWORD
./destroy.sh                 # por si hubiera restos
./deploy.sh                  # VMs + ansible-playbook site.yml (5 fases)
```

IPs (distintas de Podman-Host para poder coexistir):
`jenkins-controller-cloud` = `192.168.122.30`,
`podman-cloud-host` = `192.168.122.31`.

Al terminar: Jenkins arrancado, la **Cloud `podman-cloud`** con 3 plantillas
(`maven-jdk17`, `node20`, `podman-build`), las **imágenes-agente** construidas,
la **PKI/mTLS** de la API configurada y los **3 drivers de Podman Secrets**
(`file`, `pass`, `shell`) listos.

## Pipeline de ejemplo

`jenkins-config/jobs/reference-pipeline.groovy` usa `agent { label '...' }` en
cada stage; la Cloud aprovisiona el contenedor-agente correspondiente. El
workspace compartido (`/datos/jenkins/pipelines-workspace`) y las cachés
(`maven-cache`, `npm-cache`) los define la plantilla.

Incluye una fase final que demuestra el **consumo de un Podman Secret** y el
**kubeconfig inyectado** para `kubectl`/`kubectx`/`kubens` (el agente
`agent-podman` lleva esas herramientas).

Lanza el job `reference-pipeline` desde la UI (`http://192.168.122.30:8080`)
o por API.

## Documentación

- **Guía del modelo**: [`docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md`](docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md)
  (incluye la comparativa **rootful vs rootless**, sección 4.2, y la **PKI/mTLS**,
  sección 4.4).
- **ADRs**: [`docs/adr/`](docs/adr/README.md).

## Licencia

- **Código**: [Apache 2.0](../LICENSE) (aviso en [NOTICE](../NOTICE)).
- **Documentación y guías**: [CC BY 4.0](../guides/LICENSE).
