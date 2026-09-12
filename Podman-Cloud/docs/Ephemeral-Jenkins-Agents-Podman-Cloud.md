# Arquitectura de Agentes Efímeros con Podman y Jenkins — Variante "Cloud" (Docker plugin)

> **Modelo: `Podman-Cloud`.** Implementado con el plugin `docker-plugin`
> (Cloud + Docker Agent Templates): Jenkins aprovisiona **contenedores-agente
> bajo demanda** sobre un Podman Host, seleccionables por label.

> Documento complementario a `Ephemeral-Jenkins-Agents-Podman-host.md`.
> Aquí se analiza **el otro modelo** de agentes efímeros en Jenkins: definir
> la infraestructura como una **"Cloud" (Docker plugin) con plantillas de
> agente**, en lugar de crear contenedores desde el propio código del pipeline con
> `agent { docker { ... } }`.
>
> Responde, en orden, a las dudas planteadas: ¿se puede? ¿qué implicaciones
> tiene? ¿cómo se gestionan workspace, cachés y selección de agente? ¿qué
> hace Jenkins por detrás? ¿qué se configura en infraestructura y qué en el
> pipeline?

---

## 1. Respuesta corta

**Sí, es totalmente posible** montar el laboratorio con la definición "Cloud"
original (Jenkins → *Manage* → *Clouds* → *Docker*), apuntando a la API de
Podman del `podman-host`, con una **plantilla de agente por imagen** de
toolchain. Es el modelo que implementa el plugin **`docker-plugin`**
(docker-java, no la CLI) y es conceptualmente idéntico al plugin de
Kubernetes: Jenkins "pide" un agente y el proveedor crea un contenedor.

Pero hay **una diferencia de fondo que lo cambia todo**:

| | Podman-Host (`agent { docker {} }`) | Podman-Cloud (plugin `docker-plugin`) |
|---|---|---|
| Quién crea el contenedor | El código del pipeline, en cada `stage` | Jenkins (el *cloud provider*), al pedir un agente con cierto *label* |
| Qué corre dentro del contenedor | Tus comandos (`sh`) vía `docker exec` | Un **agente Jenkins** (JNLP/SSH) que ejecuta el build |
| ¿La imagen necesita Java + agente? | **No** (vale cualquier imagen) | **Sí** (JDK + `jenkins/inbound-agent` o sshd + JDK) |
| Unidad de ejecución | Un contenedor **por stage**, efímero dentro de un build | Un contenedor **por build** (o por executor), que es un "nodo" |
| Plugin | `docker-workflow` | `docker-plugin` ("Cloud" + "Docker Agent template") |

La consecuencia práctica más importante: en Podman-Cloud **las imágenes de
toolchain puras (`ubi9/openjdk-17`, `ubi9/nodejs-20`, `ubi9/podman`) no
sirven tal cual**, porque no son agentes Jenkins. Hay que construir imágenes
**híbridas** (toolchain + agente). En Podman-Host eso no hace falta, y suele
ser la razón principal por la que se acaba migrando a `agent { docker {} }`.

Todo lo demás (workspace, cachés, socket, selección) **se puede hacer**, pero
cambia *dónde* se configura: en Podman-Cloud casi todo vive en la **plantilla
del agente** (infraestructura), no en el código del pipeline.

---

## 2. Los tres modelos de agentes en Jenkins

1. **Podman-Host — Pipeline + `docker-workflow`**. Es el modelo del laboratorio **Podman-Host**, el unico implementado por ahora.
   `agent { docker { image '...'; reuseNode true; args '...' } }`. El plugin
   ejecuta `docker run` y luego **`docker exec`** cada paso dentro del
   contenedor. No hay agente dentro del contenedor: la "inteligencia" está en
   el nodo que hospeda el pipeline.

2. **Podman-Cloud — `docker-plugin`** (la "definición cloud"
   original). Se configura una *Cloud* apuntando a un *Podman host* (la API de
   Podman) y una o más *Docker Agent Templates*. Jenkins aprovisiona
   contenedores **como nodos/agentes** bajo demanda, según *labels*.

3. **Jenkins-Kubernetes** — Cloud de Kubernetes. El equivalente moderno para
   clústeres K8s; mismo concepto que **Podman-Cloud** pero el "pool" es el
   clúster. Fuera del alcance de este repositorio, pero conviene conocerlo
   porque comparte casi toda la lógica con **Podman-Cloud**.

El `docker-plugin` y el `docker-workflow` son **plugins distintos**, aunque
los nombres confundan (lo advierte el propio README del `docker-plugin`).
Instalar `docker-plugin` **no** elimina el soporte de `agent { docker {} }`;
pueden convivir.

---

## 3. Qué hace Jenkins "por detrás" en cada modelo

### 3.1 Podman-Host (`docker-workflow`)

Para cada bloque `agent { docker { ... } }`:

1. El nodo (el `podman-host`, agente JNLP conectado) ejecuta `docker inspect`
   / `docker pull` de la imagen.
2. `docker run -t -d -u <uid> <args> -w <workspace> -v <workspace>:<workspace> <image> cat`
   — arranca el contenedor "dormido".
3. `docker exec` para cada `sh` que definas en el `stage`.
4. `docker stop` + `docker rm` al terminar el bloque.

Puntos clave:
- El **workspace vive en el host** (el agente JNLP) y se **inyecta** en el
  contenedor con `-v` (esto es lo que hace `reuseNode true`).
- Por eso funciona con **cualquier imagen**, aunque no tenga Java ni agente.
- Los `args` que escribes en el pipeline son literalmente argumentos de
  `docker run` (ahí van `--userns=keep-id`, `-v maven-cache:...`, etc.).

### 3.2 Podman-Cloud

Cuando llega un build que necesita el label `maven-jdk17` y no hay agente
libre, el *cloud provider*:

1. Llama a la **API Docker/Podman** (por HTTP/TCP o por el socket) para
   `POST /containers/create` y `/start` con la config de la plantilla.
2. Inyecta la maquinaria de agente: nombre, secret, URL del controller y el
   *launch method* (JNLP o SSH). El contenedor arranca y **conecta de vuelta**
   al controller.
3. Jenkins lo registra como un `DockerComputer` (un nodo más) con 1 executor
   (habitualmente) y programa el build **en él**.
4. Al quedar inactivo, el plugin para y **elimina** el contenedor (retención /
   *idle timeout*).

Puntos clave:
- El **workspace vive dentro del contenedor** (`<remoteFs>/workspace/<job>`),
  salvo que lo montes como volumen del host.
- La **imagen debe ser un agente Jenkins** (JDK + inbound-agent/sshd).
- El plugin habla **la API** (docker-java), no necesita la CLI `docker` en
  ningún sitio.
- Existe la opción **"Expose DOCKER_HOST"** en la Cloud: si la activas, el
  plugin exporta `DOCKER_HOST` dentro del contenedor apuntando al host Docker
  usado por la Cloud (útil para el caso "construir imágenes desde el agente").

---

## 4. Infraestructura necesaria (Podman Host)

### 4.1 Exponer la API de Podman

La socket unit que ya usamos (`podman.socket` del usuario `jenkins`, ver
ADR-010) escucha en un **socket Unix**:
`/run/user/1100/podman/podman.sock`. La API incluye una **capa compatible
Docker v1.40** + la capa libpod. Hay tres formas de que el controller la use:

| Método | URI en la Cloud | Cuándo usarlo | Seguridad |
|---|---|---|---|
| Socket Unix local | `unix:///run/user/1100/podman/podman.sock` | Solo si Jenkins y Podman están en la **misma máquina** | Alta (permisos de fichero) |
| **TCP + mTLS** | `tcp://192.168.122.21:2376` + credencial X.509 | Remoto, producción | Alta (requiere certs) |
| **TCP sin TLS** | `tcp://192.168.122.21:2375` | Laboratorio en red aislada | **Baja** (ver aviso) |
| Túnel SSH | `tcp://127.0.0.1:2375` (tubería) o `unix://` reenviado | Remoto, sin tocar la red de Podman | Alta |

> ⚠️ **La documentación de Podman es explícita**: la API "concede acceso
> completo a toda la funcionalidad de Podman y por tanto permite ejecución
> arbitraria de código como el usuario que corre la API". Recomienda **no
> exponerla por red sin mTLS** y, si se necesita acceso remoto, **reenviar el
> socket por SSH**. Para este laboratorio (red `192.168.122.0/24` aislada)
> TCP sin TLS es aceptable *a sabiendas*, pero no lo copies a producción.

**Ejemplo — exponer rootless en TCP (solo laboratorio).** Override de la
socket unit del usuario `jenkins`:

```ini
# ~jenkins/.config/systemd/user/podman.socket.d/override.conf
[Socket]
ListenStream=
ListenStream=0.0.0.0:2375
```

```bash
sudo -u jenkins XDG_RUNTIME_DIR=/run/user/1100 systemctl --user daemon-reload
sudo -u jenkins XDG_RUNTIME_DIR=/run/user/1100 systemctl --user restart podman.socket
# comprobar
curl -s http://192.168.122.21:2375/_ping   # -> OK
```

**Ejemplo — TCP con mTLS (recomendado si no usas túnel):** generar CA + cert
de servidor + cert de cliente y arrancar el servicio como
`podman system service --tls-cert ... --tls-key ... --tls-client-ca ... tcp://0.0.0.0:2376`.
En Jenkins, la Cloud admite una **credencial "X.509 Client Certificate"** con
el CA, el cert y la clave.

**Ejemplo — túnel SSH (alternativa robusta):** desde el controller, mantener
una tubería al socket del podman-host y apuntar la Cloud a `localhost`:

```bash
# en el controller (systemd unit o autossh), reenvía el socket Unix remoto
ssh -N -L /run/podman-remote/podman.sock:/run/user/1100/podman/podman.sock \
    jenkins@192.168.122.21
# Cloud URI: unix:///run/podman-remote/podman.sock
```

(El reenvío de sockets Unix requiere OpenSSH reciente; si no, reenvía el
puerto TCP del socket al `localhost` del controller.)

### 4.2 El resto ya lo tienes

- `podman.socket` del usuario `jenkins` (ADR-010).
- `loginctl enable-linger jenkins`.
- La red de libvirt permite controller→podman-host.

---

## 5. Las imágenes de agente (la implicación grande)

En Podman-Cloud **cada imagen debe poder ejecutar un agente Jenkins** y
conectarse al controller. Hay tres *launch methods*:

| Launch method | Requisito de la imagen | Base recomendada |
|---|---|---|
| **JNLP / inbound** (recomendado) | JDK + `agent.jar` (lo inyecta el plugin) | `jenkins/inbound-agent` |
| **SSH** | `sshd` + JDK | `jenkins/ssh-agent` |
| **Attached** | JDK | `jenkins/agent` |

El controller debe ser **alcanzable desde el contenedor** (para JNLP, vía
WebSocket sobre el puerto HTTP 8080 es lo más cómodo; o el puerto 50000).

### 5.1 Imágenes híbridas (toolchain + agente)

Para reproducir el pipeline del laboratorio **Podman-Host** necesitas, por ejemplo:

```dockerfile
# agent-maven-jdk17: toolchain Maven + agente Jenkins
FROM jenkins/inbound-agent:latest-jdk17

USER root
RUN dnf install -y maven git && dnf clean all
USER jenkins
```

```dockerfile
# agent-node20: toolchain Node + agente Jenkins
FROM jenkins/inbound-agent:latest-jdk21   # el agente necesita un JRE
USER root
RUN dnf install -y nodejs npm git && dnf clean all
USER jenkins
```

> Fíjate en el matiz: la imagen del agente **siempre lleva un JDK** (para el
> propio agente), aunque tu proyecto sea Node. En Podman-Host, la imagen de
> Node no necesitaba Java.

### 5.2 Agente que además construye imágenes (Podman-out-of-Podman)

Si el stage empaqueta imágenes, el contenedor-agente necesita la **API de
Podman del host** dentro. Igual que en Podman-Host:

- montar el socket en la plantilla (`/run/user/1100/podman/podman.sock`),
- `--security-opt label=disable` (SELinux, ver ADR-011),
- `DOCKER_HOST`/`CONTAINER_HOST` apuntando al socket montado.

En Podman-Cloud esto se pone en la **plantilla**, no en el pipeline. Además
puedes activar **"Expose DOCKER_HOST"** en la Cloud.

---

## 6. Configurar la Cloud y las plantillas

### 6.1 Por UI

`Manage Jenkins → Clouds → Add a new cloud → Docker`:

> Los nombres de los campos son las etiquetas literales del plugin
> (`docker-plugin`), que usa "Docker" en su interfaz aunque aquí el motor sea
> **Podman**. Cuando en esta guía hablemos de "host", nos referimos al
> **Podman Host**.

- **Docker Cloud details**
  - *Docker Host URI*: `tcp://192.168.122.21:2375` (o `unix://...`, o con TLS).
  - *Server credentials*: solo si usas mTLS.
  - *Expose DOCKER_HOST*: útil para construir imágenes.
  - *Container Cap*: nº máximo de contenedores simultáneos.
- **Docker Agent templates → Add Docker Template**, con campos como:
  - *Name*, *Labels*, *Enabled*.
  - *Docker Image* y *Pull strategy* (`Never pull` para imágenes locales).
  - *Remote File System Root* (`remoteFs`).
  - *Connect method* (JNLP / SSH / attached) + *Jenkins URL* y *user*.
  - *Volumes*, *Volumes From*, *Environment*, *User*, *Network*,
    *Port bindings*, *Hostname*, *Privileged*, *Extra Hosts*, etc.
  - *Instance Capacity* (contenedores por host), *Idle timeout*.

### 6.2 Por JCasC (Configuration as Code)

A partir del ejemplo oficial del `docker-plugin`, adaptado a este lab:

```yaml
jenkins:
  clouds:
  - docker:
      name: "podman-host"
      containerCap: 10
      # Socket rootless del usuario jenkins (sin TLS: laboratorio aislado)
      dockerApi:
        dockerHost:
          uri: "tcp://192.168.122.21:2375"
      templates:
      - name: "maven-jdk17"
        labelString: "maven maven-jdk17"
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
          environment:
            - "MAVEN_OPTS=-Dmaven.repo.local=/cache/.m2/repository"

      - name: "node20"
        labelString: "node node20"
        remoteFs: "/datos/jenkins/pipelines-workspace"
        connector:
          jnlp:
            jenkinsUrl: "http://192.168.122.20:8080/"
            user: "1100"
        dockerTemplateBase:
          image: "localhost/agent-node20:latest"
          pullStrategy: "NEVER"
          volumes:
            - "/datos/jenkins/pipelines-workspace:/datos/jenkins/pipelines-workspace"
            - "npm-cache:/cache/.npm"

      - name: "podman-build"
        labelString: "podman-build"
        remoteFs: "/datos/jenkins/pipelines-workspace"
        connector:
          jnlp:
            jenkinsUrl: "http://192.168.122.20:8080/"
            user: "1100"
        dockerTemplateBase:
          image: "localhost/agent-podman:latest"
          pullStrategy: "NEVER"
          volumes:
            - "/datos/jenkins/pipelines-workspace:/datos/jenkins/pipelines-workspace"
            - "/run/user/1100/podman/podman.sock:/run/podman/podman.sock"
          environment:
            - "CONTAINER_HOST=unix:///run/podman/podman.sock"
```

> Los nombres exactos de los campos JCasC (`pullStrategy`, `environment`,
> `volumes`, `dockerTemplateBase`, etc.) **pueden variar según la versión**
> del plugin. La forma fiable de obtenerlos es configurar la Cloud por UI y
> usar **"Export configuration as code"** (plugin `configuration-as-code`) o
> el *script console* con un `export`.

---

## 7. Workspace (la pregunta central)

### 7.1 Dónde vive el workspace en Podman-Cloud

Jenkins crea, **dentro del contenedor**, `<remoteFs>/workspace/<job>` (y
`<remoteFs>/workspace/<job>@2` para builds concurrentes del mismo job). Como
el contenedor es efímero, por defecto **ese workspace se destruye** con él.

### 7.2 Cómo "compartirlo" con los agentes efímeros

No existe `reuseNode` en Podman-Cloud. Lo que haces es **montar un directorio
del host dentro de cada plantilla** y apuntar `remoteFs` a esa ruta:

- Volumen en la plantilla:
  `/datos/jenkins/pipelines-workspace:/datos/jenkins/pipelines-workspace`
- `remoteFs`: `/datos/jenkins/pipelines-workspace`

Resultado: el workspace real queda en el **host** (`podman-host`), igual que
en **Podman-Host**, y sobrevive a la destrucción del contenedor. Es exactamente
la ruta que ya usa el laboratorio **Podman-Host**
(`/datos/jenkins/pipelines-workspace/workspace/reference-pipeline`), así que
el código prepoblado por Ansible seguiría funcionando.

### 7.3 ¿Compartir workspace entre stages distintos?

En Podman-Host, cada `stage` con `agent { docker }` abre su propio contenedor,
pero **todos comparten el workspace del host** (vía `reuseNode`), de modo que
el `backend/target/` de un stage lo ve el siguiente.

En Podman-Cloud, si cada stage pide un **label distinto** (Maven → Node →
Podman), cada uno es un **agente distinto** (contenedor distinto). Para que
compartan los artefactos hay dos opciones:

1. **Montar el mismo directorio del host en las tres plantillas** (como en el
   ejemplo JCasC). Los tres ven `/datos/jenkins/pipelines-workspace` → misma
   ruta, mismo contenido. Es el equivalente funcional a `reuseNode`.
2. Dejar que cada agente use su workspace **interno** y comunicar artefactos
   entre stages con `stash`/`unstash` o `archiveArtifacts` (más "cloud-native",
   pero más verboso y no persiste entre builds).

En el laboratorio **Podman-Host**, la opción 1 es la que mantiene el comportamiento actual.

### 7.4 Concurrencia y `WorkspaceVolume`

- Dos builds simultáneos del mismo job usan `<job>` y `<job>@2`: con el
  volumen compartido funcionan, pero **no deben pisarse** (usa `disableConcurrentBuilds()`
  como ya hace el pipeline).
- El `docker-plugin` tiene un concepto de **`WorkspaceVolume`** (volumen
  específico para el workspace, p. ej. un *named volume* por agente). Es otra
  vía para persistir el workspace sin montar el directorio del host, pero
  complica la compartición entre plantillas. Para este lab, el bind-mount del
  host es más simple y consistente.

---

## 8. Cachés de Maven y npm

En Podman-Host las cachés se pasaban en el código del pipeline, dentro de `args`
(`-v maven-cache-${EXECUTOR_NUMBER}:/cache/.m2`). En Podman-Cloud **se mueven
a la plantilla**:

```yaml
dockerTemplateBase:
  volumes:
    - "maven-cache:/cache/.m2"     # named volume, persiste entre builds
```

y el pipeline simplemente usa la ruta (`/cache/.m2`), sin `-v`.

Consideraciones:
- **Named volume compartido por todos los agentes de ese label**: cómodo
  (calienta la caché una vez), pero **peligroso con builds concurrentes** (dos
  Maven escribiendo en el mismo `repository/`). Opciones: `disableConcurrentBuilds()`,
  un volumen por executor, o aceptar el riesgo (Maven suele tolerarlo; npm algo
  menos).
- **Bind-mount a un directorio del host** (`/datos/jenkins/maven-cache:/cache/.m2`):
  más fácil de inspeccionar/limpiar (ver "Pruning" en la guía principal).
- Recuerda que un agente Node **también** lleva JDK (por el propio agente), así
  que el volumen de Maven no "estorba", pero no lo necesitas en su plantilla.

---

## 9. Selección del agente efímero

- Cada plantilla declara uno o varios **labels** (`labelString: "maven maven-jdk17"`).
- El pipeline elige con `agent { label 'maven-jdk17' }`.
- Jenkins busca un agente **con ese label**; si no hay, el *cloud provider*
  crea un contenedor de la plantilla que lo ofrece.
- Un contenedor = 1 executor (por defecto `mode: EXCLUSIVE`, ver el ejemplo
  JCasC). Para permitir varios builds en el mismo contenedor, `mode: NORMAL`
  con `numExecutors > 1`, pero **no es lo habitual** con agentes efímeros.
- Como en Podman-Host, distintos stages pueden usar distintos labels:

```groovy
stage('Backend')   { agent { label 'maven-jdk17' }   ; steps { sh 'cd backend && mvn -B clean package' } }
stage('Frontend')  { agent { label 'node20' }         ; steps { sh 'cd frontend && npm ci && npm run build' } }
stage('Imagen')    { agent { label 'podman-build' }   ; steps { sh 'podman build -t app:${BUILD_NUMBER} -f backend/Dockerfile .' } }
```

---

## 10. Ejemplo de pipeline completo (Podman-Cloud)

```groovy
pipeline {
    agent none   // cada stage elige su plantilla por label

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    stages {
        stage('Backend (Maven)') {
            agent { label 'maven-jdk17' }
            steps {
                sh '''
                    cd backend
                    mvn -B -ntp -Dmaven.repo.local=/cache/.m2/repository clean package
                '''
            }
        }

        stage('Frontend (Node)') {
            agent { label 'node20' }
            steps {
                sh '''
                    cd frontend
                    npm ci --cache /cache/.npm --prefer-offline
                    npm run build
                '''
            }
        }

        stage('Empaquetar imagenes (Podman)') {
            agent { label 'podman-build' }
            environment { CONTAINER_HOST = 'unix:///run/podman/podman.sock' }
            steps {
                sh '''
                    podman build --format docker -t reference-backend:latest -f backend/Dockerfile .
                    podman build --format docker -t reference-frontend:latest -f frontend/Dockerfile .
                '''
            }
        }
    }
}
```

Diferencias con el pipeline de Podman-Host:
- No hay `configFileProvider(...)` para settings de Maven/npm si decides
  hornear la config en la imagen del agente (o puedes seguir usándolo).
- No hay `args` con `-v`/`--userns`: **todo el "chrome" de Docker está en la
  plantilla**.
- El workspace se comparte porque las tres plantillas montan el mismo
  directorio del host.

---

## 11. Ventajas, inconvenientes y cuándo elegir cada modelo

### Podman-Cloud — ventajas

- **Mentalidad "nodo"**: los agentes aparecen en `/computer`, con labels,
  retención, *idle timeout*, etc. Muy natural si vienes de Kubernetes.
- **Aislamiento total por proyecto**: cada plantilla es una imagen y un
  entorno; no dependes del nodo anfitrión para nada salvo la API.
- **Escalado/gestión centralizada**: una Cloud sirve a muchos jobs; añadir un
  toolchain = añadir una plantilla.
- El **pipeline queda limpio**: no hay que conocer Docker para escribir
  stages; solo labels.
- `containerCap` limita el consumo.

### Podman-Cloud — inconvenientes

- **Las imágenes deben ser agentes Jenkins** (JDK + inbound-agent/sshd). Es el
  coste principal: mantenimiento de imágenes híbridas.
- Necesitas **exponer la API de Podman** de forma segura (TLS o túnel SSH).
- **Workspace y cachés se configuran en la plantilla**, no en el pipeline; si
  cambian según el proyecto, toca tocar infraestructura (o usar una plantilla
  por proyecto).
- Depurar es más indirecto: errores en el arranque del contenedor/agente
  aparecen en los logs del *cloud* y en `/computer`, no en el log del stage.
- Escribir JCasC de la Cloud es verboso y sensible a versiones.

### Podman-Host (`agent { docker {} }`) — ventajas

- **Cualquier imagen vale** (toolchain pura). Menos imágenes que mantener.
- Todo se ve en el código del pipeline: los `-v`, el `--userns`, las cachés. Más
  "todo en el código" (GitOps) y más fácil de versionar por proyecto.
- Un contenedor **por stage**, muy efímero; el workspace lo controla el
  pipeline.
- Depuración directa en el log del stage.

### Podman-Host — inconvenientes

- El código del pipeline conoce Docker (`args`, `--userns=keep-id`, mounts): más
  acoplado y más fácil de romper.
- No hay "nodos" de agente visibles; el concepto de agente es el nodo
  anfitrión (uno solo), y los contenedores son sidecars efímeros.
- Menos aislamiento entre proyectos que Podman-Cloud.

### Recomendación

- **Un solo nodo Podman, varios proyectos, toolchains cambiantes**: **Podman-Host**
  (el implementado). Es lo que mejor encaja y lo que menos imágenes exige.
- **Muchos equipos/proyectos, necesidad de labels, cuotas y aislamiento**:
  **Podman-Cloud** (o **Jenkins-Kubernetes**). Merece la pena cuando el coste de
  construir imágenes-agente se amortiza.
- Puedes **tener ambos**: `docker-plugin` para algunos agentes "ricos" y
  `agent { docker {} }` para los efímeros ligeros, dentro del mismo Jenkins.

---

## 12. Checklist para migrar el laboratorio a Podman-Cloud

1. Exponer la API de Podman (SSH-tunnel o TCP+TLS; TCP sin TLS solo en lab).
2. Añadir la Cloud en Jenkins (UI o JCasC) apuntando a esa URI; **Test
   Connection**.
3. Construir las **imágenes-agente híbridas** (`agent-maven-jdk17`,
   `agent-node20`, `agent-podman`) con `FROM jenkins/inbound-agent` + toolchain,
   y subirlas/importarlas en el podman-host.
4. Crear una **plantilla por imagen** con:
   - `labelString` distinto,
   - `remoteFs` = `/datos/jenkins/pipelines-workspace`,
   - volumen del host del workspace,
   - volumen de caché (`maven-cache`/`npm-cache`),
   - (para la de build) socket + `--security-opt label=disable` + `CONTAINER_HOST`,
   - *Connect method* JNLP con `jenkinsUrl` del controller.
5. Reescribir el pipeline para usar `agent { label '...' }` y quitar `args`.
6. Ajustar `/datos/jenkins/pipelines-workspace` como volumen compartido en las
   tres plantillas (para que los stages se pasen los artefactos).
7. Probar: forzar cada label, comprobar `/computer`, verificar que el
   workspace persiste y que las cachés se reutilizan.
8. Endurecer: TLS en la API, `containerCap`, *idle timeout* razonable,
   limpieza de contenedores huérfanos.

---

## 13. Troubleshooting (Podman-Cloud)

- **"Agent is being disconnected" / el contenedor arranca pero no conecta**:
  el controller no es alcanzable desde el contenedor. Revisa `jenkinsUrl`
  (usa la IP del controller, no `localhost`), y que el puerto HTTP/JNLP sea
  accesible desde la red de contenedores del podman-host.
- **"no such image" con imágenes locales**: pon *Pull strategy* = `Never pull`.
- **Permission denied al construir imágenes**: el socket no está montado, o
  SELinux lo bloquea (usa `--security-opt label=disable`, ver ADR-011), o el
  UID dentro del contenedor no mapea al del socket.
- **El workspace "desaparece" entre builds**: olvidaste montar el volumen del
  host (o `remoteFs` no apunta a la ruta montada).
- **Cachés corruptas / builds concurrentes fallan**: mismo `maven-cache`
  compartido por varios ejecutores; usa `disableConcurrentBuilds()` o un
  volumen distinto por agente.
- **Contenedores que se acumulan**: ajusta *Idle timeout* / *Container Cap* y
  limpia con `podman container prune`.

---

## 14. Referencias

- Plugin Docker (Cloud): https://plugins.jenkins.io/docker-plugin/ ·
  https://github.com/jenkinsci/docker-plugin
- Plugin Pipeline: Docker (`agent { docker }`):
  https://plugins.jenkins.io/docker-workflow/ ·
  https://www.jenkins.io/doc/book/pipeline/docker/
- API de Podman (`podman system service`):
  https://docs.podman.io/en/latest/markdown/podman-system-service.1.html
- `jenkins/inbound-agent`: https://hub.docker.com/r/jenkins/inbound-agent
- JCasC (ejemplo Docker):
  https://github.com/jenkinsci/configuration-as-code-plugin/tree/master/demos/docker
- Guia principal del laboratorio Podman-Host:
  [`Ephemeral-Jenkins-Agents-Podman-host.md`](../../Podman-Host/docs/Ephemeral-Jenkins-Agents-Podman-host.md)
- ADRs relacionados (del lab Podman-Host):
  [ADR-005](../../Podman-Host/docs/adr/0005-podman-secrets-como-root.md) (secrets root),
  [ADR-009](../../Podman-Host/docs/adr/0009-reference-app-sin-scm.md) (app de referencia sin SCM),
  [ADR-010](../../Podman-Host/docs/adr/0010-habilitar-podman-socket.md) (podman.socket),
  [ADR-011](../../Podman-Host/docs/adr/0011-security-opt-label-disable-podman-socket.md) (`--security-opt label=disable`).

---

*Licencia: [CC BY 4.0](../../guides/LICENSE). © 2026 Cloudsdoers.*
