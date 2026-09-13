# Jenkins-Kubernetes — Laboratorio del modelo Jenkins-Kubernetes

> **Estado: en preparación.** Este directorio contendrá el laboratorio
> reproducible del modelo **Jenkins-Kubernetes**. La guía ya está escrita
> ([`docs/Ephemeral-Jenkins-Agents-Kubernetes.md`](docs/Ephemeral-Jenkins-Agents-Kubernetes.md));
> el código del lab se implementará después.

## El modelo

Jenkins se ejecuta **íntegramente sobre Kubernetes**: el controller se
despliega como un workload del clúster (StatefulSet o Deployment + PVC + RBAC)
y el plugin **`kubernetes-plugin`** crea un **Pod-agente por build**, con un
contenedor para el agente (`jnlp`) y varios de herramientas que se usan con
`container('nombre')`. No hay socket de Docker: las imágenes se construyen
con **Kaniko** o **Buildah**.

Referencia: [guía del modelo Jenkins-Kubernetes](docs/Ephemeral-Jenkins-Agents-Kubernetes.md).

## Roadmap del laboratorio

Pendiente de implementar. Componentes previstos:

1. **Clúster Kubernetes local** (kind/k3d/k3s) como base del lab.
2. **Controller en el clúster**: chart oficial `jenkins/jenkins` o manifiestos
   propios (StatefulSet + PVC + ServiceAccount + RBAC + Service).
3. **Cloud `kubernetes`** por JCasC y **pod templates** con contenedores de
   herramientas (Maven, Node, Kaniko).
4. **Workspace** (`emptyDir` + `stash`) y **cachés** (PVC o caché remota).
5. **Pipeline de ejemplo** con `container('maven')`, entre otros, y
   empaquetado con Kaniko.
6. **Documentación y ADRs** con las decisiones del lab.

> Mientras tanto, la guía explica el modelo completo: componentes, manifiestos,
> configuración del plugin, workspace, cachés y seguridad.
