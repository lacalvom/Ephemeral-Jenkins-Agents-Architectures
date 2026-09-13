# Arquitectura de Agentes Efímeros de Jenkins sobre Kubernetes (modelo `Jenkins-Kubernetes`)

> Tercer documento de la serie, complementario a:
> - `Ephemeral-Jenkins-Agents-Podman-host.md` (modelo **Podman-Host**)
> - `Ephemeral-Jenkins-Agents-Podman-Cloud.md` (modelo **Podman-Cloud**)
> - `Ephemeral-jenkins-Agents-Architectures-Compartive-guide.md` (comparativa de los 3 modelos)
> - `Ephemeral-jenkins-agents-Architectures-models-complete-guide.md` (documento unificado)
>
> En este documento se describe cómo montar **toda** la infraestructura de
> agentes efímeros de Jenkins sobre Kubernetes, **incluido el propio
> controller ejecutándose como un workload del clúster**, con el objetivo de
> poder construir después un laboratorio de Kubernetes sobre el que lanzar el
> controller y sus agentes.

---

## 1. Idea del modelo `Jenkins-Kubernetes`

- Se instala el plugin **`kubernetes`** (`kubernetes-plugin`) en Jenkins.
- El plugin trata el clúster como un **Cloud**: por cada build crea un **Pod**
  que actúa como **agente inbound**, y lo destruye al terminar.
- Dentro del Pod hay **un contenedor que ejecuta el agente** (por convención
  `jnlp`) y **uno o más contenedores de herramientas** (Maven, Node, Buildah,
  etc.), que se usan desde el pipeline con el paso **`container('nombre')`**.
- El **controller** se despliega como un workload de Kubernetes
  (StatefulSet o Deployment) con:
  - `ServiceAccount` y RBAC para crear y eliminar Pods de agentes,
  - `PersistentVolumeClaim` para `JENKINS_HOME`,
  - `Service` (`ClusterIP`, opcionalmente `Ingress`) para la UI.

### 1.1 Controller dentro o fuera del clúster

- No es obligatorio que el controller resida en el clúster. El plugin
  funciona con el controller **fuera** del clúster (VM u otro host) y los
  agentes dentro (modelo "híbrido"), siempre que el controller sea
  alcanzable desde los Pods (a menudo mediante **WebSocket**).
- Si se desea una arquitectura **íntegramente sobre Kubernetes**
  (autocontenida, sin depender de un host externo), la opción natural y
  recomendada es **el controller como workload del clúster**, que es el
  enfoque de esta guía.

A diferencia del modelo Podman-Host (un contenedor por stage), en
Kubernetes se crea **un Pod por build o agente**, con **varios
contenedores** dentro que pueden cambiar por stage mediante
`container(...)`.

---

## 2. Arquitectura objetivo

```
                         +------------------------------------------+
                         |          Kubernetes cluster              |
   Usuario --Ingress--> |  Service (jenkins:8080 / agent:50000)     |
                         |        |                                 |
                         |  StatefulSet: jenkins (1 réplica)        |
                         |   - contenedor jenkins/jenkins:lts-jdk21 |
                         |   - PVC jenkins-home  -> /var/jenkins_home|
                         |   - ServiceAccount jenkins                |
                         |        |  (API K8s: crea/borra Pods)      |
                         |        v                                 |
                         |  Pod agente (efímero, 1 por build)       |
                         |   - contenedor jnlp   (inbound-agent)    |
                         |   - contenedor maven  (toolchain)        |
                         |   - contenedor node   (toolchain)        |
                         |   - volumen workspace (emptyDir/PVC)     |
                         +------------------------------------------+
```

---

## 3. Conceptos del plugin Kubernetes

- **Cloud `kubernetes`**: apunta a la API del clúster. Si el controller está
  dentro, basta `https://kubernetes.default.svc` con la **ServiceAccount** del
  propio Pod; si está fuera, se utiliza un *kubeconfig* o un token.
- **Pod template**: plantilla de Pod. Se define:
  - **estáticamente** en la configuración de la Cloud (para jobs que usan
    `node('label')`), o
  - **dinámicamente** en el pipeline con el paso **`podTemplate { ... }`** (lo
    recomendado para proyectos nuevos; genera un label único `POD_LABEL`).
- **Contenedor del agente**: por defecto se llama **`jnlp`**. Puede evitarse
  usando `agentContainer: 'x'` con `agentInjection: true` (se inyecta el
  agente en el contenedor de la toolchain, ahorrando un contenedor).
- **Contenedores de herramientas**: se ejecutan comandos en ellos con
  `container('nombre') { ... }`. La variable `POD_CONTAINER` contiene el
  nombre del contenedor actual.
- **Volúmenes del Pod** (montados en **todos** los contenedores):
  `emptyDirVolume` (por defecto), `persistentVolumeClaim(...)`,
  `dynamicPVC()`, `hostPathVolume(...)`, `nfsVolume(...)`,
  `configMapVolume`, `secretVolume`.
- **`workspaceVolume`**: el volumen donde reside el workspace del job. Tipos:
  `emptyDirWorkspaceVolume` (por defecto, **efímero**),
  `persistentVolumeClaimWorkspaceVolume(...)`, `dynamicPVC()`,
  `hostPathWorkspaceVolume(...)`, `nfsWorkspaceVolume(...)`.
- **`podRetention`**: `never()` (borrar siempre), `onFailure()`, `always()`,
  `evicted()`, `default()`. Y **`idleMinutes`** para reutilizar el Pod un
  tiempo.
- **`runAsUser` y `runAsGroup`** y `securityContext`: resultan **clave** para
  que todos los contenedores del Pod compartan el **mismo UID** (en caso
  contrario, el paso `sh` falla por permisos al escribir en `workspace@tmp`;
  ver sección de resolución de problemas).

---

## 4. Componentes a desplegar en Kubernetes

1. **Namespace** (p. ej. `jenkins`).
2. **ServiceAccount** `jenkins` (para el controller).
3. **RBAC**: `Role`/`ClusterRole` y binding que permita al controller
   `pods` (create/get/list/watch/delete) y, si se usa `dynamicPVC()`,
   `persistentvolumeclaims`. El repositorio del plugin incluye un ejemplo
   en `src/main/kubernetes/service-account.yml`.
4. **PersistentVolumeClaim** `jenkins-home` (≥ 10–20 Gi) montado en
   `/var/jenkins_home`. Sin él, se pierde toda la configuración al reiniciar.
5. **StatefulSet** (o Deployment) `jenkins` con la imagen
   `jenkins/jenkins:lts-jdk21`, con:
   - el PVC montado,
   - `serviceAccountName: jenkins`,
   - recursos (`requests` y `limits`),
   - *liveness/readiness probes*,
   - (opcional) `JAVA_OPTS` con `-Djenkins.install.runSetupWizard=false` y
     `CASC_JENKINS_CONFIG` para JCasC.
6. **Service** `jenkins` (ClusterIP) exponiendo 8080 (UI) y 50000 (JNLP;
   innecesario si se utiliza WebSocket). Opcionalmente un **Ingress** para la
   UI.
7. **Configuración del controller**: plugins (incluido `kubernetes`,
   `workflow-aggregator`, `configuration-as-code`, etc.) y la definición de la
   **Cloud** por UI o **JCasC**.
8. **Imágenes de herramientas** en un registro accesible por el clúster
   (`maven`, `node`, `buildah`/`kaniko`, etc.). Pueden usarse imágenes
   públicas con `agentContainer` para evitar una imagen-agente propia.

---

## 5. Manifiestos de ejemplo

> Ejemplo mínimo y didáctico (no apto para producción). Se ajustan namespace,
> `storageClass`, recursos y versión de imagen.

```yaml
# 00-namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: jenkins
---
# 01-rbac.yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: jenkins
  namespace: jenkins
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: jenkins-agents
  namespace: jenkins
rules:
  - apiGroups: [""]
    resources: ["pods", "pods/exec", "pods/log", "pods/portforward"]
    verbs: ["create", "delete", "get", "list", "watch"]
  - apiGroups: [""]
    resources: ["persistentvolumeclaims"]
    verbs: ["create", "delete", "get", "list", "watch"]
  - apiGroups: [""]
    resources: ["events"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: jenkins-agents
  namespace: jenkins
subjects:
  - kind: ServiceAccount
    name: jenkins
    namespace: jenkins
roleRef:
  kind: Role
  name: jenkins-agents
  apiGroup: rbac.authorization.k8s.io
---
# 02-pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: jenkins-home
  namespace: jenkins
spec:
  accessModes: ["ReadWriteOnce"]
  resources:
    requests:
      storage: 20Gi
  # storageClassName: <tu-storageclass>   # opcional
---
# 03-statefulset.yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: jenkins
  namespace: jenkins
spec:
  serviceName: jenkins
  replicas: 1
  selector:
    matchLabels:
      app: jenkins
  template:
    metadata:
      labels:
        app: jenkins
    spec:
      serviceAccountName: jenkins
      securityContext:
        fsGroup: 1000
      containers:
        - name: jenkins
          image: jenkins/jenkins:lts-jdk21
          ports:
            - containerPort: 8080
            - containerPort: 50000
          env:
            - name: JAVA_OPTS
              value: "-Djenkins.install.runSetupWizard=false"
            - name: CASC_JENKINS_CONFIG
              value: "/var/jenkins_home/casc_configs/jenkins.yaml"
          volumeMounts:
            - name: jenkins-home
              mountPath: /var/jenkins_home
          readinessProbe:
            httpGet:
              path: /login
              port: 8080
            initialDelaySeconds: 60
            periodSeconds: 10
          resources:
            requests:
              cpu: "500m"
              memory: "2Gi"
            limits:
              cpu: "2"
              memory: "4Gi"
      volumes:
        - name: jenkins-home
          persistentVolumeClaim:
            claimName: jenkins-home
---
# 04-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: jenkins
  namespace: jenkins
spec:
  selector:
    app: jenkins
  ports:
    - name: http
      port: 8080
      targetPort: 8080
    - name: agent
      port: 50000
      targetPort: 50000
```

> En Kubernetes, `jenkins/jenkins` corre como UID 1000. `fsGroup: 1000` en el
> Pod hace que el PVC sea escribible. Si se utiliza una StorageClass con
> `ReadWriteOnce`, basta con una sola réplica.

---

## 6. Configurar la Cloud Kubernetes

### 6.1 Por UI

`Manage Jenkins → Clouds → New cloud → Kubernetes`:

- **Kubernetes URL**: `https://kubernetes.default.svc` (si el controller está
  dentro) o la URL del API externo.
- **Kubernetes Namespace**: `jenkins`.
- **Credentials**: "Kubernetes Service Account" (si el controller está
  dentro) o kubeconfig/token.
- **Jenkins URL**: `http://jenkins.jenkins.svc.cluster.local:8080/`.
- **WebSocket**: se activa únicamente si el controller está fuera del clúster.
- **Container Cap** y **Pod retention**.

### 6.2 Por JCasC (Configuration as Code)

```yaml
jenkins:
  clouds:
    - kubernetes:
        name: "kubernetes"
        serverUrl: "https://kubernetes.default.svc"
        namespace: "jenkins"
        jenkinsUrl: "http://jenkins.jenkins.svc.cluster.local:8080/"
        containerCap: 10
        # Si el controller está DENTRO del clúster, se usa la ServiceAccount
        # (el plugin detecta el token montado). Si está FUERA, se añade
        # credentialsId con un kubeconfig/token.
        # credentialsId: "kubeconfig"
        templates:
          - name: "maven"
            label: "maven"
            agentContainer: "maven"
            agentInjection: true
            serviceAccount: "jenkins"
            workspaceVolume:
              emptyDirWorkspaceVolume: {}
            containers:
              - name: "maven"
                image: "maven:3.9.9-eclipse-temurin-17"
                command: "sleep"
                args: "99d"
              - name: "node"
                image: "node:20"
                command: "sleep"
                args: "99d"
```

> Los nombres exactos de los campos JCasC varían según la versión. El
> procedimiento fiable es configurar por UI y **"Export configuration as
> code"** (plugin `configuration-as-code`) para obtener el YAML exacto de la
> instalación.

---

## 7. Cómo funciona un build, paso a paso

1. El pipeline solicita un agente con un label (`node('maven')`, `POD_LABEL` o
   `agent { label 'maven' }`).
2. La Cloud **crea un Pod** con los contenedores de la plantilla. El plugin
   inyecta en el contenedor del agente las variables `JENKINS_URL`,
   `JENKINS_SECRET` y `JENKINS_AGENT_NAME`.
3. El contenedor del agente arranca el **inbound-agent** y conecta con el
   controller (HTTP, WebSocket o puerto 50000).
4. Los pasos `sh` normales se ejecutan en el **contenedor del agente**
   (`agentContainer`). Para usar una herramienta concreta se usa
   `container('maven') { sh 'mvn ...' }`, que ejecuta vía la **API exec** de
   K8s en ese contenedor.
5. El **volumen de workspace** está montado en **todos** los contenedores, de
   modo que `maven`, `node`, etc. ven los mismos ficheros.
6. Al terminar, según `podRetention` e `idleMinutes`, el Pod se **elimina**
   (o se mantiene un tiempo para reutilizarlo).

---

## 8. Workspace en Kubernetes (la pregunta clave)

En K8s **no hay `reuseNode` ni *bind mounts* al estilo Docker**. El workspace
lo define el **`workspaceVolume`** de la plantilla:

| `workspaceVolume` | Persistencia | Compartición | Uso típico |
|---|---|---|---|
| `emptyDirWorkspaceVolume` (def.) | Ninguna (muere con el Pod) | Entre contenedores del **mismo Pod** | Builds *stateless*; artefactos vía `archiveArtifacts`/`stash` |
| `persistentVolumeClaimWorkspaceVolume(...)` | Sí (PVC existente) | Entre builds (siempre el mismo PVC) | Persistir workspace, pero colisiona con concurrencia |
| `dynamicPVC()` | Sí, mientras vive el Pod | Entre contenedores del Pod | Igual que `emptyDir` pero con más espacio; se elimina con el Pod |
| `hostPathWorkspaceVolume(...)` | Sí (en el nodo) | Depende del nodo | Solo clústeres de un nodo / kind / pruebas |

Recomendaciones:

- Por defecto, `emptyDirWorkspaceVolume` es la opción correcta para agentes
  efímeros: un workspace nuevo y limpio por build (reproducibilidad).
- Para **pasar artefactos entre stages o plantillas**, se utiliza
  `stash`/`unstash` o `archiveArtifacts` (nativo de Jenkins), en lugar de
  compartir un PVC.
- Para **compartir entre stages que usan contenedores distintos** no se
  necesita nada especial: se encuentran en el **mismo Pod**, de modo que el
  `emptyDir` ya es compartido. Basta con aplicar `container('x')` o
  `container('y')` sobre el mismo `node(POD_LABEL)`.
- Si se requiere un workspace persistente entre builds, se utiliza un
  `persistentVolumeClaimWorkspaceVolume` con `disableConcurrentBuilds()`
  (o un PVC por job). Con `ReadWriteOnce` hay que vigilar la concurrencia
  entre Pods en nodos distintos.

> Equivalencia con el laboratorio Docker: en Docker se compartía
> `/datos/jenkins/pipelines-workspace` en el host mediante *bind mount*. En
> K8s el equivalente "limpio" es `emptyDir` + `stash`, o un PVC si se
> requiere persistencia. El `git clone`/checkout suele sustituir al "código
> prepoblado" del laboratorio Podman.

---

## 9. Cachés de Maven y npm en Kubernetes

Estrategias, de mejor a peor para un clúster:

1. **PVC dedicado a caché** (`persistentVolumeClaim(claimName: 'maven-cache',
   mountPath: '/root/.m2')`): persiste entre builds; ideal para clústeres con
   almacenamiento de bloque/RWX.
   - Con varios agentes concurrentes, `ReadWriteOnce` no sirve (un solo
     nodo). Se usa `ReadWriteMany` (NFS, CephFS) o un PVC **por
     proyecto**.
2. **Caché en el nodo** (`hostPathVolume`): rápida, pero ata el build al
   nodo y no es propiamente "cloud-native".
3. **`emptyDir`**: la caché se pierde en cada build (más lento, pero simple
   y sin corrupción por concurrencia).
4. **Cachés externas**: un **proxy o caché remoto** (Nexus/Artifactory para
   Maven; registro npm o Verdaccio). Es la opción más escalable en K8s y
   evita PVCs.

Ejemplo de plantilla con PVC de caché:

```yaml
volumes:
  - persistentVolumeClaim:
      claimName: maven-cache
      mountPath: /root/.m2
  - persistentVolumeClaim:
      claimName: npm-cache
      mountPath: /root/.npm
```

> `dynamicPVC()` crea un PVC por Pod y lo elimina con él: **no** sirve como
> caché persistente (sería equivalente a `emptyDir`). Para caché
> persistente se utiliza `persistentVolumeClaim(...)` con un PVC **creado
> aparte**.

---

## 10. Selección del agente y del pod

- **`agent { label 'x' }`** o **`node('x')`**: utiliza una plantilla
  **estática** de la Cloud que declare ese label.
- **`podTemplate { ... }` con `node(POD_LABEL)`** (scripted) o
  **`agent { kubernetes { yaml '''...''' } }`** (declarative): definen la
  plantilla **en el pipeline** (recomendado para proyectos nuevos; el pod
  template reside en el SCM junto al código).
- **`container('nombre') { ... }`**: selecciona **dentro del Pod** el
  contenedor en el que se ejecutan los comandos.
- **`defaultContainer 'maven'`**: hace que `sh` se ejecute por defecto en ese
  contenedor (evita envolver todo en `container(...)`).

---

## 11. Construcción de imágenes desde dentro de Kubernetes

En los modelos Podman-Cloud y Podman-Host se monta el socket de Podman del
host y se ejecuta `podman build` desde el agente. En Kubernetes **no hay
socket de Docker**: el contenedor no incluye un motor de contenedores. Las
opciones habituales son:

1. **Kaniko** (recomendado, sin privilegios y sin daemon):
   ```groovy
   container('kaniko') {
     sh '/kaniko/executor --context=. --dockerfile=backend/Dockerfile \
          --destination=registry.local/reference-backend:${BUILD_NUMBER}'
   }
   ```
   (Kaniko está en desuso, pero sigue siendo el patrón clásico; alternativas:
   **Buildah** en modo `vfs`/`chroot`, o **img**/`buildkit` rootless).
2. **Buildah** en un contenedor sin privilegios:
   ```groovy
   container('buildah') {
     sh 'buildah bud --storage-driver=vfs -t registry.local/app:${BUILD_NUMBER} .'
   }
   ```
3. **DinD** (Docker-in-Docker) con un contenedor **privilegiado**: viable,
   pero rompe el aislamiento y no se recomienda en clústeres compartidos.
4. **Construir fuera del agente**: delegar la construcción a un servicio
   (Tekton, un job dedicado o un pipeline con acceso al daemon).

En este modelo el empaquetado ocurre **dentro del Pod**, sin socket del
host.

---

## 12. Secretos

- **Kubernetes Secrets** montados en el Pod:
  - `secretEnvVar(key, secretName, secretKey)` → variable de entorno.
  - `secretVolume(secretName, mountPath)` → fichero montado.
- **Jenkins Credentials Store** (para el pipeline) como siempre.
- Para un `kubeconfig` o un token de registro, se usan `secretVolume` y
  `imagePullSecrets`.

---

## 13. Ejemplo de pipeline (adaptación del `reference-pipeline`)

```groovy
pipeline {
    agent {
        kubernetes {
            yaml '''
                apiVersion: v1
                kind: Pod
                spec:
                  securityContext:
                    runAsUser: 1000
                    runAsGroup: 1000
                  containers:
                    - name: maven
                      image: maven:3.9.9-eclipse-temurin-17
                      command: [sleep]
                      args: [99d]
                    - name: node
                      image: node:20
                      command: [sleep]
                      args: [99d]
                    - name: kaniko
                      image: gcr.io/kaniko-project/executor:v1.23.2-debug
                      command: [sleep]
                      args: [99d]
            '''
        }
    }

    options { timestamps(); disableConcurrentBuilds() }

    stages {
        stage('Backend (Maven)') {
            steps {
                checkout scm
                container('maven') {
                    sh 'cd backend && mvn -B -ntp clean package'
                }
            }
        }
        stage('Frontend (Node)') {
            steps {
                container('node') {
                    sh 'cd frontend && npm ci && npm run build'
                }
            }
        }
        stage('Empaquetar imagenes (Kaniko)') {
            steps {
                container('kaniko') {
                    sh '/kaniko/executor --context=. --dockerfile=backend/Dockerfile \
                         --destination=registry.local/reference-backend:${BUILD_NUMBER} \
                         --insecure --skip-tls-verify'
                }
            }
        }
    }

    post {
        always { archiveArtifacts artifacts: 'backend/target/*.jar', allowEmptyArchive: true }
    }
}
```

Observaciones:

- Un **único Pod** (un `node`) cubre todo el pipeline: un solo agente con
  varios contenedores. Esto difiere del laboratorio Podman (un contenedor
  por stage).
- Si se quisiera **un Pod por stage** en K8s, se definiría
  `agent { kubernetes {} }` en cada `stage` (posible, pero se pierde la
  compartición de `emptyDir` y hay que recurrir a `stash` o PVC).

---

## 14. Alternativa: el Helm chart oficial `jenkins/jenkins`

En la práctica no se escriben los manifiestos a mano: se utiliza el **Helm
chart** oficial `jenkins/jenkins`, que ya incluye StatefulSet, Service, PVC,
RBAC, ServiceAccount y la configuración del plugin Kubernetes. Un
`values.yaml` mínimo:

```yaml
controller:
  serviceType: ClusterIP
  admin:
    username: admin
    password: ""          # mejor: existingSecret / secrets
  installPlugins:
    - kubernetes
    - workflow-aggregator
    - configuration-as-code
  JCasC:
    defaultConfig: true
    configScripts:
      cloud: |
        jenkins:
          clouds:
          - kubernetes:
              name: kubernetes
              serverUrl: https://kubernetes.default.svc
              namespace: jenkins
              jenkinsUrl: http://jenkins.jenkins.svc.cluster.local:8080/
              templates:
              - name: maven
                label: maven
                agentContainer: maven
                agentInjection: true
                containers:
                - name: maven
                  image: maven:3.9.9-eclipse-temurin-17
                  command: sleep
                  args: 99d
persistence:
  enabled: true
  size: 20Gi
```

Para el futuro laboratorio de Kubernetes, el chart oficial constituye el
punto de partida más rápido y realista.

---

## 15. Seguridad y operación

- **RBAC mínimo**: el controller solo necesita gestionar Pods (y PVCs si se
  usa `dynamicPVC`) **en su namespace**. No debe recibir `cluster-admin`.
- **Aislamiento del namespace**: la seguridad del plugin exige que **actores
  no confiables no tengan ni lectura** del namespace de agentes (quien
  pueda leer los Pods obtiene credenciales para conectarse al controller).
  Se utiliza un namespace dedicado y se restringe el acceso.
- **Los Pods-agente no deben usar una ServiceAccount con permisos** sobre el
  namespace (se asigna una SA sin permisos para los agentes, distinta de la
  del controller).
- **Recursos**: se definen `requests` y `limits` en el controller y en los
  contenedores de las plantillas (Maven y Node son exigentes en CPU y RAM).
- **Limpieza**: se activa la **garbage collection** de pods huérfanos del
  plugin y se limita `containerCap`.
- **Coste**: los agentes nacen y mueren por build; se ajustan límites y
  *right-sizing*.

---

## 16. Comparación con los modelos basados en Docker

| Aspecto | Podman-Host | Podman-Cloud | Jenkins-Kubernetes |
|---|---|---|---|
| Controller | VM/contenedor | VM/contenedor | VM/contenedor o Pod del clúster |
| Proveedor | `docker-workflow` (`agent { docker }`) | `docker-plugin` (Cloud + templates) | `kubernetes-plugin` (Cloud + pod templates) |
| Unidad | contenedor **por stage** | contenedor **por build** (nodo) | **Pod por build**, contenedores dentro |
| Imagen | cualquiera | agente Jenkins (JDK + inbound) | agente Jenkins (JDK + inbound) o `agentInjection` |
| Workspace | host del agente + `reuseNode` | bind-mount del host o volumen | `workspaceVolume` (emptyDir/PVC) |
| Compartir entre stages | mismo host (`reuseNode`) | mismo volumen en plantillas | mismo Pod (`emptyDir`) o `stash` |
| Cachés | `-v` en el pipeline | volúmenes de la plantilla | PVC / caché remota |
| Selección | `agent { docker { image } }` | label de la plantilla | label/podTemplate + `container()` |
| Build de imágenes | socket Podman | socket Podman | Kaniko/Buildah/registro (sin socket) |
| Escalado | manual (nodos) | `containerCap` | horizontal del clúster |

---

## 17. Checklist para el laboratorio Kubernetes

1. Levantar un clúster (kind/minikube/k3s) — para un lab local, **kind** o
   **k3d** son las opciones más cómodas (Kubernetes-in-Docker).
2. Crear el namespace `jenkins` y el PVC.
3. Desplegar el controller (Helm chart `jenkins/jenkins` o los manifiestos
   de la sección 5).
4. Conceder el RBAC (SA + Role + RoleBinding).
5. Definir la **Cloud `kubernetes`** (JCasC) y los **pod templates**.
6. Verificar que Jenkins crea Pods-agente en un build de prueba y que se
   eliminan al terminar.
7. Añadir las **herramientas** (contenedores Maven/Node/Kaniko) y el paso
   `container(...)`.
8. Definir la estrategia de **workspace** (`emptyDir` + `stash`) y de
   **cachés** (PVC RWX o caché remota).
9. Definir los **secretos** (registro, kubeconfig) como Kubernetes Secrets.
10. Endurecer: RBAC mínimo, límites de recursos, GC de pods, `containerCap`.

---

## 18. Resolución de problemas

- **El Pod arranca pero no conecta.** Se revisa `Jenkins URL`/WebSocket y
  que el `Service` sea alcanzable desde los Pods
  (`jenkins.jenkins.svc.cluster.local`).
- **`sh` se cuelga con varios contenedores:**
  `permission denied ... jenkins-log.txt` indica **UIDs distintos** entre
  contenedores. Se fija `securityContext.runAsUser` y `runAsGroup` iguales
  (p. ej. 1000) en todos los contenedores del Pod.
- **Pod `Pending`**: sin recursos suficientes o sin PVC disponible; se
  ejecuta `kubectl describe pod`.
- **El workspace se pierde entre builds**: es `emptyDir` por diseño; se
  utiliza `persistentVolumeClaimWorkspaceVolume` o `archiveArtifacts`.
- **Caché corrupta con concurrencia**: PVC `ReadWriteOnce` compartido por
  varios Pods en nodos distintos; se usa RWX, un PVC por job, o caché
  remota.
- **Permisos del PVC del controller**: `fsGroup: 1000` en el Pod.
- **Agentes zombies**: se activa la garbage collection del plugin.

---

## 19. Referencias

- Plugin Kubernetes: https://plugins.jenkins.io/kubernetes ·
  https://github.com/jenkinsci/kubernetes-plugin
- Ejemplos del repositorio del plugin: `examples/`
- Chart oficial: https://github.com/jenkinsci/helm-charts (chart `jenkins`)
- Inbound agent: https://github.com/jenkinsci/docker-agent
- Documentos hermanos de esta serie:
  `Ephemeral-Jenkins-Agents-Podman-host.md`,
  `Ephemeral-Jenkins-Agents-Podman-Cloud.md`,
  `Ephemeral-jenkins-Agents-Architectures-Compartive-guide.md`,
  `Ephemeral-jenkins-agents-Architectures-models-complete-guide.md`.

---

*Licencia: [CC BY 4.0](../../guides/LICENSE). © 2026 Cloudsdoers.*
