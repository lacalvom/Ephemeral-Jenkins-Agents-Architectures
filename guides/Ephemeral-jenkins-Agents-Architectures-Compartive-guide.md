# Arquitecturas de Agentes Efímeros en Jenkins — Los tres modelos

> Documento de referencia que describe y compara los **tres modelos** posibles
> para ejecutar builds de Jenkins en **agentes efímeros** (contenedores o Pods
> que nacen para un build y mueren al terminar).
>
> Guías profundas de cada modelo:
> - **Podman-Host**: `Ephemeral-Jenkins-Agents-Podman-host.md`
> - **Podman-Cloud**: `Ephemeral-Jenkins-Agents-Podman-Cloud.md`
> - **Jenkins-Kubernetes**: `Ephemeral-Jenkins-Agents-Kubernetes.md`
>
> Documento unificado (todo en uno): `Ephemeral-jenkins-agents-Architectures-models-complete-guide.md`.

---

## 1. Qué es un agente efímero y por qué

Un **agente efímero** es un entorno de ejecución aislado que se crea **bajo
demanda** para un build y se destruye al terminar. Ventajas frente al agente
permanente clásico:

- **Aislamiento**: cada build parte de un entorno limpio; sin "restos" del
  build anterior.
- **Reproducibilidad**: la imagen del agente está versionada; "funciona en mi
  máquina" desaparece.
- **Escalado**: se crean tantos como haga falta y se apagan al terminar.
- **Seguridad**: credenciales y toolchains no persisten en un nodo compartido.

El precio: hay que decidir **dónde** se ejecutan, **cómo** se aprovisionan,
**dónde vive el workspace** y **cómo se cachean** las dependencias. De eso
tratan los tres modelos.

---

## 2. Los tres modelos de un vistazo

| | **Podman-Host** | **Podman-Cloud** | **Jenkins-Kubernetes** |
|---|---|---|---|
| Plugin | `docker-workflow` | `docker-plugin` | `kubernetes-plugin` |
| Sintaxis típica | `agent { docker { image } }` | Cloud + Docker Agent Template + `agent { label }` | Cloud + Pod Template + `agent { kubernetes }` |
| Quién crea el entorno | el código del pipeline | Jenkins (Cloud) | Jenkins (Cloud) |
| Entorno | contenedor | contenedor (nodo-agente) | Pod (nodo-agente) |
| Grano | **1 contenedor por stage** | **1 contenedor por build** | **1 Pod por build**, varios contenedores dentro |
| ¿Imagen necesita agente? | **No** | **Sí** (JDK + inbound/sshd) | **Sí** (o `agentInjection`) |
| Workspace | host del agente + `reuseNode` | bind-mount del host o volumen | `workspaceVolume` (emptyDir/PVC) |
| Compartir entre stages | mismo host (`reuseNode`) | mismo volumen en plantillas | mismo Pod (`emptyDir`) o `stash` |
| Cachés | `-v` en el pipeline | volúmenes de la plantilla | PVC / caché remota |
| Selección | imagen en el pipeline | label de plantilla | label/podTemplate + `container()` |
| Host del entorno | Docker/Podman (VM/contenedor) | Docker/Podman (VM/contenedor) | clúster Kubernetes |
| Controller | VM/contenedor | VM/contenedor | VM/contenedor **o Pod del clúster** |

---

## 3. Podman-Host

### 3.1 Concepto

El plugin **`docker-workflow`** (parte de Pipeline) permite declarar en el
propio código del pipeline un contenedor por stage:

```groovy
stage('Backend') {
    agent {
        docker {
            image 'registry.access.redhat.com/ubi9/openjdk-17:latest'
            reuseNode true
            args '--userns=keep-id -v maven-cache-${EXECUTOR_NUMBER}:/cache/.m2'
        }
    }
    steps { sh 'cd backend && mvn -B clean package' }
}
```

### 3.2 Cómo funciona por dentro

1. El nodo (un agente Jenkins, p. ej. el `podman-host`) ejecuta `docker run` con
   los `args` del pipeline, dejando el contenedor "dormido" (`cat`).
2. Cada `sh` del stage se ejecuta con **`docker exec`** dentro del contenedor.
3. Al terminar el stage: `docker stop` + `docker rm`.
4. `reuseNode true` monta el **workspace del host** dentro del contenedor.

`docker` puede ser la CLI real o un **alias a Podman** (`podman-docker`).

### 3.3 Implicaciones

- Sirve **cualquier imagen** (no necesita Java ni agente).
- El **workspace vive en el host** y se inyecta; por eso los stages comparten
  artefactos.
- Todo el "chrome" (imagen, `-v`, `--userns`, `--security-opt`) está **en el
  pipeline** (GitOps, por proyecto).
- Requiere un **nodo-agente que sepa hablar con el motor** (socket, permisos,
  SELinux).

### 3.4 Workspace, cachés y selección

- **Workspace**: `<RemoteFs del nodo>/workspace/<job>`, montado con `reuseNode`.
- **Cachés**: volúmenes en los `args` (`-v maven-cache-${EXECUTOR_NUMBER}:/cache/.m2`). Típicamente
  uno por executor para evitar colisiones.
- **Selección**: la propia directiva `agent { docker { image } }` (una imagen por
  stage).

### 3.5 Pros / contras

- **Pros**: imágenes simples; todo en el pipeline; un contenedor por stage
  (muy efímero); fácil de depurar en el log del stage.
- **Contras**: el pipeline conoce Docker (acoplado); un solo nodo anfitrión;
  menos aislamiento entre proyectos.
- **Cuándo**: un único host Podman con varios proyectos y toolchains
  cambiantes. **Es el modelo del laboratorio Podman-Host.**

---

## 4. Podman-Cloud

### 4.1 Concepto

El plugin **`docker-plugin`** define una **Cloud** que apunta a la API de un
Docker/Podman host y una o más **Docker Agent Templates**. Jenkins aprovisiona
contenedores **como nodos-agente** bajo demanda, según **labels**.

```groovy
stage('Backend') {
    agent { label 'maven-jdk17' }
    steps { sh 'cd backend && mvn -B clean package' }
}
```

### 4.2 Cómo funciona por dentro

1. Un build pide el label `maven-jdk17`; si no hay agente, la Cloud hace
   `POST /containers/create` + `/start` (vía **docker-java**, no la CLI).
2. Inyecta el *launch method* (JNLP/SSH/attached) con nombre, secret y URL del
   controller; el contenedor **conecta de vuelta** y se registra como nodo.
3. El build corre **en** ese nodo-agente.
4. Al quedar inactivo, el plugin para y **borra** el contenedor.

### 4.3 Implicaciones

- **La imagen debe ser un agente Jenkins** (JDK + `jenkins/inbound-agent` o
  sshd+JDK). Hay que construir imágenes **híbridas** (toolchain + agente).
- Hay que **exponer la API de Podman** (TCP+mTLS o túnel SSH; TCP sin TLS solo
  en red aislada).
- El workspace y las cachés se configuran en la **plantilla**, no en el
  pipeline.

### 4.4 Workspace, cachés y selección

- **Workspace**: dentro del contenedor (`<remoteFs>/workspace/<job>`); para
  compartirlo, se **monta un directorio del host** en cada plantilla y se apunta
  `remoteFs` ahí (equivalente a `reuseNode`).
- **Cachés**: *named volumes* o bind-mounts en la plantilla (`maven-cache:/cache/.m2`).
- **Selección**: `labelString` de cada plantilla; el pipeline usa
  `agent { label '...' }`.

### 4.5 Pros / contras

- **Pros**: modelo "nodo" (labels, retención, `containerCap`); aislamiento por
  proyecto; gestión centralizada; pipeline limpio.
- **Contras**: mantener imágenes-agente; exponer la API de forma segura;
  configuración verbosa (JCasC); depuración indirecta.
- **Cuándo**: muchos equipos/proyectos, necesidad de labels y cuotas, y se
  asume el coste de las imágenes-agente.

---

## 5. Jenkins-Kubernetes

### 5.1 Concepto

El plugin **`kubernetes-plugin`** trata el clúster como una Cloud. Por cada
build crea un **Pod** (agente inbound) con **varios contenedores**: uno que
ejecuta el agente (`jnlp`) y varios de herramientas (Maven, Node, Buildah…).
El pipeline elige el contenedor con **`container('nombre')`**.

```groovy
agent {
    kubernetes {
        yaml '''
        spec:
          containers:
          - name: maven
            image: maven:3.9.9-eclipse-temurin-17
            command: [sleep]
            args: [99d]
        '''
    }
}
// ...
container('maven') { sh 'mvn -B clean package' }
```

### 5.2 Cómo funciona por dentro

1. El pipeline pide un agente (label, `POD_LABEL` o `agent { kubernetes }`).
2. La Cloud crea un **Pod** con los contenedores de la plantilla; inyecta
   `JENKINS_URL`/`JENKINS_SECRET`/`JENKINS_AGENT_NAME` en el contenedor del agente.
3. El inbound-agent conecta al controller (HTTP/WebSocket o puerto 50000).
4. `sh` corre en el contenedor del agente; `container('x')` ejecuta vía la **API
   exec** de K8s en otro contenedor del mismo Pod.
5. El **volumen de workspace** está montado en todos los contenedores.
6. Al terminar, el Pod se borra (según `podRetention`/`idleMinutes`).

### 5.3 Implicaciones

- **No hay socket de Docker**: para empaquetar imágenes se usa **Kaniko**,
  **Buildah** (sin daemon), o un servicio externo. Nada de `podman build` con
  socket del host.
- **Controller dentro o fuera** del clúster. Fuera ⇒ normalmente **WebSocket**.
  Dentro ⇒ lo natural es el **chart oficial `jenkins/jenkins`** (StatefulSet +
  PVC + RBAC + SA).
- **UIDs consistentes**: fija `securityContext.runAsUser/runAsGroup` en todos
  los contenedores del Pod, o el paso `sh` falla por permisos.

### 5.4 Workspace, cachés y selección

- **Workspace**: `workspaceVolume`:
  - `emptyDirWorkspaceVolume` (def., efímero, **compartido entre contenedores
    del Pod**),
  - `dynamicPVC()` (PVC por Pod, se borra con él),
  - `persistentVolumeClaimWorkspaceVolume(...)` (persiste; ojo con concurrencia),
  - `hostPathWorkspaceVolume(...)` (solo clústeres de 1 nodo/pruebas).
  Para pasar artefactos entre stages/plantillas: `stash`/`unstash` o
  `archiveArtifacts`.
- **Cachés**: PVC (`persistentVolumeClaim(claimName: ...)`) o **caché remota**
  (Nexus/Artifactory/Verdaccio). `dynamicPVC()` no vale para caché persistente.
- **Selección**: label/podTemplate + `container(...)` (y `defaultContainer`).

### 5.5 Pros / contras

- **Pros**: escalado horizontal real; aislamiento y cuotas por namespace; el Pod
  template vive en el SCM; ecosistema K8s (secretos, PVC, política).
- **Contras**: requiere un clúster; los agentes necesitan JDK+inbound; sin
  socket de Docker hay que cambiar la forma de construir imágenes; más piezas
  (inbound + exec + PVC).
- **Cuándo**: ya hay (o se quiere) Kubernetes; muchos proyectos; necesidad de
  escalar y aislar.

---

## 6. Comparativa ampliada

| Criterio | Podman-Host | Podman-Cloud | Jenkins-Kubernetes |
|---|---|---|---|
| **Imagen del agente** | Cualquiera | JDK + inbound/sshd | JDK + inbound (o `agentInjection`) |
| **Grano** | contenedor/stage | contenedor/build | Pod/build (varios contenedores) |
| **Workspace** | host + `reuseNode` | bind-mount del host | `workspaceVolume` |
| **Compartir artefactos** | automático (mismo host) | mismo volumen en plantillas | mismo Pod / `stash` |
| **Cachés** | `-v` en pipeline | volúmenes de plantilla | PVC / caché remota |
| **Selección** | imagen en pipeline | label de plantilla | label/podTemplate + `container()` |
| **Build de imágenes** | socket Podman | socket Podman | Kaniko/Buildah/registry |
| **Escalado** | manual | `containerCap` en un host | horizontal del clúster |
| **Controller** | VM/contenedor | VM/contenedor | VM/contenedor o Pod |
| **Conocimiento Docker en el pipeline** | sí | no | no |
| **Configuración en infra** | poca | mucha (plantillas) | mucha (RBAC, PVC, plantillas) |
| **Mejor para** | 1 host, toolchains cambiantes | muchos equipos, labels | K8s, escala, aislamiento |

---

## 7. Árbol de decisión

1. ¿Ya tienes (o quieres) un clúster Kubernetes? → **Jenkins-Kubernetes**.
2. ¿Un solo host Podman y varios proyectos con toolchains distintas? →
   **Podman-Host**.
3. ¿Un host Docker/Podman pero necesitas labels, cuotas y aislamiento por
   proyecto, y puedes mantener imágenes-agente? → **Podman-Cloud**.
4. ¿Quieres el mínimo esfuerzo de infraestructura y el máximo "todo en el
   pipeline"? → **Podman-Host**.
5. ¿Necesitas escalar horizontalmente y aislar por namespace? →
   **Jenkins-Kubernetes**.

Se pueden **combinar** modelos en un mismo Jenkins (p. ej. agentes "ricos" en
Kubernetes y agentes ligeros con `agent { docker {} }`).

---

## 8. Lo común a los tres modelos

- **Controller** independiente de dónde corran los agentes.
- **Comunicación agente → controller** (inbound JNLP/WebSocket, o `docker exec`
  desde el nodo en Podman-Host).
- **Workspace**: decidir si es efímero (limpio por build) o persistente, y cómo
  se comparten artefactos (`reuseNode`, volumen, `stash`).
- **Cachés**: moverlas fuera del workspace (volúmenes/PVC/caché remota) para no
  ensuciarlo y acelerar.
- **Selección de agente**: por imagen (Workflow) o por **label**
  (Cloud/Kubernetes).
- **Construcción de imágenes**: en Docker es `docker/podman build` con socket;
  en K8s es Kaniko/Buildah/registry.

---

## 9. Referencias

- `Ephemeral-Jenkins-Agents-Podman-host.md` (Podman-Host)
- `Ephemeral-Jenkins-Agents-Podman-Cloud.md` (Podman-Cloud)
- `Ephemeral-Jenkins-Agents-Kubernetes.md` (Jenkins-Kubernetes)
- `Ephemeral-jenkins-agents-Architectures-models-complete-guide.md` (documento unificado)
- Plugins: `docker-workflow`, `docker-plugin`, `kubernetes`

---

*Licencia: [CC BY 4.0](./LICENSE). © 2026 Cloudsdoers.*
