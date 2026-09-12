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

1. **Podman-Host — Pipeline + `docker-workflow`**. Es el modelo del laboratorio
   **Podman-Host**.
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

### 4.1 Exponer la API de Podman (ROOTFUL)

En este modelo la API de Podman corre **rootful** (servicio de sistema). El
socket Unix por defecto es `/run/podman/podman.sock`, y hay que exponerlo en
**TCP** para que el controller lo alcance. La API incluye una **capa compatible
Docker v1.40** + la capa libpod. Formas de que el controller la use:

| Método | URI en la Cloud | Cuándo usarlo | Seguridad |
|---|---|---|---|
| Socket Unix local | `unix:///run/podman/podman.sock` | Solo si Jenkins y Podman están en la **misma máquina** | Alta |
| **TCP + mTLS** | `tcp://192.168.122.31:2376` + credencial X.509 | Remoto, producción | Alta (requiere certs) |
| **TCP sin TLS** | `tcp://192.168.122.31:2375` | Solo depuración en red aislada | **Baja** (ver aviso) |
| Túnel SSH | `unix://` reenviado (o `tcp://127.0.0.1:2376`) | Remoto, sin abrir TCP | Alta |

> ⚠️ **La documentación de Podman es explícita**: la API "concede acceso
> completo a toda la funcionalidad de Podman y por tanto permite ejecución
> arbitraria de código como el usuario que corre la API". Recomienda **no
> exponerla por red sin mTLS** y, si se necesita acceso remoto, **reenviar el
> socket por SSH**.

**En el laboratorio se usa TCP + mTLS** (`tcp://192.168.122.31:2376`, ver
[ADR-0005](./adr/0005-mtls-api-podman.md)). En el `podman-host` se levanta un
servicio systemd aparte (`podman-tcp.service`) que ejecuta:

```ini
[Service]
ExecStart=/usr/bin/podman system service --time=0 \
  --tls-cert=/etc/podman/tls/server-cert.pem \
  --tls-key=/etc/podman/tls/server-key.pem \
  --tls-client-ca=/etc/podman/tls/ca.pem \
  tcp://0.0.0.0:2376
```

El flag `--tls-client-ca` es lo que hace la conexión **mutua (mTLS)**: además
de que el cliente valide el servidor, el servidor **exige** al cliente un
certificado firmado por la CA del laboratorio. En Jenkins, la Cloud referencia
la credencial X.509 (`DockerServerCredentials`) con el material de cliente.

Se usa un servicio **aparte** (y no se overridea `podman.socket`) para
mantener a la vez el socket Unix rootful `/run/podman/podman.sock`, que montan
los contenedores-agente que empaquetan imágenes (`podman system service` no
admite más de un socket por proceso).

**Alternativa — túnel SSH:** desde el controller, mantener una tubería al
socket rootful del podman-host y apuntar la Cloud a `unix://` reenviado. Útil
si prefieres no exponer ningún puerto TCP; en ese caso puedes desactivar la
PKI con `podman_tls_enabled: false`.

### 4.2 Rootful vs rootless: por qué rootful en este modelo

En el modelo Cloud, quien corre la **API** decide en qué contexto corren los
contenedores y qué "ve" el daemon (store, secrets, permisos). Comparativa:

| Aspecto | **Rootful** (API = root) | Rootless (API = jenkins) |
|---|---|---|
| **Podman Secrets (3 drivers)** | Ve el **store de sistema** (donde los crea el rol) → `--secret` en plantillas funciona directo | Ve el store del usuario jenkins; los secrets de sistema **NO** se ven |
| **Permisos workspace/cachés** | Fácil (root del contenedor = root real) | Requiere `user: 0` (rootless mapea a jenkins) o `--userns=keep-id`, que el plugin **no soporta** |
| **Semántica para el plugin** | Es lo más parecido a un daemon Docker (rootful), para el que se diseñó `docker-plugin` | Más casos límite en la API |
| **Aislamiento** | Menor (los agentes pueden ser root) | Mayor (user namespaces) |
| **Coherencia con el lab Podman-Host** | Diverge (aquel es rootless) | Coherente con el discurso rootless |

**Decisión: rootful.** Es lo que mejor encaja con el `docker-plugin` y con los
**Podman Secrets** del laboratorio, y elimina la fricción de permisos. El coste
es menos aislamiento, aceptable en la red aislada del lab. El lab **Podman-Host
se mantiene rootless** (ahí `--userns=keep-id` sí funciona a nivel de pipeline),
de modo que los dos labs ilustran los dos enfoques.

### 4.3 El resto ya lo tienes

- `podman.socket` **rootful** → `/run/podman/podman.sock`.
- La red de libvirt permite controller→podman-host.

### 4.4 Certificados TLS (la PKI del laboratorio)

El laboratorio **genera su propia PKI** en el `podman-host` (rol
`podman_tls`), para que la API quede protegida por mTLS sin depender de una CA
externa. Se crean cuatro piezas:

| Fichero | Quién lo usa | Contenido |
|---|---|---|
| `ca.pem` / `ca-key.pem` | CA del laboratorio | Firme de servidor y cliente. **La clave de la CA nunca sale del host** |
| `server-cert.pem` / `server-key.pem` | `podman-tcp.service` | `CN=podman-cloud-host`, con SAN de IP y DNS del host |
| `client-cert.pem` / `client-key.pem` | Jenkins Controller | `CN=jenkins-controller` |

El flujo es:

1. **Fase 2** del `site.yml` genera la PKI en el `podman-host`
   (`/etc/podman/tls`, `0700`, claves `0600`).
2. **Fase 3** (rol `jenkins_controller`) copia **solo el material de cliente**
   (`ca.pem`, `client-cert.pem`, `client-key.pem`) a
   `/etc/jenkins/podman-tls` (propiedad de `jenkins`, `0700`). Se lee del
   `podman-host` con `slurp` delegado; el material no se versiona.
3. El script `init.groovy.d/00-create-docker-credentials.groovy` crea en
   Jenkins la credencial `DockerServerCredentials` (`podman-cloud-tls`) a
   partir de esos ficheros.
4. `create-cloud.groovy` monta la Cloud con
   `DockerServerEndpoint("tcp://…:2376", "podman-cloud-tls")`.

> **Rotación:** para regenerar la PKI, borra `/etc/podman/tls` en el
> `podman-host` y vuelve a ejecutar `deploy.sh` (las tareas usan `creates:`).
> Si cambia la IP del `podman-host`, hay que regenerarla también, porque el SAN
> del certificado de servidor la incluye.

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

Todas las imágenes-agente del laboratorio parten de la **misma base**:

```dockerfile
FROM docker.io/jenkins/inbound-agent:latest-rhel-ubi9-jdk21
```

- Es **UBI 9** (RHEL 9), coherente con AlmaLinux 9 del resto del laboratorio.
- Trae el **runtime del agente + JDK 21**, alineado con el JDK del controller.
- Sobre esa base se añade la toolchain con `dnf` (se usa
  `--disableplugin=subscription-manager` porque la imagen UBI lo trae y falla):

```dockerfile
# agent-maven-jdk17: agente (JDK21) + Maven + toolchain JDK17
FROM docker.io/jenkins/inbound-agent:latest-rhel-ubi9-jdk21

USER root
RUN dnf install -y --disableplugin=subscription-manager \
      --setopt=install_weak_deps=0 --setopt=tsflags=nodocs \
      maven java-17-openjdk-devel git \
 && dnf clean --disableplugin=subscription-manager all
USER jenkins
```

```dockerfile
# agent-node20: agente (JDK21) + Node 20
FROM docker.io/jenkins/inbound-agent:latest-rhel-ubi9-jdk21

USER root
RUN dnf install -y --disableplugin=subscription-manager \
      --setopt=install_weak_deps=0 --setopt=tsflags=nodocs \
      ca-certificates git \
 && curl -fsSL https://rpm.nodesource.com/setup_20.x | bash - \
 && dnf install -y --disableplugin=subscription-manager nodejs \
 && dnf clean --disableplugin=subscription-manager all
USER jenkins
```

> **Ojo con `curl` en UBI 9:** la base ya trae `curl-minimal` (que provee el
> binario `curl`). Si en el `dnf install` pides el paquete `curl`, entra en
> conflicto y el build falla. No lo instales: usa el `curl` ya presente.

> Fíjate en el matiz: la imagen del agente **siempre lleva un JDK** (para el
> propio agente), aunque tu proyecto sea Node. En Podman-Host, la imagen de
> Node no necesitaba Java.
>
> El **JDK del agente** (21) es independiente del **JDK de compilación**: si el
> build necesita otra versión (p. ej. 17 con Maven), se añade como *toolchain*
> en la misma imagen. Ver sección 5.3 y ADR-0006.

### 5.2 Agente que además construye imágenes (Podman-out-of-Podman)

Si el stage empaqueta imágenes, el contenedor-agente necesita la **API de
Podman del host** dentro:

- montar el socket **rootful** `/run/podman/podman.sock` en la plantilla,
- `--security-opt label=disable` (SELinux, ver ADR-011 de Podman-Host),
- `CONTAINER_HOST=unix:///run/podman/podman.sock` en el entorno de la plantilla.

En Podman-Cloud esto se pone en la **plantilla**, no en el pipeline. Además
puedes activar **"Expose DOCKER_HOST"** en la Cloud.

La imagen `agent-podman` de este laboratorio añade, además de Podman, las
herramientas **`kubectl`, `kubectx` y `kubens`** para operar contra clústeres
Kubernetes desde el pipeline. El **kubeconfig no se hornea** en la imagen: se
inyecta en tiempo de build con un *Managed Config File* (ver sección 10).

### 5.3 JDK del agente vs JDK de compilación (Maven Toolchains)

Son dos cosas distintas y conviene no mezclarlas:

| | Quién lo usa | En este laboratorio |
|---|---|---|
| **JDK del agente** | El runtime del agente Jenkins (y por tanto Maven) | **JDK 21** (base de la imagen), alineado con el controller |
| **JDK de compilación** | `javac` que compila la aplicación | **JDK 17** (el que pide el backend), aportado como *toolchain* |

**Maven es agnóstico al JDK**: `mvn` corre sobre el JDK 21 del agente, y es la
**toolchain** la que decide con qué `javac` se compila. Así una sola imagen de
agente (JDK 21) puede compilar para varias versiones de Java sin duplicar
imágenes.

**1. En la imagen** (`agent-maven-jdk17`) se instala el JDK 17 y se declara en
`/opt/toolchains/toolchains.xml`:

```xml
<toolchains>
  <toolchain>
    <type>jdk</type>
    <provides>
      <version>17</version>
      <vendor>openjdk</vendor>
    </provides>
    <configuration>
      <jdkHome>/usr/lib/jvm/java-17-openjdk</jdkHome>
    </configuration>
  </toolchain>
</toolchains>
```

**2. En el `pom.xml`** se activa el plugin que selecciona la toolchain:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-toolchains-plugin</artifactId>
  <version>3.2.0</version>
  <executions>
    <execution>
      <goals><goal>toolchain</goal></goals>
    </execution>
  </executions>
  <configuration>
    <toolchains>
      <jdk><version>17</version></jdk>
    </toolchains>
  </configuration>
</plugin>
```

**3. En el pipeline** se pasa el fichero de toolchains a Maven:

```groovy
sh '''
  cd backend
  mvn -t /opt/toolchains/toolchains.xml \
      -Dmaven.repo.local=/cache/.m2/repository \
      -B -ntp clean package
'''
```

> **Ojo:** con `maven-toolchains-plugin`, si se ejecuta `mvn` **sin** `-t` el
> build falla con `Cannot find matching toolchain definitions`. Es intencionado
> (fallar rápido y claro) y hay que tenerlo en cuenta al probar a mano (ver
> `samples/reference-app/README.md`).
>
> **Alternativa sin toolchains:** compilar sobre el JDK 21 con
> `-Dmaven.compiler.release=17` (genera bytecode/API de 17). Es más simple, pero
> aquí se usa toolchains a propósito para enseñar el mecanismo. Ver ADR-0006.

---

## 6. Configurar la Cloud y las plantillas

### 6.1 Por UI

`Manage Jenkins → Clouds → Add a new cloud → Docker`:

> Los nombres de los campos son las etiquetas literales del plugin
> (`docker-plugin`), que usa "Docker" en su interfaz aunque aquí el motor sea
> **Podman**. Cuando en esta guía hablemos de "host", nos referimos al
> **Podman Host**.

- **Docker Cloud details**
  - *Docker Host URI*: `tcp://192.168.122.31:2376` (o `unix://...`; sin TLS
    solo para depurar).
  - *Server credentials*: la credencial X.509 (`podman-cloud-tls`) cuando usas
    mTLS (es lo recomendado y lo que hace este laboratorio).
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
      name: "podman-cloud"
      containerCap: 10
      # API rootful con mTLS: URI en :2376 + credencial X.509
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
            user: "0"
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
            jenkinsUrl: "http://192.168.122.30:8080/"
            user: "0"
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
            jenkinsUrl: "http://192.168.122.30:8080/"
            user: "0"
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
stage('Backend')   { agent { label 'maven-jdk17' }   ; steps { sh 'cd backend && mvn -t /opt/toolchains/toolchains.xml -B clean package' } }
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
                    mvn -t /opt/toolchains/toolchains.xml \
                        -B -ntp -Dmaven.repo.local=/cache/.m2/repository clean package
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

### 10.1 Consumo de secrets y kubeconfig en el pipeline

El pipeline real (`jenkins-config/jobs/reference-pipeline.groovy`) añade una
fase que ilustra dos buenas prácticas sobre el agente `podman-build`:

```groovy
stage('Secrets y Kubernetes') {
    agent { label 'podman-build' }
    steps {
        // El kubeconfig se inyecta como fichero temporal y se exporta la
        // variable KUBECONFIG; NO se hornea en la imagen del agente.
        configFileProvider([configFile(fileId: 'kubeconfig-demo',
                                        variable: 'KUBECONFIG')]) {
            sh '''
                # Consumo de un Podman Secret: el motor lo monta como
                # fichero en /run/secrets/<nombre>, sin pasarlo por argv.
                podman run --rm --secret api_token_prod_file \
                    docker.io/library/alpine:3.20 \
                    sh -c 'cat /run/secrets/api_token_prod_file'

                # Herramientas Kubernetes con el kubeconfig inyectado:
                kubectl config get-contexts
                kubectx
            '''
        }
    }
}
```

Puntos clave:

- **Secrets**: los Podman Secrets viven en el store **rootful** del
  `podman-host`; como el agente monta su socket, `podman run --secret` los ve.
  El pipeline **no recibe el valor**: el motor lo monta como fichero.
- **kubeconfig**: se define una sola vez como *Managed Config File*
  (`configFileProvider`) y el pipeline lo recibe como variable `KUBECONFIG`.
  Rotar credenciales del clúster no obliga a reconstruir la imagen del agente.
  El `kubeconfig-demo` del laboratorio es un **ejemplo sin credenciales
  reales**; sustitúyelo por el de tu clúster (o crea un *Secret file* con el
  kubeconfig real y cambia el `fileId`).

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

1. Exponer la API de Podman con **mTLS** (o túnel SSH). En este laboratorio ya
   viene así: PKI propia + `podman-tcp.service` en `:2376` (ver ADR-0005).
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
8. Endurecer: TLS en la API (ya por defecto), `containerCap`, *idle timeout*
   razonable, limpieza de contenedores huérfanos.

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
- **`PKIX path building failed` / `certificate signed by unknown authority`**:
  la credencial X.509 no se creó o la Cloud no la referencia. Revisa que
  `00-create-docker-credentials.groovy` aparece como `DOCKER_CREDENTIAL_CREATED`
  en el log de Jenkins y que `JENKINS_PODMAN_CLOUD_TLS_CREDENTIALS` está
  definida (ver ADR-0005). Si cambió la IP del `podman-host`, regenera la PKI
  (el SAN del certificado de servidor incluye la IP).
- **`no such file or directory` al leer `/etc/jenkins/podman-tls/…`**: Ansible
  no copió el material de cliente (Fase 3 antes que Fase 2) o el usuario
  jenkins no puede leerlo. Revisa permisos (`0700` dir, `0600` key).
- **`kubectl` responde `no configuration` / `error: current-context`**: el
  pipeline no inyectó el kubeconfig. Comprueba que el Managed File
  `kubeconfig-demo` existe (Config File Provider) y que el stage envuelve el
  `sh` en `configFileProvider([configFile(... variable: 'KUBECONFIG')])`.
- **El secret no aparece en `/run/secrets`**: el agente `podman-build` no monta
  el socket rootful (revisa los `mounts` de la plantilla) o el secret no existe
  (`create_example_secrets: true` en `vars.yml`).

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
- ADRs del lab Podman-Cloud:
  [ADR-0001](./adr/0001-rootful-api-y-motor.md),
  [ADR-0002](./adr/0002-imagenes-agente-hibridas.md),
  [ADR-0003](./adr/0003-api-tcp-sin-tls.md) (sustituido por ADR-0005),
  [ADR-0004](./adr/0004-aprovisionamiento-via-cloud.md),
  [ADR-0005](./adr/0005-mtls-api-podman.md) (mTLS),
  [ADR-0006](./adr/0006-jdk-agente-vs-jdk-compilacion-toolchains.md) (JDK del agente vs JDK de compilación).
- ADRs relacionados (del lab Podman-Host):
  [ADR-005](../../Podman-Host/docs/adr/0005-podman-secrets-como-root.md) (secrets root),
  [ADR-009](../../Podman-Host/docs/adr/0009-reference-app-sin-scm.md) (app de referencia sin SCM),
  [ADR-010](../../Podman-Host/docs/adr/0010-habilitar-podman-socket.md) (podman.socket),
  [ADR-011](../../Podman-Host/docs/adr/0011-security-opt-label-disable-podman-socket.md) (`--security-opt label=disable`).

---

*Licencia: [CC BY 4.0](../../guides/LICENSE). © 2026 Cloudsdoers.*
