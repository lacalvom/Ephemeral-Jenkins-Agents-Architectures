# Ephemeral Jenkins Agents Architectures

Repositorio de **laboratorios reproducibles** para probar las **tres
arquitecturas de agentes efímeros** de Jenkins. Cada modelo tiene su propio
laboratorio **autocontenido** en su carpeta, y la documentación (guías y ADRs)
es común y vive en `docs/`.

## Los tres modelos

| Modelo | Carpeta (lab) | Guía | Plugin | Entorno |
|---|---|---|---|---|
| **Podman-Host** | [`Podman-Host/`](./Podman-Host/) | [guía](./docs/guides/2_Ephemeral-Jenkins-Agents-Podman-host.md) | `docker-workflow` (`agent { docker { ... } }`) | contenedor por stage |
| **Podman-Cloud** | [`Podman-Cloud/`](./Podman-Cloud/) | [guía](./docs/guides/3_Ephemeral-Jenkins-Agents-Podman-Cloud.md) | `docker-plugin` (Cloud + Docker Agent Templates) | contenedor-agente por build |
| **Jenkins-Kubernetes** | [`Jenkins-Kubernetes/`](./Jenkins-Kubernetes/) | [guía](./docs/guides/4_Ephemeral-Jenkins-Agents-Kubernetes.md) | `kubernetes-plugin` | Pod-agente por build |

**Empieza por la [comparativa de los tres modelos](./docs/guides/1_Ephemeral-jenkins-Agents-Architectures-Compartive.md)**
y, si prefieres un único documento, el
[documento unificado](./docs/guides/5_Ephemeral-jenkins-agents-Architectures-models.md).

## Qué es un agente efímero

Un **agente efímero** es un entorno de ejecución aislado que se crea **bajo
demanda** para un build y se destruye al terminar: builds reproducibles, sin
restos entre ejecuciones, con toolchains versionadas y escalado bajo demanda.
Las tres arquitecturas se diferencian en **quién** crea ese entorno, **dónde**
corre y **cómo** se gestionan workspace y cachés.

## Estructura del repositorio

```
Ephemeral-Jenkins-Agents-Architectures/
├── README.md                     # Este índice general
├── CHANGELOG.md                  # Historial de cambios
├── CONTRIBUTING.md               # Guia de contribucion
├── LICENSE                       # Licencia del codigo (Apache 2.0)
├── NOTICE                        # Aviso de atribucion (Apache 2.0)
├── docs/
│   ├── adr/                      # Architecture Decision Records (comunes)
│   └── guides/                   # Guias de los 3 modelos (comunes)
├── Podman-Host/                  # Lab del modelo Podman-Host (completo)
│   ├── README.md
│   ├── deploy.sh / destroy.sh
│   ├── .env.example
│   ├── ansible/
│   ├── jenkins-config/
│   └── legacy-images/
├── Podman-Cloud/                 # Lab del modelo Podman-Cloud (en preparacion)
│   └── README.md
└── Jenkins-Kubernetes/           # Lab del modelo Jenkins-Kubernetes (en preparacion)
    └── README.md
```

Cada laboratorio de modelo es **autocontenido**: incluye su provisioning
(`deploy.sh`/`destroy.sh`), su playbook de Ansible, su configuración de Jenkins
y sus imágenes. Solo comparten la documentación (`docs/`) y las licencias.

## Estado de los laboratorios

| Lab | Estado | Notas |
|---|---|---|
| **Podman-Host** | **Funcional** | Laboratorio completo y probado (Jenkins Controller + Podman Host + agente + Podman Secrets). |
| **Podman-Cloud** | En preparacion | Guia completa; el lab se implementara despues. |
| **Jenkins-Kubernetes** | En preparacion | Guia completa; el lab se implementara despues. |

## Documentacion

- [Guias de arquitecturas](./docs/guides/) — comparativa + un documento por
  modelo + documento unificado (con orden de lectura `1_`..`5_`).
- [ADRs](./docs/adr/README.md) — decisiones tecnicas del laboratorio Podman-Host.

## Licencia

- **Codigo** (scripts, roles de Ansible, configuracion): [Apache 2.0](./LICENSE),
  con el aviso de atribucion en [NOTICE](./NOTICE).
- **Documentacion y guias** (`docs/`): [CC BY 4.0](./docs/guides/LICENSE).
- **Marcas:** "Cloudsdoers" y su logotipo son marcas de Cloudsdoers
  (https://cloudsdoers.com). Las licencias anteriores no conceden derechos
  sobre ellas.

## Autor

**Cloudsdoers** — Luis Alberto Calvo Muñiz <luis.calvo@cloudsdoers.com>
