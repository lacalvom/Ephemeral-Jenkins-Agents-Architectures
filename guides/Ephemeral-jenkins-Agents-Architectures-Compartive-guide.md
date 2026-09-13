# Arquitecturas de Agentes Efímeros en Jenkins — Los tres modelos

> Documento de referencia que describe y compara las **tres arquitecturas**
> posibles para ejecutar builds de Jenkins en **agentes efímeros**
> (contenedores o Pods que nacen con el build y mueren al terminar).
>
> Guías profundas de cada modelo:
> - **Podman-Host**: `Ephemeral-Jenkins-Agents-Podman-host.md`
> - **Podman-Cloud**: `Ephemeral-Jenkins-Agents-Podman-Cloud.md`
> - **Jenkins-Kubernetes**: `Ephemeral-Jenkins-Agents-Kubernetes.md`
>
> Documento unificado (todo en uno): `Ephemeral-jenkins-agents-Architectures-models-complete-guide.md`.

---

## 1. Concepto de agente efímero

Un **agente efímero** es un entorno de ejecución aislado que se crea **bajo
demanda** para un build y se destruye al terminar. Aporta, frente al agente
permanente clásico:

- **Aislamiento**: cada build parte de un entorno limpio, sin restos del
  build anterior.
- **Reproducibilidad**: la imagen del agente está versionada; el clásico "en
  mi máquina funciona" deja de tener sentido.
- **Escalado**: se crean tantos como se necesiten y se apagan al terminar.
- **Seguridad**: las credenciales y toolchains no persisten en un nodo
  compartido.

El precio es que hay que decidir **dónde** se ejecutan, **cómo** se
aprovisionan, **dónde vive el workspace** y **cómo se cachean** las
dependencias. De eso tratan los tres modelos.

---

## 2. Los tres modelos de un vistazo

| | **Podman-Host** | **Podman-Cloud** | **Jenkins-Kubernetes** |
|---|---|---|---|
| Plugin | `docker-workflow` | `docker-plugin` | `kubernetes-plugin` |
| Sintaxis típica | `agent { docker { image } }` | Cloud + Docker Agent Template + `agent { label }` | Cloud + Pod Template + `agent { kubernetes }` |
| Quién crea el entorno | el código del pipeline | Jenkins (Cloud) | Jenkins (Cloud) |
| Entorno | contenedor | contenedor (nodo-agente) | Pod (nodo-agente) |
| Grano | un contenedor por stage | un contenedor por build | un Pod por build, varios contenedores dentro |
| ¿La imagen necesita agente? | No | Sí (JDK + inbound/sshd) | Sí (o `agentInjection`) |
| Workspace | host del agente + `reuseNode` | bind-mount del host o volumen | `workspaceVolume` (emptyDir/PVC) |
| Compartir entre stages | mismo host (`reuseNode`) | mismo volumen en plantillas | mismo Pod (`emptyDir`) o `stash` |
| Cachés | `-v` en el pipeline | volúmenes de la plantilla | PVC / caché remota |
| Selección | imagen en el pipeline | label de plantilla | label/podTemplate + `container()` |
| Host del entorno | Docker/Podman (VM/contenedor) | Docker/Podman (VM/contenedor) | clúster Kubernetes |
| Controller | VM/contenedor | VM/contenedor | VM/contenedor o Pod del clúster |

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

### 3.2 Funcionamiento interno

1. El nodo (un agente Jenkins, p. ej. el `podman-host`) ejecuta `docker run`
   con los `args` del pipeline, dejando el contenedor "dormido" (`cat`).
2. Cada `sh` del stage se ejecuta con **`docker exec`** dentro del contenedor.
3. Al terminar el stage: `docker stop` y `docker rm`.
4. `reuseNode true` monta el **workspace del host** dentro del contenedor.

`docker` puede ser la CLI real o un **alias a Podman** (`podman-docker`).

### 3.3 Implicaciones

- Sirve **cualquier imagen** (no necesita Java ni agente).
- El **workspace reside en el host** y se inyecta, por lo que los stages
  comparten artefactos.
- Todo el "chrome" (imagen, `-v`, `--userns`, `--security-opt`) reside en el
  **código del pipeline** (GitOps por proyecto).
- Requiere un **nodo-agente que sepa hablar con el motor** (socket, permisos,
  SELinux).

### 3.4 Workspace, cachés y selección

- **Workspace**: `<RemoteFs del nodo>/workspace/<job>`, montado con `reuseNode`.
- **Cachés**: volúmenes en los `args` (`-v maven-cache-${EXECUTOR_NUMBER}:/cache/.m2`),
  típicamente uno por executor para evitar colisiones.
- **Selección**: la propia directiva `agent { docker { image } }` (una imagen
  por stage).

### 3.5 Pros / contras

- **Pros**: imágenes simples; todo en el pipeline; un contenedor por stage
  (muy efímero); depuración directa en el log del stage.
- **Contras**: el pipeline conoce Docker (mayor acoplamiento); un único nodo
  anfitrión; menor aislamiento entre proyectos.
- **Cuándo**: un único host Podman con varios proyectos y toolchains
  cambiantes. Es el modelo del laboratorio Podman-Host.

---

## 4. Podman-Cloud

### 4.1 Concepto

El plugin **`docker-plugin`** define una **Cloud** que apunta a la API de un
Docker/Podman host y una o más **Docker Agent Templates**. Jenkins aprovisiona
contenedores **como nodos-agente** bajo demanda, en función de **labels**.

```groovy
stage('Backend') {
    agent { label 'maven-jdk17' }
    steps { sh 'cd backend && mvn -B clean package' }
}
```

### 4.2 Funcionamiento interno

1. Un build solicita el label `maven-jdk17`; si no hay agente, la Cloud
   ejecuta `POST /containers/create` + `/start` (vía **docker-java**, no la CLI).
2. Inyecta el *launch method* (JNLP/SSH/attached) con nombre, secret y URL
   del controller; el contenedor **conecta de vuelta** y se registra como nodo.
3. El build se ejecuta **en** ese nodo-agente.
4. Al quedar inactivo, el plugin detiene y **elimina** el contenedor.

### 4.3 Implicaciones

- **La imagen debe ser un agente Jenkins** (JDK + `jenkins/inbound-agent` o
  sshd + JDK). Se construyen imágenes **híbridas** (toolchain + agente).
- Hay que **exponer la API de Podman** de forma segura. La recomendación es
  **TCP + mTLS** (puerto 2376, ver ADR-0005); el túnel SSH es una alternativa.
- El workspace y las cachés se configuran en la **plantilla**, no en el pipeline.
- La configuración declarativa (JCasC) puede presentar incompatibilidades
  con Jenkins LTS 2.568.3; el laboratorio opta por scripts `init.groovy.d/`.

### 4.4 Workspace, cachés y selección

- **Workspace**: dentro del contenedor (`<remoteFs>/workspace/<job>`); para
  compartirlo entre stages, se **monta un directorio del host** en cada
  plantilla y se apunta `remoteFs` a esa ruta (equivalente a `reuseNode`).
- **Cachés**: *named volumes* o bind-mounts declarados en la plantilla
  (`type=volume,source=maven-cache,destination=/cache/.m2`).
- **Selección**: `labelString` de cada plantilla; el pipeline usa
  `agent { label '...' }`.

### 4.5 Pros / contras

- **Pros**: modelo "nodo" (labels, retención, `containerCap`); aislamiento por
  proyecto; gestión centralizada; pipeline limpio.
- **Contras**: mantener imágenes-agente; exponer la API de forma segura;
  configuración verbosa; depuración indirecta.
- **Cuándo**: muchos equipos o proyectos, necesidad de labels y cuotas, y se
  asume el coste de mantener imágenes-agente.

---

## 5. Jenkins-Kubernetes

### 5.1 Concepto

El plugin **`kubernetes-plugin`** trata el clúster como una Cloud. Por cada
build crea un **Pod** (agente inbound) con **varios contenedores**: uno que
ejecuta el agente (`jnlp`) y varios de herramientas (Maven, Node, Buildah…).
El pipeline selecciona el contenedor con **`container('nombre')`**.

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

### 5.2 Funcionamiento interno

1. El pipeline solicita un agente (label, `POD_LABEL` o `agent { kubernetes }`).
2. La Cloud crea un **Pod** con los contenedores de la plantilla e inyecta
   `JENKINS_URL`, `JENKINS_SECRET` y `JENKINS_AGENT_NAME` en el contenedor
   del agente.
3. El inbound-agent conecta con el controller (HTTP/WebSocket o puerto 50000).
4. `sh` se ejecuta en el contenedor del agente; `container('x')` lanza el
   comando vía la **API exec** de K8s en otro contenedor del mismo Pod.
5. El **volumen de workspace** está montado en todos los contenedores.
6. Al terminar, el Pod se elimina (según `podRetention`/`idleMinutes`).

### 5.3 Implicaciones

- **No hay socket de Docker**: para empaquetar imágenes se recurre a
  **Kaniko**, **Buildah** (sin daemon) o un registro externo. No se usa
  `podman build` con socket del host.
- **Controller dentro o fuera** del clúster. Fuera del clúster se usa
  normalmente WebSocket; dentro se suele recurrir al **chart oficial
  `jenkins/jenkins`** (StatefulSet + PVC + RBAC + ServiceAccount).
- **UIDs coherentes**: se fija `securityContext.runAsUser`/`runAsGroup` en
  todos los contenedores del Pod; de lo contrario, el paso `sh` falla por
  permisos.

### 5.4 Workspace, cachés y selección

- **Workspace**: `workspaceVolume`:
  - `emptyDirWorkspaceVolume` (por defecto, efímero, **compartido entre
    contenedores del Pod**),
  - `dynamicPVC()` (PVC por Pod, se elimina con él),
  - `persistentVolumeClaimWorkspaceVolume(...)` (persistente; con cuidado en
    concurrencia),
  - `hostPathWorkspaceVolume(...)` (solo para clústeres de un nodo o pruebas).
  Para mover artefactos entre stages o plantillas: `stash`/`unstash` o
  `archiveArtifacts`.
- **Cachés**: PVC (`persistentVolumeClaim(claimName: ...)`) o **caché
  remota** (Nexus, Artifactory, Verdaccio). `dynamicPVC()` no sirve para
  caché persistente.
- **Selección**: label/podTemplate + `container(...)` (y `defaultContainer`).

### 5.5 Pros / contras

- **Pros**: escalado horizontal real; aislamiento y cuotas por namespace; el
  Pod template reside en el SCM; ecosistema Kubernetes (secretos, PVC,
  políticas).
- **Contras**: requiere un clúster; los agentes necesitan JDK e inbound; sin
  socket de Docker hay que cambiar la forma de construir imágenes; más piezas
  (inbound, exec, PVC).
- **Cuándo**: ya hay Kubernetes (o se quiere); muchos proyectos; necesidad de
  escalar y aislar.

---

## 6. Comparativa ampliada

| Criterio | Podman-Host | Podman-Cloud | Jenkins-Kubernetes |
|---|---|---|---|
| Imagen del agente | cualquiera | JDK + inbound/sshd | JDK + inbound (o `agentInjection`) |
| Grano | contenedor por stage | contenedor por build | Pod por build (varios contenedores) |
| Workspace | host + `reuseNode` | bind-mount del host | `workspaceVolume` |
| Compartir artefactos | automático (mismo host) | mismo volumen en plantillas | mismo Pod / `stash` |
| Cachés | `-v` en el pipeline | volúmenes de plantilla | PVC / caché remota |
| Selección | imagen en el pipeline | label de plantilla | label/podTemplate + `container()` |
| Build de imágenes | socket Podman | socket Podman | Kaniko / Buildah / registry |
| Escalado | manual | `containerCap` en un host | horizontal del clúster |
| Controller | VM/contenedor | VM/contenedor | VM/contenedor o Pod del clúster |
| Conocimiento Docker en el pipeline | sí | no | no |
| Configuración en infraestructura | poca | mucha (plantillas) | mucha (RBAC, PVC, plantillas) |
| Mejor para | un host con toolchains cambiantes | muchos equipos, labels, cuotas | Kubernetes, escala, aislamiento |

---

## 7. Árbol de decisión

1. ¿Ya existe (o se quiere) un clúster Kubernetes? → **Jenkins-Kubernetes**.
2. ¿Hay un único host Podman con varios proyectos y toolchains distintas? →
   **Podman-Host**.
3. ¿Hay un host Docker/Podman pero se necesitan labels, cuotas y aislamiento
   por proyecto, y se puede mantener imágenes-agente? → **Podman-Cloud**.
4. ¿Se busca el mínimo esfuerzo de infraestructura y el máximo "todo en el
   pipeline"? → **Podman-Host**.
5. ¿Es necesario escalar horizontalmente y aislar por namespace? →
   **Jenkins-Kubernetes**.

Los modelos pueden **combinarse** en un mismo Jenkins (por ejemplo, agentes
"completos" en Kubernetes y agentes ligeros con `agent { docker {} }`).

---

## 8. Aspectos comunes a los tres modelos

- **Controller** independiente de dónde corran los agentes.
- **Comunicación agente → controller** (inbound JNLP/WebSocket, o `docker
  exec` desde el nodo en Podman-Host).
- **Workspace**: decidir si es efímero (limpio por build) o persistente, y
  cómo se comparten artefactos (`reuseNode`, volumen, `stash`).
- **Cachés**: sacarlas del workspace (volúmenes, PVC o caché remota) para no
  contaminarlo y acelerar builds.
- **Selección de agente**: por imagen (Workflow) o por **label** (Cloud /
  Kubernetes).
- **Construcción de imágenes**: en Docker/Podman, `docker/podman build` con
  socket; en Kubernetes, Kaniko/Buildah/registro.

---

## 9. Referencias

- `Ephemeral-Jenkins-Agents-Podman-host.md` (Podman-Host)
- `Ephemeral-Jenkins-Agents-Podman-Cloud.md` (Podman-Cloud)
- `Ephemeral-Jenkins-Agents-Kubernetes.md` (Jenkins-Kubernetes)
- `Ephemeral-jenkins-agents-Architectures-models-complete-guide.md` (documento unificado)
- Plugins: `docker-workflow`, `docker-plugin`, `kubernetes-plugin`

---

*Licencia: [CC BY 4.0](./LICENSE). © 2026 Cloudsdoers.*
