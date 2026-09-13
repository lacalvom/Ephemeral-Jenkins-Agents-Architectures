# Ephemeral Jenkins Agents Architectures

Repositorio de **laboratorios reproducibles** para probar las **tres
arquitecturas de agentes efímeros** de Jenkins. Cada modelo tiene su propio
laboratorio **autocontenido** en su carpeta, y la documentación (guías y ADRs)
es común y vive en `docs/`.

## Los tres modelos

| Modelo | Carpeta (lab) | Guía | Plugin | Entorno |
|---|---|---|---|---|
| **Podman-Host** | [`Podman-Host/`](./Podman-Host/) | [guía](./Podman-Host/docs/Ephemeral-Jenkins-Agents-Podman-host.md) | `docker-workflow` (`agent { docker { ... } }`) | contenedor por stage |
| **Podman-Cloud** | [`Podman-Cloud/`](./Podman-Cloud/) | [guía](./Podman-Cloud/docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md) | `docker-plugin` (Cloud + Docker Agent Templates) | contenedor-agente por build |
| **Jenkins-Kubernetes** | [`Jenkins-Kubernetes/`](./Jenkins-Kubernetes/) | [guía](./Jenkins-Kubernetes/docs/Ephemeral-Jenkins-Agents-Kubernetes.md) | `kubernetes-plugin` | Pod-agente por build |

Como punto de partida, la [comparativa de los tres modelos](./guides/Ephemeral-jenkins-Agents-Architectures-Compartive-guide.md)
ofrece una visión de conjunto. El
[documento unificado](./guides/Ephemeral-jenkins-agents-Architectures-models-complete-guide.md)
constituye una referencia única.

## Concepto de agente efímero

Un **agente efímero** es un entorno de ejecución aislado que se crea **bajo
demanda** para un build y se destruye al terminar. Aporta builds reproducibles,
sin restos entre ejecuciones, toolchains versionadas y escalado bajo demanda.
Las tres arquitecturas se diferencian en **quién** crea ese entorno, **dónde**
corre y **cómo** se gestionan workspace y cachés.

## Estructura del repositorio

```
Ephemeral-Jenkins-Agents-Architectures/
├── README.md                     # Este índice general
├── CHANGELOG.md                  # Historial de cambios
├── CONTRIBUTING.md               # Guía de contribución
├── LICENSE                       # Licencia del código (Apache 2.0)
├── NOTICE                        # Aviso de atribución (Apache 2.0)
├── guides/                       # Guías GENERALES
│   ├── 1_...Comparative.md       #   Comparativa de los tres modelos
│   ├── 5_...models.md            #   Documento unificado
│   └── LICENSE                   #   Licencia CC BY 4.0 de las guías
├── Podman-Host/                  # Lab del modelo Podman-Host (completo)
│   ├── README.md
│   ├── deploy.sh / destroy.sh / .env.example
│   ├── ansible/  jenkins-config/  legacy-images/
│   └── docs/
│       ├── adr/                                    # ADRs del lab Podman-Host
│       └── Ephemeral-Jenkins-Agents-Podman-host.md # guía del modelo
├── Podman-Cloud/                 # Lab del modelo Podman-Cloud
│   ├── README.md
│   └── docs/
│       ├── adr/                                    # ADRs del lab Podman-Cloud
│       └── Ephemeral-Jenkins-Agents-Podman-Cloud.md
└── Jenkins-Kubernetes/           # Lab del modelo Jenkins-Kubernetes (en preparación)
    ├── README.md
    └── docs/
        ├── adr/                                    # ADRs del lab Jenkins-Kubernetes
        └── Ephemeral-Jenkins-Agents-Kubernetes.md
```

Cada laboratorio de modelo es **autocontenido**: incluye su provisioning
(`deploy.sh`/`destroy.sh`), su playbook de Ansible, su configuración de Jenkins
y sus imágenes. Solo comparten las **guías generales** (`guides/`) y las
licencias.

## Estado de los laboratorios

| Lab | Estado | Notas |
|---|---|---|
| **Podman-Host** | Funcional | Laboratorio completo y probado (Jenkins Controller + Podman Host + agente + Podman Secrets). |
| **Podman-Cloud** | Implementado | Rootful (API + motor); Cloud `docker-plugin` con imágenes-agente híbridas y 3 drivers de secrets. Pendiente de validación end-to-end en VMs. |
| **Jenkins-Kubernetes** | En preparación | Guía completa; el lab se implementará después. |

## Documentación

- [Guías generales](./guides/) — comparativa de los tres modelos y documento
  unificado.
- Guías por modelo: `Podman-Host/docs/`, `Podman-Cloud/docs/` y
  `Jenkins-Kubernetes/docs/`.
- [ADRs](./Podman-Host/docs/adr/README.md) — decisiones técnicas del laboratorio Podman-Host.

## Licencia

- **Código** (scripts, roles de Ansible, configuración): [Apache 2.0](./LICENSE),
  con el aviso de atribución en [NOTICE](./NOTICE).
- **Documentación y guías** (`guides/` y `<lab>/docs/`): [CC BY 4.0](./guides/LICENSE).
- **Marcas:** "Cloudsdoers" y su logotipo son marcas de Cloudsdoers
  (https://cloudsdoers.com). Las licencias anteriores no conceden derechos
  sobre ellas.

## Autor

**Cloudsdoers** — Luis Alberto Calvo Muñiz <luis.calvo@cloudsdoers.com>
