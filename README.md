# Ephemeral Jenkins Agents Architectures

Repositorio de **laboratorios reproducibles** para probar las **tres
arquitecturas de agentes efímeros** de Jenkins. Cada modelo tiene su propio
laboratorio **autocontenido** en su carpeta, y la documentación (guías y ADRs)
es común y vive en `docs/`.

## Los tres modelos

| Modelo | Carpeta (lab) | Guía | Plugin | Entorno |
|---|---|---|---|---|
| **Podman-Host** | [`Podman-Host/`](./Podman-Host/) | [guía](./Podman-Host/docs/guides/Ephemeral-Jenkins-Agents-Podman-host.md) | `docker-workflow` (`agent { docker { ... } }`) | contenedor por stage |
| **Podman-Cloud** | [`Podman-Cloud/`](./Podman-Cloud/) | [guía](./Podman-Cloud/docs/guides/Ephemeral-Jenkins-Agents-Podman-Cloud.md) | `docker-plugin` (Cloud + Docker Agent Templates) | contenedor-agente por build |
| **Jenkins-Kubernetes** | [`Jenkins-Kubernetes/`](./Jenkins-Kubernetes/) | [guía](./Jenkins-Kubernetes/docs/guides/Ephemeral-Jenkins-Agents-Kubernetes.md) | `kubernetes-plugin` | Pod-agente por build |

**Empieza por la [comparativa de los tres modelos](./guides/Ephemeral-jenkins-Agents-Architectures-Compartive-guide.md)**
y, si prefieres un único documento, el
[documento unificado](./guides/Ephemeral-jenkins-agents-Architectures-models-complete-guide.md).

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
├── guides/                       # Guias GENERALES
│   ├── 1_...Compartive.md        #   Comparativa de los tres modelos
│   ├── 5_...models.md            #   Documento unificado
│   └── LICENSE                   #   Licencia CC BY 4.0 de las guias
├── Podman-Host/                  # Lab del modelo Podman-Host (completo)
│   ├── README.md
│   ├── deploy.sh / destroy.sh / .env.example
│   ├── ansible/  jenkins-config/  legacy-images/
│   └── docs/
│       ├── adr/                  #   ADRs del lab Podman-Host
│       └── guides/               #   2_... (guia del modelo Podman-Host)
├── Podman-Cloud/                 # Lab del modelo Podman-Cloud (en preparacion)
│   ├── README.md
│   └── docs/guides/              #   3_... (guia del modelo Podman-Cloud)
└── Jenkins-Kubernetes/           # Lab del modelo Jenkins-Kubernetes (en preparacion)
    ├── README.md
    └── docs/guides/              #   4_... (guia del modelo Jenkins-Kubernetes)
```

Cada laboratorio de modelo es **autocontenido**: incluye su provisioning
(`deploy.sh`/`destroy.sh`), su playbook de Ansible, su configuración de Jenkins
y sus imágenes. Solo comparten las **guías generales** (`guides/`) y las
licencias.

## Estado de los laboratorios

| Lab | Estado | Notas |
|---|---|---|
| **Podman-Host** | **Funcional** | Laboratorio completo y probado (Jenkins Controller + Podman Host + agente + Podman Secrets). |
| **Podman-Cloud** | En preparacion | Guia completa; el lab se implementara despues. |
| **Jenkins-Kubernetes** | En preparacion | Guia completa; el lab se implementara despues. |

## Documentacion

- [Guias generales](./guides/) — comparativa de los tres modelos y documento
  unificado.
- Guias por modelo: `Podman-Host/docs/guides/`, `Podman-Cloud/docs/guides/` y
  `Jenkins-Kubernetes/docs/guides/`.
- [ADRs](./Podman-Host/docs/adr/README.md) — decisiones tecnicas del laboratorio Podman-Host.

## Licencia

- **Codigo** (scripts, roles de Ansible, configuracion): [Apache 2.0](./LICENSE),
  con el aviso de atribucion en [NOTICE](./NOTICE).
- **Documentacion y guias** (`guides/` y `<lab>/docs/`): [CC BY 4.0](./guides/LICENSE).
- **Marcas:** "Cloudsdoers" y su logotipo son marcas de Cloudsdoers
  (https://cloudsdoers.com). Las licencias anteriores no conceden derechos
  sobre ellas.

## Autor

**Cloudsdoers** — Luis Alberto Calvo Muñiz <luis.calvo@cloudsdoers.com>
