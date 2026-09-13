# Arquitecturas de Agentes Efímeros en Jenkins — Documento unificado

> **Documento único y autocontenido.** Reúne, en este orden:
> (1) las explicaciones de los modelos existentes y (2) el detalle de cada uno.
>
> Es la versión "todo en uno" de la serie:
> - `Ephemeral-Jenkins-Agents-Podman-host.md` — guía del modelo **Podman-Host** (laboratorio Podman).
> - `Ephemeral-Jenkins-Agents-Podman-Cloud.md` — guía del modelo **Podman-Cloud**.
> - `Ephemeral-Jenkins-Agents-Kubernetes.md` — guía del modelo **Jenkins-Kubernetes**.
> - `Ephemeral-jenkins-Agents-Architectures-Compartive-guide.md` — comparativa de los tres modelos.

---

# PARTE I — Explicación de los modelos

## 1. Concepto de agente efímero

Un **agente efímero** es un entorno de ejecución aislado que se crea **bajo
demanda** para un build y se destruye al terminar.

Beneficios:

- **Aislamiento**: cada build parte de un entorno limpio, sin restos del
  anterior.
- **Reproducibilidad**: la imagen está versionada; el clásico "en mi máquina
  funciona" deja de tener sentido.
- **Escalado**: se crean los que hagan falta y se apagan al terminar.
- **Seguridad**: las credenciales y toolchains no persisten en un nodo
  compartido.

El precio es decidir cuatro aspectos:

1. **Dónde** corren los agentes (host Docker/Podman o clúster Kubernetes).
2. **Cómo** se aprovisionan (desde el propio pipeline o desde una Cloud).
3. **Dónde vive el workspace** y cómo se comparte entre stages y builds.
4. **Cómo se cachean** Maven o npm para no re-descargar el mundo en cada
   build.

De esas cuatro respuestas surgen los tres modelos.

## 2. Los tres modelos existentes

| | **Podman-Host** | **Podman-Cloud** | **Jenkins-Kubernetes** |
|---|---|---|---|
| Plugin | `docker-workflow` | `docker-plugin` | `kubernetes-plugin` |
| Sintaxis | `agent { docker { image } }` | Cloud + Docker Agent Template + `agent { label }` | Cloud + Pod Template + `agent { kubernetes }` |
| Quién crea el entorno | el código del pipeline | Jenkins (Cloud) | Jenkins (Cloud) |
| Entorno | contenedor | contenedor (nodo-agente) | Pod (nodo-agente) |
| Grano | un contenedor por stage | un contenedor por build | un Pod por build, varios contenedores |
| ¿La imagen necesita agente? | No | Sí (JDK + inbound/sshd) | Sí (o `agentInjection`) |
| Workspace | host + `reuseNode` | bind-mount del host | `workspaceVolume` (emptyDir/PVC) |
| Cachés | `-v` en el pipeline | volúmenes de plantilla | PVC / caché remota |
| Selección | imagen en el pipeline | label de plantilla | label/podTemplate + `container()` |
| Controller | VM/contenedor | VM/contenedor | VM/contenedor o Pod |

Nomenclatura:

- **Podman-Host**: el modelo del laboratorio Podman-Host.
- **Podman-Cloud**: la definición de Cloud con `docker-plugin`.
- **Jenkins-Kubernetes**: todo sobre Kubernetes con `kubernetes-plugin`.

## 3. Fundamentos comunes a los tres

- El **controller** es independiente de dónde corran los agentes. Puede ser
  una VM, un contenedor o, en Kubernetes, un Pod.
- La **comunicación agente → controller** es "inbound" (JNLP o WebSocket) en
  Cloud y Kubernetes; en Podman-Host no hay agente, el nodo hace
  `docker exec`.
- El **workspace** admite modalidad efímera (limpio por build) o persistente;
  el intercambio de artefactos se resuelve con `reuseNode`, un volumen
  compartido o `stash`.
- Las **cachés** se mantienen fuera del workspace (volúmenes, PVC o caché
  remota) para acelerar y no contaminar el workspace.
- La **selección del entorno** se realiza por **imagen** (Workflow) o por
  **label** (Cloud y Kubernetes).
- La **construcción de imágenes** difiere entre modelos: en Docker/Podman se
  usa `docker/podman build` con socket; en Kubernetes se recurre a Kaniko,
  Buildah o un registro.

---

# PARTE II — Detalle de cada modelo

## 4. Podman-Host

### 4.1 Concepto

El plugin **`docker-workflow`** permite declarar, en el propio código del
pipeline, un contenedor por stage. Es el modelo del laboratorio Podman.

```groovy
stage('Backend') {
    agent {
        docker {
            image 'registry.access.redhat.com/ubi9/openjdk-17:latest'
            reuseNode true
            args '--userns=keep-id -v maven-cache-${EXECUTOR_NUMBER}:/cache/.m2:z'
        }
    }
    steps { sh 'cd backend && mvn -B clean package' }
}
```

### 4.2 Funcionamiento interno

1. Un **agente Jenkins residente** (el `podman-host`) ejecuta `docker run`
   con los `args` del pipeline, dejando el contenedor "dormido" (`cat`).
2. Cada `sh` del stage se ejecuta con **`docker exec`** dentro del contenedor.
3. Al terminar el stage: `docker stop` y `docker rm`.
4. `reuseNode true` monta el **workspace del host** dentro del contenedor.

`docker` puede ser la CLI real o un **alias a Podman** (`podman-docker`).

### 4.3 Infraestructura necesaria

- Un host con Docker o Podman y un **socket** accesible por el usuario del
  agente. En Podman rootless: `podman.socket` del usuario (ADR-010).
- El agente Jenkins debe poder ejecutar `docker` o `podman` (PATH, permisos).
- Si el contenedor empaqueta imágenes, se monta el socket en el contenedor
  del stage con `--security-opt label=disable` (SELinux, ADR-011).

### 4.4 Workspace

- Reside en el **host** del agente: `<RemoteFs>/workspace/<job>`.
- Se inyecta en cada contenedor con `-v` (lo hace `reuseNode true`).
- Todos los stages comparten ese workspace, de modo que los artefactos
  (`backend/target`, `frontend/dist`) fluyen entre stages sin necesidad de
  `stash`.

### 4.5 Cachés

- Se pasan en los `args` del pipeline:
  `-v maven-cache-${EXECUTOR_NUMBER}:/cache/.m2:z`.
- Lo habitual es un volumen **por ejecutor**
  (`maven-cache-${EXECUTOR_NUMBER}`) para evitar colisiones con builds
  concurrentes.
- Las rutas son fijas dentro del contenedor (`/cache/.m2`), sin depender de
  `$HOME`.

### 4.6 Selección del entorno

- No hay labels: el **pipeline selecciona la imagen** de cada stage.

### 4.7 Pipeline típico (laboratorio Podman-Host)

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

- **Pros**: cualquier imagen; todo en el pipeline; contenedor por stage;
  depuración sencilla.
- **Contras**: el pipeline conoce Docker; un único nodo anfitrión; menor
  aislamiento entre proyectos.
- **Cuándo**: un host Podman, varios proyectos y toolchains cambiantes.

---

## 5. Podman-Cloud

### 5.1 Concepto

El plugin **`docker-plugin`** define una **Cloud** que apunta a la API de un
Docker o Podman host y una o más **Docker Agent Templates**. Jenkins
aprovisiona contenedores **como nodos-agente** bajo demanda, en función de
**labels**.

```groovy
stage('Backend') {
    agent { label 'maven-jdk17' }
    steps { sh 'mvn -B clean package' }
}
```

### 5.2 Funcionamiento interno

1. Un build solicita el label `maven-jdk17`; si no hay agente, la Cloud
   ejecuta `POST /containers/create` + `/start` vía **docker-java** (no la CLI).
2. Inyecta el *launch method* (JNLP, SSH o attached) con nombre, secret y
   URL del controller; el contenedor **conecta de vuelta** y se registra
   como nodo.
3. El build se ejecuta **en** ese nodo-agente.
4. Al quedar inactivo, el plugin detiene y **elimina** el contenedor
   (*idle timeout*, `podRetention`, `containerCap`).

### 5.3 Infraestructura necesaria

- **Exponer la API de Podman**:
  - Túnel **SSH** (recomendado por Podman para acceso remoto), o
  - **TCP + mTLS** (puerto 2376, certificado X.509), o
  - **TCP sin TLS** solo en red aislada (laboratorio).
  - `unix://` únicamente si Jenkins y Podman están en la misma máquina.
- **Imágenes-agente híbridas** (toolchain + `jenkins/inbound-agent`).
- En este laboratorio, la configuración declarativa vía JCasC presenta
  incompatibilidades con Jenkins LTS 2.568.3, por lo que se opta por scripts
  `init.groovy.d` para provisionar la Cloud y las plantillas.

> La API de Podman concede **control total** (ejecución arbitraria como el
> usuario que la ejecuta). No debe exponerse por red sin mTLS.

### 5.4 Workspace

- Reside **dentro del contenedor**: `<remoteFs>/workspace/<job>`.
- Como el contenedor es efímero, para persistir y compartir el workspace se
  **monta un directorio del host** en cada plantilla y se apunta `remoteFs` a
  esa ruta (equivalente funcional a `reuseNode`).
- Alternativa: workspace interno y `stash`/`archiveArtifacts` para mover
  artefactos entre stages.

### 5.5 Cachés

- *Named volumes* o bind-mounts declarados en la **plantilla**:
  `type=volume,source=maven-cache,destination=/cache/.m2`,
  `type=volume,source=npm-cache,destination=/cache/.npm`.
- El pipeline se limita a usar la ruta, sin `-v`.
- Con concurrencia sobre un mismo volumen debe recurrirse a
  `disableConcurrentBuilds()` o a un volumen por executor.

### 5.6 Selección del entorno

- `labelString` de cada plantilla; el pipeline usa `agent { label '...' }`.

### 5.7 Configuración declarativa (orientativa)

El siguiente fragmento JCasC es una **referencia**; el laboratorio no lo
aplica de forma directa. La forma canónica de la configuración real está en
`init.groovy.d/`.

```yaml
jenkins:
  clouds:
  - docker:
      name: "podman-cloud"
      containerCap: 10
      dockerApi:
        dockerHost:
          uri: "tcp://192.168.122.31:2376"
          credentialsId: "podman-cloud-tls"
      templates:
      - name: "maven-jdk17"
        labelString: "maven maven-jdk17"
        remoteFs: "/datos/jenkins/pipelines-workspace"
        connector:
          jnlp:
            jenkinsUrl: "http://192.168.122.30:8080/"
        dockerTemplateBase:
          image: "localhost/agent-maven-jdk17:latest"
          pullStrategy: "NEVER"
          user: "0"
          mountsString: "type=bind,source=/datos/jenkins/pipelines-workspace,destination=/datos/jenkins/pipelines-workspace\ntype=volume,source=maven-cache,destination=/cache/.m2"
          environment:
            - "MAVEN_OPTS=-Dmaven.repo.local=/cache/.m2/repository"
          securityOptsString: "label=disable"
```

> Los nombres JCasC varían entre versiones del plugin. La forma fiable de
> obtenerlos es configurar la Cloud por UI y usar "Export configuration as
> code".

### 5.8 Pros / contras / cuándo

- **Pros**: modelo "nodo" (labels, cuotas, retención); aislamiento por
  proyecto; pipeline limpio.
- **Contras**: mantener imágenes-agente; exponer la API de forma segura;
  configuración verbosa; depuración indirecta.
- **Cuándo**: muchos equipos o proyectos con necesidad de labels, cuotas y
  aislamiento.

---

## 6. Jenkins-Kubernetes

### 6.1 Concepto

El plugin **`kubernetes-plugin`** trata el clúster como una Cloud. Por cada
build crea un **Pod** (agente inbound) con **varios contenedores**: uno para
el agente (`jnlp`) y varios de herramientas. El pipeline selecciona el
contenedor con **`container('nombre')`**.

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

- **Fuera** (híbrido): viable; típicamente con **WebSocket**.
- **Dentro** (todo K8s): la opción natural para una arquitectura íntegramente
  Kubernetes, con el **chart oficial `jenkins/jenkins`** (StatefulSet + PVC
  + RBAC + ServiceAccount + Service), o con manifiestos propios.

### 6.3 Funcionamiento interno

1. El pipeline solicita un agente (label, `POD_LABEL` o `agent { kubernetes }`).
2. La Cloud crea un **Pod** con los contenedores de la plantilla e inyecta
   `JENKINS_URL`, `JENKINS_SECRET` y `JENKINS_AGENT_NAME` en el contenedor
   del agente.
3. El inbound-agent conecta con el controller (HTTP/WebSocket o puerto 50000).
4. `sh` se ejecuta en el contenedor del agente; `container('x')` lanza el
   comando vía la **API exec** de K8s en otro contenedor del mismo Pod.
5. El **volumen de workspace** está montado en todos los contenedores.
6. Al terminar, el Pod se elimina (según `podRetention`/`idleMinutes`).

### 6.4 Infraestructura necesaria

- Namespace, ServiceAccount y RBAC (pods, pods/exec, pods/log, PVCs), PVC
  para `JENKINS_HOME`, StatefulSet/Deployment del controller y Service.
- Imágenes de herramientas accesibles por el clúster.

### 6.5 Workspace

- `workspaceVolume`:
  - `emptyDirWorkspaceVolume` (por defecto, efímero, **compartido entre los
    contenedores del Pod**),
  - `dynamicPVC()` (PVC por Pod, se elimina con él; no apto para caché),
  - `persistentVolumeClaimWorkspaceVolume(...)` (persistente; con cuidado en
    concurrencia),
  - `hostPathWorkspaceVolume(...)` (solo para clústeres de un nodo o pruebas).
- Para pasar artefactos entre stages o plantillas: `stash`/`unstash` o
  `archiveArtifacts`.

### 6.6 Cachés

- PVC: `persistentVolumeClaim(claimName: 'maven-cache', mountPath: '/root/.m2')`.
- En entornos con concurrencia, el PVC debe ser **RWX** (NFS, CephFS) o
  asignarse un PVC por proyecto.
- A escala, la alternativa preferente es la **caché remota** (Nexus,
  Artifactory, Verdaccio).

### 6.7 Construcción de imágenes (sin socket)

- **Kaniko**, **Buildah** (sin daemon) o **registry/build service**.
- No se utiliza `podman build` con socket del host: no existe en Kubernetes.

### 6.8 Selección del entorno

- label/podTemplate + `container(...)` (y `defaultContainer`).
- UID coherente en todos los contenedores
  (`securityContext.runAsUser`).

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

- **Pros**: escalado horizontal; aislamiento y cuotas por namespace; pod
  template en el SCM; ecosistema Kubernetes.
- **Contras**: requiere clúster; los agentes necesitan JDK e inbound;
  cambia la forma de construir imágenes; más piezas en juego.
- **Cuándo**: ya existe (o se quiere) Kubernetes; muchos proyectos; necesidad
  de escala.

---

# PARTE III — Comparativa, elección y temas transversales

## 7. Comparativa maestra

| Criterio | Podman-Host | Podman-Cloud | Jenkins-Kubernetes |
|---|---|---|---|
| Imagen del agente | cualquiera | JDK + inbound/sshd | JDK + inbound (o `agentInjection`) |
| Grano | contenedor por stage | contenedor por build | Pod por build (varios contenedores) |
| Workspace | host + `reuseNode` | bind-mount del host | `workspaceVolume` |
| Compartir artefactos | automático (mismo host) | mismo volumen en plantillas | mismo Pod / `stash` |
| Cachés | `-v` en el pipeline | volúmenes de plantilla | PVC / caché remota |
| Selección | imagen en el pipeline | label de plantilla | label/podTemplate + `container()` |
| Build de imágenes | socket Podman | socket Podman | Kaniko/Buildah/registry |
| Escalado | manual | `containerCap` en un host | horizontal del clúster |
| Controller | VM/contenedor | VM/contenedor | VM/contenedor o Pod |
| Configuración en infraestructura | poca | mucha | mucha |
| Mejor para | un host con toolchains cambiantes | muchos equipos, labels | Kubernetes, escala, aislamiento |

## 8. Cómo elegir

1. ¿Ya existe (o se quiere) Kubernetes? → **Jenkins-Kubernetes**.
2. ¿Un host Podman y toolchains cambiantes? → **Podman-Host**.
3. ¿Un host pero se necesitan labels, cuotas y aislamiento por proyecto, y se
   puede mantener imágenes-agente? → **Podman-Cloud**.
4. ¿Mínima infraestructura y todo en el pipeline? → **Podman-Host**.
5. ¿Escala horizontal y aislamiento por namespace? → **Jenkins-Kubernetes**.

Los modelos pueden **combinarse** en un mismo Jenkins (por ejemplo, agentes
"completos" en Kubernetes y agentes ligeros con `agent { docker {} }`).

## 9. Temas transversales

- **Seguridad de la API**: exponer la API de Podman concede control total;
  se recurre a TLS mutuo o túnel SSH. En Kubernetes, RBAC mínimo por
  namespace.
- **Imágenes-agente** (Cloud y Kubernetes): se construyen con
  `FROM jenkins/inbound-agent` y la toolchain correspondiente. En
  Podman-Host no son necesarias.
- **Workspace**: efímero por defecto; persistente solo cuando se requiere;
  para pasar artefactos, `reuseNode` (Workflow), volumen compartido (Cloud)
  o `stash` (Kubernetes).
- **Cachés**: siempre fuera del workspace; en Docker, volúmenes; en
  Kubernetes, PVC o caché remota.
- **Construcción de imágenes**: en Docker/Podman, socket; en Kubernetes,
  Kaniko/Buildah/registro.

## 10. Recomendación para los laboratorios y roadmap

- El laboratorio **Podman-Host** se adapta bien al contexto descrito (un
  host, toolchains varias).
- El plugin `docker-plugin` **convive** con `docker-workflow`; migrar a
  **Podman-Cloud** solo cuando aparezcan necesidades reales de labels, cuotas
  o aislamiento.
- El siguiente laboratorio natural es **Jenkins-Kubernetes**, partiendo del
  chart `jenkins/jenkins` sobre un clúster local (kind, k3d o k3s), tal y
  como se describe en `Ephemeral-Jenkins-Agents-Kubernetes.md`.

## 11. Referencias

- `docker-workflow`: https://plugins.jenkins.io/docker-workflow/
- `docker-plugin`: https://plugins.jenkins.io/docker-plugin/
- `kubernetes-plugin`: https://plugins.jenkins.io/kubernetes/
- API de Podman: https://docs.podman.io/en/latest/markdown/podman-system-service.1.html
- Chart oficial Jenkins: https://github.com/jenkinsci/helm-charts
- Guías hermanas de la serie (Podman-Host, Podman-Cloud, Jenkins-Kubernetes,
  comparativa).

---

*Licencia: [CC BY 4.0](./LICENSE). © 2026 Cloudsdoers.*
