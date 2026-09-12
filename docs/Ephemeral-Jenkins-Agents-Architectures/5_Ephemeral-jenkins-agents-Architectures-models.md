# Arquitecturas de Agentes Efímeros en Jenkins — Documento unificado

> **Documento único y autocontenido.** Reúne, en este orden:
> (1) las explicaciones de los modelos existentes, y (2) el detalle de cada uno.
>
> Es la versión "todo en uno" de la serie:
> - `2_Ephemeral-Jenkins-Agents-Podman-host.md` — guía del modelo **Podman-Host** (laboratorio Podman).
> - `3_Ephemeral-Jenkins-Agents-Podman-Cloud.md` — guía del modelo **Podman-Cloud**.
> - `4_Ephemeral-Jenkins-Agents-Kubernetes.md` — guía del modelo **Jenkins-Kubernetes**.
> - `1_Ephemeral-jenkins-Agents-Architectures-Compartive.md` — comparativa de los tres modelos.

---

# PARTE I — Explicación de los modelos

## 1. Qué es un agente efímero y por qué

Un **agente efímero** es un entorno de ejecución aislado que se crea **bajo
demanda** para un build y se destruye al terminar.

Beneficios:

- **Aislamiento**: cada build empieza limpio; sin restos del anterior.
- **Reproducibilidad**: la imagen está versionada; el clásico "en mi máquina
  funciona" desaparece.
- **Escalado**: se crean los que hagan falta y se apagan al terminar.
- **Seguridad**: credenciales y toolchains no persisten en un nodo compartido.

El precio a pagar es decidir cuatro cosas:

1. **Dónde** corren los agentes (host Docker/Podman o clúster Kubernetes).
2. **Cómo** se aprovisionan (desde el pipeline o desde una "Cloud").
3. **Dónde vive el workspace** y cómo se comparte entre stages/builds.
4. **Cómo se cachean** Maven/npm para no re-descargar el mundo en cada build.

De esas cuatro respuestas surgen los tres modelos.

## 2. Los tres modelos existentes

| | **Podman-Host** | **Podman-Cloud** | **Jenkins-Kubernetes** |
|---|---|---|---|
| Plugin | `docker-workflow` | `docker-plugin` | `kubernetes-plugin` |
| Sintaxis | `agent { docker { image } }` | Cloud + Docker Agent Template + `agent { label }` | Cloud + Pod Template + `agent { kubernetes }` |
| Quién crea el entorno | el `Jenkinsfile` | Jenkins (Cloud) | Jenkins (Cloud) |
| Entorno | contenedor | contenedor (nodo-agente) | Pod (nodo-agente) |
| Grano | 1 contenedor **por stage** | 1 contenedor **por build** | 1 Pod **por build**, varios contenedores |
| ¿Imagen necesita agente? | **No** | **Sí** (JDK + inbound/sshd) | **Sí** (o `agentInjection`) |
| Workspace | host + `reuseNode` | bind-mount del host | `workspaceVolume` (emptyDir/PVC) |
| Cachés | `-v` en pipeline | volúmenes de plantilla | PVC / caché remota |
| Selección | imagen en pipeline | label de plantilla | label/podTemplate + `container()` |
| Controller | VM/contenedor | VM/contenedor | VM/contenedor **o Pod** |

Nombres que usaremos:

- **Podman-Host**: el modelo de este laboratorio Podman.
- **Podman-Cloud**: la "definición Cloud" con `docker-plugin`.
- **Jenkins-Kubernetes**: todo sobre Kubernetes con `kubernetes-plugin`.

## 3. Fundamentos comunes a los tres

- **El controller es independiente** de dónde corran los agentes. Puede ser una
  VM o un contenedor (y, en Kubernetes, un Pod).
- **Comunicación agente → controller**: "inbound" (JNLP/WebSocket) en
  Cloud/Kubernetes; en Podman-Host no hay agente, el nodo hace
  `docker exec`.
- **Workspace**: decidir efímero (limpio por build) o persistente, y cómo se
  comparten artefactos (`reuseNode`, volumen compartido, `stash`).
- **Cachés**: sacarlas del workspace (volúmenes/PVC/caché remota) para acelerar
  y no ensuciarlo.
- **Selección**: por **imagen** (Workflow) o por **label** (Cloud/Kubernetes).
- **Construcción de imágenes**: en Docker se hace `docker/podman build` con el
  socket del motor; en Kubernetes no hay socket y se usa Kaniko/Buildah/registry.

---

# PARTE II — Explicación de cada modelo

## 4. Modelo A — Podman-Host

### 4.1 Concepto

El plugin **`docker-workflow`** permite declarar, en el propio `Jenkinsfile`,
un contenedor por stage. Es el modelo del laboratorio Podman.

```groovy
stage('Backend') {
    agent {
        docker {
            image 'registry.access.redhat.com/ubi9/openjdk-17:latest'
            reuseNode true
            args '--userns=keep-id -v maven-cache:/cache/.m2:z'
        }
    }
    steps { sh 'cd backend && mvn -B clean package' }
}
```

### 4.2 Cómo funciona por dentro

1. Un **agente Jenkins residente** (el `podman-host`) ejecuta `docker run` con
   los `args` del pipeline, dejando el contenedor "dormido" (`cat`).
2. Cada `sh` del stage se ejecuta con **`docker exec`** dentro del contenedor.
3. Al terminar el stage: `docker stop` + `docker rm`.
4. `reuseNode true` monta el **workspace del host** dentro del contenedor.

`docker` puede ser la CLI real o un **alias a Podman** (`podman-docker`).

### 4.3 Infraestructura necesaria

- Un host con Docker o Podman y un **socket** accesible por el usuario del
  agente. En Podman rootless: `podman.socket` del usuario (ver ADR-010).
- El agente Jenkins debe poder ejecutar `docker`/`podman` (PATH, permisos).
- Si el contenedor empaqueta imágenes: montar el socket en el contenedor del
  stage con `--security-opt label=disable` (SELinux, ver ADR-011).

### 4.4 Workspace

- Vive en el **host** del agente: `<RemoteFs>/workspace/<job>`.
- Se inyecta en cada contenedor con `-v` (lo hace `reuseNode true`).
- **Todos los stages comparten** ese workspace ⇒ los artefactos
  (`backend/target`, `frontend/dist`) fluyen de un stage a otro sin `stash`.

### 4.5 Cachés

- Se pasan en los `args` del pipeline: `-v maven-cache:/cache/.m2:z`.
- Lo típico es un volumen **por ejecutor** (`maven-cache-${EXECUTOR_NUMBER}`)
  para evitar colisiones con builds concurrentes.
- Rutas fijas dentro del contenedor (`/cache/.m2`), sin depender de `$HOME`.

### 4.6 Selección del entorno

- No hay labels: el **pipeline elige la imagen** de cada stage.

### 4.7 Pipeline típico (laboratorio)

```groovy
pipeline {
    agent { label 'podman-node' }
    stages {
        stage('Construccion Backend') {
            agent { docker { image '.../ubi9/openjdk-17:latest'; reuseNode true
                             args '--userns=keep-id -v maven-cache-${EXECUTOR_NUMBER}:/cache/.m2:z' } }
            steps { sh 'cd backend && mvn -B -ntp clean package' }
        }
        stage('Empaquetar Imagen') {
            agent { docker { image '.../ubi9/podman:latest'; reuseNode true
                             args '--userns=keep-id --security-opt label=disable -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock' } }
            steps { sh 'podman build --format docker -t app:${BUILD_NUMBER} -f backend/Dockerfile .' }
        }
    }
}
```

### 4.8 Pros / contras / cuándo

- **Pros**: cualquier imagen; todo en el pipeline; contenedor por stage; fácil de
  depurar.
- **Contras**: el pipeline conoce Docker; un solo nodo anfitrión; menos
  aislamiento entre proyectos.
- **Cuándo**: un host Podman/Docker, varios proyectos, toolchains cambiantes.

---

## 5. Modelo B — Podman-Cloud

### 5.1 Concepto

El plugin **`docker-plugin`** define una **Cloud** apuntando a la API de un
Docker/Podman host, más **Docker Agent Templates**. Jenkins aprovisiona
contenedores **como nodos-agente** bajo demanda, según **labels**.

```groovy
stage('Backend') {
    agent { label 'maven-jdk17' }
    steps { sh 'mvn -B clean package' }
}
```

### 5.2 Cómo funciona por dentro

1. Un build pide el label `maven-jdk17`; si no hay agente, la Cloud hace
   `POST /containers/create` + `/start` vía **docker-java** (no la CLI).
2. Inyecta el *launch method* (JNLP/SSH/attached) con nombre, secret y URL del
   controller; el contenedor **conecta de vuelta** y se registra como nodo.
3. El build corre **en** ese nodo-agente.
4. Al quedar inactivo, el plugin para y **borra** el contenedor
   (`idle timeout`, `podRetention`/`containerCap`).

### 5.3 Infraestructura necesaria

- **Exponer la API de Podman**:
  - Túnel **SSH** (recomendado por Podman para remoto), o
  - **TCP+mTLS** (certificados), o
  - **TCP sin TLS** solo en red aislada (laboratorio).
  - `unix://` solo si Jenkins y Podman están en la misma máquina.
- **Imágenes-agente híbridas** (toolchain + `jenkins/inbound-agent`).

> ⚠️ La API de Podman da **control total** (ejecución arbitraria como el usuario
> que la corre). No la expongas por red sin mTLS.

### 5.4 Workspace

- Vive **dentro del contenedor**: `<remoteFs>/workspace/<job>`.
- Como el contenedor es efímero, para persistir/compartir se **monta un
  directorio del host** en cada plantilla y se apunta `remoteFs` a esa ruta
  (equivalente a `reuseNode`).
- Alternativa: workspace interno + `stash`/`archiveArtifacts`.

### 5.5 Cachés

- *Named volumes* o bind-mounts en la **plantilla**:
  `maven-cache:/cache/.m2`, `npm-cache:/cache/.npm`.
- El pipeline solo usa la ruta, sin `-v`.
- Ojo con la concurrencia sobre un mismo volumen (usa `disableConcurrentBuilds()`
  o un volumen por executor).

### 5.6 Selección del entorno

- `labelString` de cada plantilla; el pipeline usa `agent { label '...' }`.

### 5.7 Configuración (JCasC, orientativo)

```yaml
jenkins:
  clouds:
  - docker:
      name: "podman-host"
      containerCap: 10
      dockerApi:
        dockerHost:
          uri: "tcp://192.168.122.21:2375"
      templates:
      - name: "maven-jdk17"
        labelString: "maven-jdk17"
        remoteFs: "/datos/jenkins/pipelines-workspace"
        connector:
          jnlp:
            jenkinsUrl: "http://192.168.122.20:8080/"
            user: "1100"
        dockerTemplateBase:
          image: "localhost/agent-maven-jdk17:latest"
          pullStrategy: "NEVER"
          volumes:
            - "/datos/jenkins/pipelines-workspace:/datos/jenkins/pipelines-workspace"
            - "maven-cache:/cache/.m2"
```

> Los nombres JCasC varían por versión: configúralo por UI y usa "Export
> configuration as code".

### 5.8 Pros / contras / cuándo

- **Pros**: modelo "nodo" (labels, cuotas, retención); aislamiento por proyecto;
  pipeline limpio.
- **Contras**: mantener imágenes-agente; exponer la API; JCasC verboso;
  depuración indirecta.
- **Cuándo**: muchos equipos/proyectos con labels, cuotas y aislamiento.

---

## 6. Modelo C — Jenkins-Kubernetes

### 6.1 Concepto

El plugin **`kubernetes-plugin`** trata el clúster como una Cloud. Por cada
build crea un **Pod** (agente inbound) con **varios contenedores**: uno para el
agente (`jnlp`) y varios de herramientas. El pipeline elige el contenedor con
**`container('nombre')`**.

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
container('maven') { sh 'mvn -B clean package' }
```

### 6.2 Controller dentro o fuera del clúster

- **Fuera** (híbrido): funciona; normalmente con **WebSocket**.
- **Dentro** (todo K8s): lo natural para una arquitectura íntegramente K8s, con
  el **chart oficial `jenkins/jenkins`** (StatefulSet + PVC + RBAC +
  ServiceAccount + Service), o con manifiestos propios.

### 6.3 Cómo funciona por dentro

1. El pipeline pide un agente (label, `POD_LABEL`, o `agent { kubernetes }`).
2. La Cloud crea un **Pod** con los contenedores de la plantilla; inyecta
   `JENKINS_URL`/`JENKINS_SECRET`/`JENKINS_AGENT_NAME` en el contenedor del agente.
3. El inbound-agent conecta al controller (HTTP/WebSocket o puerto 50000).
4. `sh` corre en el contenedor del agente; `container('x')` ejecuta vía la **API
   exec** de K8s en otro contenedor del mismo Pod.
5. El **volumen de workspace** está montado en todos los contenedores.
6. Al terminar, el Pod se borra (según `podRetention`/`idleMinutes`).

### 6.4 Infraestructura necesaria

- Namespace, ServiceAccount + RBAC (pods, pods/exec, pods/log, PVCs), PVC para
  `JENKINS_HOME`, StatefulSet/Deployment del controller, Service.
- Imágenes de herramientas accesibles por el clúster.

### 6.5 Workspace

- `workspaceVolume`:
  - `emptyDirWorkspaceVolume` (def., efímero, **compartido entre contenedores
    del Pod**),
  - `dynamicPVC()` (PVC por Pod, se borra con él; no sirve para caché),
  - `persistentVolumeClaimWorkspaceVolume(...)` (persiste; ojo concurrencia),
  - `hostPathWorkspaceVolume(...)` (solo clústeres de 1 nodo/pruebas).
- Para pasar artefactos entre stages/plantillas: `stash`/`unstash` o
  `archiveArtifacts`.

### 6.6 Cachés

- PVC: `persistentVolumeClaim(claimName: 'maven-cache', mountPath: '/root/.m2')`.
- Con concurrencia, el PVC debe ser **RWX** (NFS/CephFS) o un PVC por proyecto.
- Alternativa mejor a escala: **caché remota** (Nexus/Artifactory/Verdaccio).

### 6.7 Construir imágenes (sin socket)

- **Kaniko**, **Buildah** (sin daemon), o **registry/build service**.
- Nada de `podman build` con socket del host: no existe en K8s.

### 6.8 Selección del entorno

- label/podTemplate + `container(...)` (y `defaultContainer`).
- UID consistente en todos los contenedores (`securityContext.runAsUser`).

### 6.9 Pipeline típico

```groovy
pipeline {
    agent { kubernetes { yaml '''...''' } }
    stages {
        stage('Backend')  { steps { container('maven') { sh 'cd backend && mvn -B clean package' } } }
        stage('Frontend') { steps { container('node')  { sh 'cd frontend && npm ci && npm run build' } } }
        stage('Imagen')   { steps { container('kaniko'){ sh '/kaniko/executor --context=. --dockerfile=backend/Dockerfile --destination=registry/app:${BUILD_NUMBER}' } } }
    }
}
```

### 6.10 Pros / contras / cuándo

- **Pros**: escalado horizontal; aislamiento/cuotas por namespace; pod template
  en el SCM; ecosistema K8s.
- **Contras**: requiere clúster; agentes con JDK+inbound; cambio en cómo se
  construyen imágenes; más piezas.
- **Cuándo**: ya hay (o se quiere) Kubernetes; muchos proyectos; escala.

---

# PARTE III — Comparativa, elección y temas transversales

## 7. Comparativa maestra

| Criterio | Podman-Host | Podman-Cloud | Jenkins-Kubernetes |
|---|---|---|---|
| Imagen del agente | Cualquiera | JDK + inbound/sshd | JDK + inbound (o `agentInjection`) |
| Grano | contenedor/stage | contenedor/build | Pod/build (varios contenedores) |
| Workspace | host + `reuseNode` | bind-mount del host | `workspaceVolume` |
| Compartir artefactos | automático (mismo host) | mismo volumen en plantillas | mismo Pod / `stash` |
| Cachés | `-v` en pipeline | volúmenes de plantilla | PVC / caché remota |
| Selección | imagen en pipeline | label de plantilla | label/podTemplate + `container()` |
| Build de imágenes | socket Podman | socket Podman | Kaniko/Buildah/registry |
| Escalado | manual | `containerCap` en un host | horizontal del clúster |
| Controller | VM/contenedor | VM/contenedor | VM/contenedor o Pod |
| Config en infra | poca | mucha | mucha |
| Mejor para | 1 host, toolchains varias | muchos equipos, labels | K8s, escala, aislamiento |

## 8. Cómo elegir

1. ¿Ya hay (o quieres) Kubernetes? → **Jenkins-Kubernetes**.
2. ¿Un host Podman/Docker y toolchains cambiantes? → **Podman-Host**.
3. ¿Un host pero necesitas labels/cuotas/aislamiento y puedes mantener
   imágenes-agente? → **Podman-Cloud**.
4. ¿Mínima infra y todo en el pipeline? → **Podman-Host**.
5. ¿Escala horizontal y aislamiento por namespace? → **Jenkins-Kubernetes**.

Se pueden **combinar** (p. ej. agentes "ricos" en K8s y ligeros con
`agent { docker {} }` en el mismo Jenkins).

## 9. Temas transversales

- **Seguridad de la API**: exponer la API de Podman/Docker da control total;
  TLS mutuo o túnel SSH. En K8s, RBAC mínimo por namespace.
- **Imágenes-agente** (Cloud/Kubernetes): construir con
  `FROM jenkins/inbound-agent` + toolchain. En Podman-Host no hacen falta.
- **Workspace**: efímero por defecto; persistir solo si se necesita; para pasar
  artefactos, `reuseNode` (Workflow), volumen compartido (Cloud) o
  `stash` (Kubernetes).
- **Cachés**: siempre fuera del workspace; en Docker volúmenes, en K8s PVC o
  caché remota.
- **Construcción de imágenes**: Docker ⇒ socket; Kubernetes ⇒ Kaniko/Buildah.

## 10. Recomendación para el laboratorio y roadmap

- El laboratorio Podman actual usa **Podman-Host** y es la mejor opción para
  su contexto (un host, toolchains varias).
- El plugin `docker-plugin` **puede convivir** con él; migrar a **Podman-Cloud**
  solo si aparecen necesidades de labels/cuotas/aislamiento.
- El siguiente laboratorio natural (si se quiere explorar K8s) es
  **Jenkins-Kubernetes**, partiendo del chart `jenkins/jenkins` sobre un clúster
  local (kind/k3d/k3s), tal y como se describe en
  `4_Ephemeral-Jenkins-Agents-Kubernetes.md`.

## 11. Referencias

- `docker-workflow`: https://plugins.jenkins.io/docker-workflow/
- `docker-plugin`: https://plugins.jenkins.io/docker-plugin/
- `kubernetes-plugin`: https://plugins.jenkins.io/kubernetes/
- API de Podman: https://docs.podman.io/en/latest/markdown/podman-system-service.1.html
- Chart oficial Jenkins: https://github.com/jenkinsci/helm-charts
- Guías hermanas de la serie (Podman-Host, Podman-Cloud, Kubernetes,
  comparativa).

---

*Licencia: [CC BY 4.0](./LICENSE). © 2026 jenkins-podman-lab contributors.*
