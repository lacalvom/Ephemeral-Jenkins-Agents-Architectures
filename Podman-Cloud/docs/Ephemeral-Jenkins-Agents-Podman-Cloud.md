# Arquitectura de Agentes Efímeros con Podman y Jenkins — Modelo Podman-Cloud

> **Modelo: `Podman-Cloud`.** Implementado con el plugin `docker-plugin`
> (Cloud + Docker Agent Templates): Jenkins aprovisiona **contenedores-agente
> bajo demanda** sobre un Podman Host, seleccionables por *label*.

> Documento complementario a [`Ephemeral-Jenkins-Agents-Podman-host.md`](../../Podman-Host/docs/Ephemeral-Jenkins-Agents-Podman-host.md).
> Describe el modelo en el que la infraestructura se declara como una **Cloud
> (Docker plugin) con plantillas de agente**, en lugar de crear contenedores
> desde el propio código del pipeline con `agent { docker { ... } }`.

> **Alcance.** El documento aborda la viabilidad del modelo, sus implicaciones,
> la gestión de workspace, cachés y selección de agente, el comportamiento
> interno de Jenkins y el reparto de responsabilidades entre infraestructura y
> pipeline.

---

## 1. Resumen

El modelo es **plenamente viable**: consiste en configurar la definición de
Cloud (Jenkins → *Manage* → *Clouds* → *Docker*) apuntando a la API de Podman
del `podman-host`, con una **plantilla de agente por imagen** de toolchain. Es
el modelo que implementa el plugin **`docker-plugin`** (docker-java, no la CLI)
y es conceptualmente idéntico al plugin de Kubernetes: Jenkins solicita un
agente y el proveedor crea un contenedor.

La diferencia de fondo respecto al modelo Podman-Host es la siguiente:

| | Podman-Host (`agent { docker {} }`) | Podman-Cloud (plugin `docker-plugin`) |
|---|---|---|
| Quién crea el contenedor | El código del pipeline, en cada `stage` | Jenkins (el *cloud provider*), al solicitar un agente con cierto *label* |
| Qué se ejecuta en el contenedor | Los comandos del stage (`sh`) vía `docker exec` | Un agente Jenkins (JNLP/SSH) que ejecuta el build |
| ¿La imagen necesita Java + agente? | No (sirve cualquier imagen) | Sí (JDK + `jenkins/inbound-agent` o sshd + JDK) |
| Unidad de ejecución | Un contenedor por stage, efímero dentro de un build | Un contenedor por build (o por executor), que es un nodo |
| Plugin | `docker-workflow` | `docker-plugin` (Cloud + Docker Agent Templates) |

La consecuencia práctica principal: en Podman-Cloud las imágenes de toolchain
puras (`ubi9/openjdk-17`, `ubi9/nodejs-20`, `ubi9/podman`) no son válidas tal
cual, porque no son agentes Jenkins. Es necesario construir imágenes
**híbridas** (toolchain + agente). En Podman-Host eso no es necesario, y suele
ser el motivo principal por el que se opta por `agent { docker {} }`.

El resto de aspectos (workspace, cachés, socket, selección) es viable, pero
cambia *dónde* se configura: en Podman-Cloud casi todo reside en la **plantilla
del agente** (infraestructura), no en el código del pipeline.

---

## 2. Modelos de agentes efímeros en Jenkins

1. **Podman-Host — Pipeline + `docker-workflow`**. Es el modelo del laboratorio
   Podman-Host. El pipeline usa
   `agent { docker { image '...'; reuseNode true; args '...' } }`. El plugin
   ejecuta `docker run` y, a continuación, **`docker exec`** para cada paso
   dentro del contenedor. No hay agente dentro del contenedor: la lógica reside
   en el nodo que hospeda el pipeline.

2. **Podman-Cloud — `docker-plugin`** (la definición de Cloud). Se configura
   una Cloud apuntando a la API de Podman de un Podman Host y una o más
   *Docker Agent Templates*. Jenkins aprovisiona contenedores **como
   nodos/agentes** bajo demanda, según *labels*.

3. **Jenkins-Kubernetes — Cloud de Kubernetes**. Equivalente para clústeres
   Kubernetes: el mismo concepto que Podman-Cloud, pero el *pool* es el
   clúster. Comparte casi toda la lógica con Podman-Cloud y este repositorio
   lo aborda en su propio laboratorio (`Jenkins-Kubernetes/`).

El `docker-plugin` y el `docker-workflow` son **plugins distintos**, aunque los
nombres induzcan a confusión (lo advierte el propio README del `docker-plugin`).
Instalar `docker-plugin` no elimina el soporte de `agent { docker {} }`: ambos
pueden convivir.

---

## 3. Comportamiento interno de Jenkins en cada modelo

### 3.1 Podman-Host (`docker-workflow`)

Para cada bloque `agent { docker { ... } }`:

1. El nodo (el `podman-host`, agente JNLP conectado) ejecuta `docker inspect`
   o `docker pull` de la imagen.
2. `docker run -t -d -u <uid> <args> -w <workspace> -v <workspace>:<workspace> <image> cat`
   arranca el contenedor en espera.
3. `docker exec` para cada `sh` definido en el `stage`.
4. `docker stop` y `docker rm` al finalizar el bloque.

Aspectos clave:
- El **workspace reside en el host** (el agente JNLP) y se **inyecta** en el
  contenedor con `-v` (esto es lo que aporta `reuseNode true`).
- Por ello funciona con cualquier imagen, aunque no incluya Java ni agente.
- Los `args` del pipeline son literalmente argumentos de `docker run` (ahí van
  `--userns=keep-id`, `-v maven-cache:...`, etc.).

### 3.2 Podman-Cloud

Cuando llega un build que requiere el label `maven-jdk17` y no hay agente
libre, el *cloud provider*:

1. Llama a la **API Docker/Podman** (por HTTP/TCP o por el socket) para
   `POST /containers/create` y `/start` con la configuración de la plantilla.
2. Inyecta la maquinaria del agente: nombre, secret, URL del controller y el
   *launch method* (JNLP o SSH). El contenedor arranca y **conecta de vuelta**
   al controller.
3. Jenkins lo registra como un `DockerComputer` (un nodo más) con un executor
   (habitualmente) y programa el build en él.
4. Al quedar inactivo, el plugin detiene y **elimina** el contenedor
   (retención / *idle timeout*).

Aspectos clave:
- El **workspace reside dentro del contenedor**
  (`<remoteFs>/workspace/<job>`), salvo que se monte como volumen del host.
- La **imagen debe ser un agente Jenkins** (JDK + inbound-agent/sshd).
- El plugin habla con la API (docker-java); no necesita la CLI `docker` en
  ningún punto.
- Existe la opción **"Expose DOCKER_HOST"** en la Cloud: al activarla, el
  plugin exporta `DOCKER_HOST` dentro del contenedor apuntando al host Docker
  usado por la Cloud (útil para construir imágenes desde el agente).

---

## 4. Infraestructura necesaria (Podman-Cloud)

### 4.1 API y motor de Podman: rootful frente a rootless

En el modelo Cloud, quien ejecuta la **API** determina el contexto en el que
corren los contenedores y qué "ve" el motor (store, secrets, permisos).
Comparativa:

| Aspecto | Rootful (API = root) | Rootless (API = jenkins) |
|---|---|---|
| **Podman Secrets (3 drivers)** | Ve el store de sistema (donde los crea el rol) → `--secret` en plantillas funciona directamente | Ve el store del usuario jenkins; los secrets de sistema no se ven |
| **Permisos workspace/cachés** | Sencillo (root del contenedor = root real) | Requiere `user: 0` (rootless mapea a jenkins) o `--userns=keep-id`, que el plugin no soporta |
| **Semántica para el plugin** | Es lo más próximo a un daemon Docker (rootful), para el que se diseñó `docker-plugin` | Más casos límite en la API |
| **Aislamiento** | Menor (los agentes pueden ser root) | Mayor (*user namespaces*) |
| **Coherencia con el laboratorio Podman-Host** | Diverge (aquel es rootless) | Coherente con el enfoque rootless |

**Decisión: rootful.** Es la opción que mejor encaja con `docker-plugin` y con
los **Podman Secrets** del laboratorio, y elimina la fricción de permisos. El
coste es un menor aislamiento, aceptable en la red aislada del laboratorio. El
laboratorio **Podman-Host se mantiene rootless** (`--userns=keep-id` funciona a
nivel de pipeline), de modo que ambos laboratorios ilustran los dos enfoques.
Ver [ADR-0001](./adr/0001-rootful-api-y-motor.md).

El socket Unix rootful es `/run/podman/podman.sock`. Lo montan los
contenedores-agente que empaquetan imágenes.

### 4.2 Exposición de la API por red: TCP con mTLS

La API de Podman incorpora una **capa compatible con Docker v1.40** más la capa
libpod. El controller (otra máquina) necesita alcanzarla, y la API concede
control total (ejecución arbitraria como el usuario que la ejecuta), por lo que
su exposición requiere protección. Formas de uso:

| Método | URI en la Cloud | Cuándo usarlo | Seguridad |
|---|---|---|---|
| Socket Unix local | `unix:///run/podman/podman.sock` | Solo si Jenkins y Podman están en la misma máquina | Alta |
| **TCP + mTLS** | `tcp://192.168.122.31:2376` + credencial X.509 | Remoto, producción | Alta (requiere certificados) |
| TCP sin TLS | `tcp://192.168.122.31:2375` | Solo depuración en red aislada | Baja |
| Túnel SSH | `unix://` reenviado (o `tcp://127.0.0.1:2376`) | Remoto, sin abrir TCP | Alta |

> La documentación de Podman es explícita: la API "concede acceso completo a
> toda la funcionalidad de Podman y, por tanto, permite la ejecución arbitraria
> de código como el usuario que la ejecuta". Recomienda **no exponerla por red
> sin mTLS** y, cuando se necesita acceso remoto, **reenviar el socket por
> SSH**.

**En el laboratorio se emplea TCP + mTLS** (`tcp://192.168.122.31:2376`; ver
[ADR-0005](./adr/0005-mtls-api-podman.md)). En el `podman-host` se levanta un
servicio systemd independiente (`podman-tcp.service`):

```ini
[Service]
ExecStart=/usr/bin/podman system service --time=0 \
  --tls-cert=/etc/podman/tls/server-cert.pem \
  --tls-key=/etc/podman/tls/server-key.pem \
  --tls-client-ca=/etc/podman/tls/ca.pem \
  tcp://0.0.0.0:2376
```

El flag `--tls-client-ca` establece la conexión **mutua (mTLS)**: además de que
el cliente valide el servidor, el servidor **exige** al cliente un certificado
firmado por la CA del laboratorio. En Jenkins, la Cloud referencia la credencial
X.509 (`DockerServerCredentials`) con el material de cliente.

El servicio es **independiente** (no se sobrescribe `podman.socket`) para
conservar a la vez el socket Unix rootful `/run/podman/podman.sock`, que montan
los contenedores-agente que empaquetan imágenes (`podman system service` no
admite más de un socket por proceso).

**PKI del laboratorio.** La PKI se genera en el `podman-host` (rol `podman_tls`)
para no depender de una CA externa. Consta de:

| Fichero | Uso | Contenido |
|---|---|---|
| `ca.pem` / `ca-key.pem` | CA del laboratorio | Firma de servidor y cliente. La clave de la CA nunca sale del host |
| `server-cert.pem` / `server-key.pem` | `podman-tcp.service` | `CN=podman-cloud-host`, con SAN de IP y DNS del host |
| `client-cert.pem` / `client-key.pem` | Jenkins Controller | `CN=jenkins-controller` |

Flujo de aplicación:

1. La fase de PKI del `site.yml` genera la PKI en el `podman-host`
   (`/etc/podman/tls`, `0700`, claves `0600`).
2. El rol `jenkins_controller` copia **solo el material de cliente**
   (`ca.pem`, `client-cert.pem`, `client-key.pem`) a `/etc/jenkins/podman-tls`
   (propiedad de `jenkins`, `0700`). Se lee del `podman-host` con `slurp`
   delegado; el material no se versiona.
3. `init.groovy.d/00-create-docker-credentials.groovy` crea en Jenkins la
   credencial `DockerServerCredentials` (`podman-cloud-tls`).
4. `create-cloud.groovy` configura la Cloud con
   `DockerServerEndpoint("tcp://…:2376", "podman-cloud-tls")`.

> **Rotación:** para regenerar la PKI basta con eliminar `/etc/podman/tls` en el
> `podman-host` y reejecutar el playbook (las tareas usan `creates:`). Si cambia
> la IP del `podman-host`, también debe regenerarse, porque el SAN del
> certificado de servidor incluye esa IP.

**Alternativa — túnel SSH.** Desde el controller puede mantenerse una tubería
al socket rootful del `podman-host` y apuntar la Cloud al `unix://` reenviado.
Es útil cuando no se desea exponer ningún puerto TCP; en ese caso la PKI puede
desactivarse con `podman_tls_enabled: false`.

---

## 5. Imágenes de agente: requisitos y construcción

En Podman-Cloud cada imagen debe poder ejecutar un agente Jenkins y conectarse
al controller. Existen tres *launch methods*:

| Launch method | Requisito de la imagen | Base recomendada |
|---|---|---|
| **JNLP / inbound** (recomendado) | JDK + `agent.jar` (lo inyecta el plugin) | `jenkins/inbound-agent` |
| SSH | `sshd` + JDK | `jenkins/ssh-agent` |
| Attached | JDK | `jenkins/agent` |

El controller debe ser **alcanzable desde el contenedor** (para JNLP, WebSocket
sobre el puerto HTTP 8080 o el puerto 50000).

### 5.1 Imágenes híbridas (toolchain + agente)

Todas las imágenes-agente del laboratorio parten de la **misma base**:

```dockerfile
FROM docker.io/jenkins/inbound-agent:latest-rhel-ubi9-jdk21
```

- Es UBI 9 (RHEL 9), coherente con AlmaLinux 9 del resto del laboratorio.
- Incluye el runtime del agente y JDK 21, alineado con el JDK del controller.
- Sobre esa base se añade la toolchain con `dnf` (se usa
  `--disableplugin=subscription-manager` porque la imagen UBI lo incorpora y
  falla en caso contrario):

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

> **`curl` en UBI 9.** La base ya incluye `curl-minimal`, que provee el binario
> `curl`. Solicitar el paquete `curl` en `dnf install` provoca un conflicto y el
> build falla; debe utilizarse el `curl` ya presente.

> Cabe destacar que la imagen del agente **siempre incorpora un JDK** (para el
> propio agente), aunque el proyecto sea Node. En Podman-Host, la imagen de
> Node no requería Java.
>
> El JDK del agente (21) es independiente del JDK de compilación: si el build
> requiere otra versión (por ejemplo 17 con Maven), se aporta como *toolchain*
> en la misma imagen. Ver sección 5.3 y ADR-0006.

### 5.2 Agente que además construye imágenes (Podman-out-of-Podman)

Cuando un stage empaqueta imágenes, el contenedor-agente necesita acceso a la
API de Podman del host:

- montar el socket rootful `/run/podman/podman.sock` en la plantilla,
- `--security-opt label=disable` (SELinux; ver ADR-011 de Podman-Host),
- `CONTAINER_HOST=unix:///run/podman/podman.sock` en el entorno de la plantilla.

En Podman-Cloud esto se configura en la **plantilla**, no en el pipeline.
También puede activarse **"Expose DOCKER_HOST"** en la Cloud.

> **SELinux y *bind mounts*.** El workspace se monta como *bind* del host. Con
> SELinux en `enforcing` (AlmaLinux 9), el contenedor no puede escribir en él
> salvo que se relabele; el `docker-plugin` no puede expresar `:z`, por lo que
> **todas las plantillas** (no solo la de build) llevan
> `securityOpts = "label=disable"`. Sin ello, el primer build falla con
> `java.nio.file.AccessDeniedException: …/workspace/<job>@tmp`. Ver ADR-011 del
> laboratorio Podman-Host y la sección de resolución de problemas.

La imagen `agent-podman` añade, además de Podman, las herramientas `kubectl`,
`kubectx` y `kubens` para operar contra clústeres Kubernetes desde el pipeline.
El kubeconfig no se incorpora a la imagen: se inyecta en tiempo de build con un
*Managed Config File* (ver sección 11).

### 5.3 JDK del agente frente a JDK de compilación (Maven Toolchains)

Son dos conceptos distintos que conviene no mezclar:

| | Uso | En este laboratorio |
|---|---|---|
| JDK del agente | Runtime del agente Jenkins (y, por tanto, Maven) | JDK 21 (base de la imagen), alineado con el controller |
| JDK de compilación | `javac` que compila la aplicación | JDK 17 (el del backend), aportado como *toolchain* |

**Maven es agnóstico al JDK**: `mvn` se ejecuta sobre el JDK 21 del agente y es
la *toolchain* la que determina con qué `javac` se compila. Así, una única
imagen de agente (JDK 21) puede compilar para varias versiones de Java sin
duplicar imágenes.

1. En la imagen (`agent-maven-jdk17`) se instala el JDK 17 y se declara en
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

2. En el `pom.xml` se activa el plugin que selecciona la toolchain:

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

3. En el pipeline se pasa el fichero de toolchains a Maven:

```groovy
sh '''
  cd backend
  mvn -t /opt/toolchains/toolchains.xml \
      -Dmaven.repo.local=/cache/.m2/repository \
      -B -ntp clean package
'''
```

> Con `maven-toolchains-plugin`, ejecutar `mvn` sin `-t` hace fallar el build
> con `Cannot find matching toolchain definitions`. Es un fallo intencionado
> (rápido y explícito) y debe tenerse en cuenta al probar manualmente (ver
> `samples/reference-app/README.md`).
>
> **Alternativa sin toolchains:** compilar sobre el JDK 21 con
> `-Dmaven.compiler.release=17` (genera bytecode/API de 17). Es más simple, pero
> el laboratorio usa toolchains deliberadamente para ilustrar el mecanismo. Ver
> ADR-0006.

---

## 6. Configuración de la Cloud y de las plantillas

### 6.1 Configuración por UI

`Manage Jenkins → Clouds → Add a new cloud → Docker`:

> Los nombres de los campos corresponden a las etiquetas literales del plugin
> (`docker-plugin`), que utiliza "Docker" en su interfaz aunque el motor sea
> Podman. En este documento, "host" designa al Podman Host.

- **Docker Cloud details**
  - *Docker Host URI*: `tcp://192.168.122.31:2376` (o `unix://...`; sin TLS solo
    para depuración).
  - *Server credentials*: la credencial X.509 (`podman-cloud-tls`) cuando se usa
    mTLS (opción recomendada y empleada por el laboratorio).
  - *Expose DOCKER_HOST*: útil para construir imágenes.
  - *Container Cap*: número máximo de contenedores simultáneos.
- **Docker Agent templates → Add Docker Template**:
  - *Name*, *Labels*, *Enabled*.
  - *Docker Image* y *Pull strategy* (`Never pull` para imágenes locales).
  - *Remote File System Root* (`remoteFs`).
  - *Connect method* (JNLP / SSH / attached) + *Jenkins URL*.
  - *Volumes* (campo `mounts`/`mountsString`) y *Volumes From*: el campo
    *Volumes* **no** usa la sintaxis `-v host:contenedor`; espera pares
    `key=value` separados por comas, una línea por mount:
    `type=bind,source=/datos/jenkins/pipelines-workspace,destination=/datos/jenkins/pipelines-workspace`
    o `type=volume,source=maven-cache,destination=/cache/.m2`.
  - *User* (**importante**), *Environment*, *Network*: el usuario del
    contenedor se fija aquí (`user: 0`), en el `DockerTemplateBase`. El campo
    `user` del conector JNLP es *legacy* y no cambia el usuario con el que corre
    el contenedor; si se define solo ahí, el agente arranca como el usuario por
    defecto de la imagen (`jenkins`, UID 1000) y no puede escribir en el
    workspace (UID 1100) → `java.nio.file.AccessDeniedException`.
  - *Port bindings*, *Hostname*, *Privileged*, *Extra Hosts*, etc.
  - *Instance Capacity* (contenedores por host), *Idle timeout*.

### 6.2 Configuración por JCasC (referencia)

> **Nota:** los ejemplos de esta sección son **ilustrativos**. El laboratorio
> no aplica JCasC por el problema descrito en la sección 6.3; se incluyen como
> referencia canónica de la configuración de una Cloud.

A partir del ejemplo oficial del `docker-plugin`, adaptado a este laboratorio:

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
        dockerTemplateBase:
          image: "localhost/agent-maven-jdk17:latest"
          pullStrategy: "NEVER"
          user: "0"
          # mountsString: pares key=value, una linea por mount (NO "-v host:dest")
          mountsString: "type=bind,source=/datos/jenkins/pipelines-workspace,destination=/datos/jenkins/pipelines-workspace\ntype=volume,source=maven-cache,destination=/cache/.m2"
          environment:
            - "MAVEN_OPTS=-Dmaven.repo.local=/cache/.m2/repository"
          securityOptsString: "label=disable"

      - name: "node20"
        labelString: "node node20"
        remoteFs: "/datos/jenkins/pipelines-workspace"
        connector:
          jnlp:
            jenkinsUrl: "http://192.168.122.30:8080/"
        dockerTemplateBase:
          image: "localhost/agent-node20:latest"
          pullStrategy: "NEVER"
          user: "0"
          mountsString: "type=bind,source=/datos/jenkins/pipelines-workspace,destination=/datos/jenkins/pipelines-workspace\ntype=volume,source=npm-cache,destination=/cache/.npm"
          securityOptsString: "label=disable"

      - name: "podman-build"
        labelString: "podman-build"
        remoteFs: "/datos/jenkins/pipelines-workspace"
        connector:
          jnlp:
            jenkinsUrl: "http://192.168.122.30:8080/"
        dockerTemplateBase:
          image: "localhost/agent-podman:latest"
          pullStrategy: "NEVER"
          user: "0"
          mountsString: "type=bind,source=/datos/jenkins/pipelines-workspace,destination=/datos/jenkins/pipelines-workspace\ntype=bind,source=/run/podman/podman.sock,destination=/run/podman/podman.sock"
          environment:
            - "CONTAINER_HOST=unix:///run/podman/podman.sock"
          securityOptsString: "label=disable"
```

> Los nombres exactos de los campos JCasC (`pullStrategy`, `environment`,
> `mountsString`, `securityOptsString`, `dockerTemplateBase`, etc.) pueden
> variar según la versión del plugin. La forma fiable de obtenerlos es
> configurar la Cloud por UI y usar **"Export configuration as code"** (plugin
> `configuration-as-code`) o la *script console* con un `export`.

### 6.3 Gestión de la configuración: JCasC frente a `init.groovy.d`

JCasC (*Configuration as Code*) es el mecanismo declarativo recomendado por
Jenkins para describir la configuración (usuarios, clouds, jobs, credenciales)
en YAML y aplicarla al arrancar. Su propósito es que la configuración sea
versionable y reproducible, sin intervención manual en la UI.

> Los ejemplos JCasC de la sección 6.2 tienen valor como referencia, pero **no
> son la configuración que ejecuta este laboratorio**.

**Problema encontrado.** Con Jenkins LTS 2.568.3, JCasC presenta un problema de
**orden de carga de plugins**: al procesar la configuración, el registro de
*configurators* aún no está completo, y la aplicación del YAML falla o se
ignora parcialmente. Es un comportamiento dependiente de la versión; ver
[ADR-0003 del laboratorio Podman-Host](../../Podman-Host/docs/adr/0003-init-groovy-vs-jcasc.md).

**Solución aplicada.** El laboratorio utiliza el **método tradicional con
scripts `init.groovy.d/`**. Jenkins ejecuta esos scripts Groovy al arrancar y
en ellos se crean el usuario admin, la Cloud y sus plantillas, el pipeline y
los *Managed Config Files* de forma imperativa e idempotente. Ventajas: funciona
de forma fiable en la versión LTS empleada y no depende del orden de carga de
JCasC. Inconveniente: la configuración es código Groovy, no YAML declarativo.

**Consideraciones prácticas:**
- Los ejemplos JCasC de la sección 6.2 sirven como plantilla para
  exportar/importar la configuración (por ejemplo, con "Export configuration as
  code") en entornos donde JCasC funcione.
- Para aplicar cambios de configuración en este laboratorio se modifican los
  scripts `init.groovy.d/` y se reejecuta el playbook, que copia los ficheros y
  reinicia Jenkins.

---

## 7. Gestión del workspace

### 7.1 Ubicación del workspace

Jenkins crea, **dentro del contenedor**, `<remoteFs>/workspace/<job>` (y
`<remoteFs>/workspace/<job>@2` para builds concurrentes del mismo job). Como el
contenedor es efímero, por defecto ese workspace se destruye con él.

### 7.2 Compartición del workspace con agentes efímeros

En Podman-Cloud no existe `reuseNode`. La estrategia consiste en **montar un
directorio del host en cada plantilla** y apuntar `remoteFs` a esa ruta:

- Mount en la plantilla (campo `mounts`):
  `type=bind,source=/datos/jenkins/pipelines-workspace,destination=/datos/jenkins/pipelines-workspace`
- `remoteFs`: `/datos/jenkins/pipelines-workspace`

El workspace real queda en el **host** (`podman-host`), como en Podman-Host, y
sobrevive a la destrucción del contenedor. Es la misma ruta que usa el
laboratorio Podman-Host
(`/datos/jenkins/pipelines-workspace/workspace/reference-pipeline`), por lo que
el código preparado por Ansible sigue siendo válido.

### 7.3 Compartición entre stages

En Podman-Host, cada `stage` con `agent { docker }` abre su propio contenedor,
pero todos comparten el workspace del host (vía `reuseNode`), de modo que
`backend/target/` de un stage es visible para el siguiente.

En Podman-Cloud, si cada stage solicita un **label distinto** (Maven → Node →
Podman), cada uno es un **agente distinto** (contenedor distinto). Para
compartir artefactos existen dos opciones:

1. **Montar el mismo directorio del host en las tres plantillas** (como en el
   ejemplo JCasC). Las tres ven `/datos/jenkins/pipelines-workspace` → misma
   ruta y contenido. Es el equivalente funcional a `reuseNode`.
2. Dejar que cada agente use su workspace **interno** y comunicar artefactos
   con `stash`/`unstash` o `archiveArtifacts` (más orientado a cloud, pero más
   verboso y sin persistencia entre builds).

En el laboratorio Podman-Cloud se aplica la opción 1.

### 7.4 Concurrencia y `WorkspaceVolume`

- Dos builds simultáneos del mismo job usan `<job>` y `<job>@2`: con el volumen
  compartido funcionan, pero no deben solaparse (se usa
  `disableConcurrentBuilds()`, como hace el pipeline).
- El `docker-plugin` incorpora el concepto de **`WorkspaceVolume`** (volumen
  específico para el workspace, por ejemplo un *named volume* por agente). Es
  otra vía para persistir el workspace sin montar el directorio del host, pero
  complica la compartición entre plantillas. Para este laboratorio, el
  *bind mount* del host es más simple y consistente.

---

## 8. Cachés de Maven y npm

En Podman-Host las cachés se declaraban en el código del pipeline, dentro de
`args` (`-v maven-cache-${EXECUTOR_NUMBER}:/cache/.m2`). En Podman-Cloud se
trasladan a la plantilla:

```yaml
dockerTemplateBase:
  # campo mountsString: pares key=value, una linea por mount
  mountsString: "type=volume,source=maven-cache,destination=/cache/.m2"
```

y el pipeline se limita a usar la ruta (`/cache/.m2`), sin `-v`.

Consideraciones:
- **Named volume compartido por todos los agentes de ese label**: resulta
  cómodo (la caché se calienta una vez), pero es arriesgado con builds
  concurrentes (dos Maven escribiendo en el mismo `repository/`). Alternativas:
  `disableConcurrentBuilds()`, un volumen por executor, o asumir el riesgo
  (Maven suele tolerarlo; npm en menor medida).
- **Bind mount a un directorio del host**
  (`/datos/jenkins/maven-cache:/cache/.m2`): facilita la inspección y la
  limpieza.
- Un agente Node también incorpora JDK (por el propio agente), por lo que el
  volumen de Maven no interfiere, aunque no se necesita en su plantilla.

### 8.1 Mantenimiento y purga (pruning)

En Podman-Cloud las cachés son *named volumes* en el store rootful del
`podman-host`, por lo que persisten entre builds. Un job de mantenimiento
periódico sigue teniendo sentido, aunque con matices respecto al laboratorio
Podman-Host:

- **Contenedores e imágenes huérfanas.** El propio plugin elimina los
  contenedores-agente inactivos (*idle timeout* / retención), por lo que la
  limpieza de contenedores es secundaria. Sí conviene purgar las imágenes
  *dangling* generadas por los `podman build`.
- **`podman system prune -a` no es adecuado aquí.** Elimina toda imagen no
  usada por un contenedor en ejecución, incluidas `localhost/agent-*:latest`.
  Sin esas imágenes, la Cloud no puede aprovisionar agentes hasta
  reconstruirlas. La purga debe limitarse a imágenes *dangling*
  (`podman image prune -f`) o proteger explícitamente las imágenes-agente.
- **Volúmenes de caché.** `podman system prune` (sin `--volumes`) no elimina los
  *named volumes*, de modo que las cachés se conservan. Para refrescar
  dependencias hay que borrar los volúmenes explícitamente
  (`podman volume rm maven-cache npm-cache`), asumiendo que el siguiente build
  descargará todo de nuevo. Es una decisión de mantenimiento, no un efecto
  colateral de la limpieza de imágenes.
- **No hay nodo permanente** (ADR-0004), por lo que el job de mantenimiento no
  puede ejecutarse "en el host" como en Podman-Host: debe lanzarse sobre un
  agente de la Cloud que monte el socket (label `podman-build`) o directamente
  desde el `podman-host`.

Ejemplo de job programado:

```groovy
// Mantenimiento: se ejecuta en un agente de la Cloud que monta el socket
// rootful, ya que este modelo no dispone de nodo permanente en el host.
pipeline {
    agent { label 'podman-build' }
    triggers { cron('H 2 * * 7') }
    stages {
        stage('Limpieza de contenedores e imagenes dangling') {
            steps {
                sh '''
                    podman container prune -f
                    podman image prune -f          # solo dangling
                    # Evitar "podman system prune -a": borraria localhost/agent-*
                '''
            }
        }
    }
}
```

La purga de los volúmenes de caché, si se desea, se realiza como paso
independiente y deliberado.

---

## 9. Podman Secrets

El laboratorio incorpora **Podman Secrets** para distribuir credenciales y
material sensible (tokens, *kubeconfig*, certificados) a los contenedores
efímeros **sin que el valor aparezca en el pipeline, en la imagen del
agente ni en los logs**. Es el equivalente nativo de Kubernetes Secrets y de
los Jenkins Credentials Store para credenciales consumidas por el motor de
contenedores.

### 9.1 Dónde residen

Los secretos se crean en el **store rootful de Podman del `podman-cloud-host`**
(en `/var/lib/containers/storage/secrets/...` para el driver `file`, y
ubicaciones análogas para los drivers `pass` y `shell`). El agente
`podman-build` los ve porque monta el socket rootful
`/run/podman/podman.sock` (plantilla `podman-build`, sección 5.2). Decisión
del ADR-001: la API y el motor son rootful para que los secretos residan en
el store de sistema y sean visibles por el plugin `docker-plugin`.

La gestión la realiza el rol `podman_secrets_tooling` (Fase 5 de
`site.yml`). Por defecto crea tres secrets de ejemplo, uno por cada driver
configurado:

| Driver    | Secret de ejemplo           | Almacenamiento                                                                                  |
|-----------|------------------------------|---------------------------------------------------------------------------------------------------|
| `file`    | `api_token_prod_file`        | Texto plano en `/var/lib/containers/storage/secrets/` (sin cifrado)                              |
| `pass`    | `api_token_prod_pass`        | Cifrado con GPG (sin passphrase) en `/root/.password-store/`                                    |
| `shell`   | `api_token_prod_shell`       | Cifrado con `sops`+`age` en `/root/.config/podman-secrets-shell-store/` (descifrado al vuelo)    |

El valor de ejemplo para los tres es el mismo (`example_secret_value: "valor-de-ejemplo"`
en `ansible/roles/podman_secrets_tooling/defaults/main.yml`) y **no** representa
credenciales reales: solo existe para validar el flujo.

### 9.2 Drivers disponibles

| Driver   | Cifrado en reposo | Requiere preparación                                                                                       | Caso de uso típico                                       |
|----------|-------------------|------------------------------------------------------------------------------------------------------------|---------------------------------------------------------|
| `file`   | No (texto plano)  | Ninguna                                                                                                    | Entornos aislados con control de acceso estricto al host |
| `pass`   | Sí (GPG)          | El rol genera una clave GPG sin passphrase si no existe                                                     | Auditorías, Zero Trust con tooling GPG clásico           |
| `shell`  | Sí (`age`)        | El rol genera una clave `age` y despliega un script propio (`podman-secret-sops-driver.sh`) que implementa las 4 acciones que exige Podman (`lookup`/`store`/`list`/`delete`) | El más avanzado; el valor nunca se almacena, se descifra bajo demanda |

`shell` sustituye al antiguo `crypta` (ADR-015), incompatible con la glibc de
AlmaLinux 9.

### 9.3 Cómo se consumen desde el pipeline

El agente `podman-build` ve los secretos del store rootful a través del
socket. El patrón recomendado es **dejar que el motor los monte como
ficheros**, sin pasarlos por línea de comandos:

```groovy
stage('Consumir un Podman Secret') {
    agent { label 'podman-build' }
    steps {
        sh '''
            set -euo pipefail
            podman run --rm \
                --secret api_token_prod_file \
                docker.io/library/alpine:3.20 \
                sh -c 'cat /run/secrets/api_token_prod_file'
        '''
    }
}
```

Variantes habituales:

- **`type=env,target=API_TOKEN`** inyecta el valor como variable de entorno
  dentro del contenedor.
- **`type=mount,target=/run/secrets/api_token`** lo monta como fichero
  (forma por defecto si no se especifica `type`).
- Para **`podman build`**, se usa `--secret` con un `--mount=type=secret,...`
  en la línea del `RUN` del Dockerfile, o con
  `--secret id=src,dst=/run/secrets/file` directamente en CLI.

En el pipeline de referencia del laboratorio (sección 11), la **Fase 5
"Secrets y Kubernetes"** itera sobre los tres secrets de ejemplo y los
consume con `podman run --secret`. El valor se imprime desde
`/run/secrets/<nombre>` dentro de un contenedor `alpine`. Esta fase
demuestra además la inyección de un *kubeconfig* mediante Config File
Provider (no se hornea en la imagen).

### 9.4 Buenas prácticas y consideraciones operativas

- **Nunca pasar el valor por argumentos** del pipeline, ni como variable de
  entorno de Jenkins, ni como parámetro en `args`. El motor lo monta
  directamente en `/run/secrets/<nombre>` y lo destruye de memoria al
  terminar el contenedor.
- **Secretos reales fuera del repositorio.** Los nombres de ejemplo
  (`api_token_prod_*`) existen para validar el flujo; las credenciales de
  producción se crean a mano (`podman secret create ...`) o mediante una
  tarea Ansible adicional en `podman_secrets_tooling/tasks/main.yml` que
  no se versiona.
- **No compartir `podman system prune -a`** sobre el `podman-cloud-host`
  con un agente `podman-build` en ejecución: podría borrar imágenes
  referenciadas. Limitar la purga a imágenes *dangling*
  (`podman image prune -f`).
- **Rotación.** Para regenerar los secrets de ejemplo, basta con ejecutar
  `ansible-playbook site.yml` (las tareas son idempotentes y usan
  `skip_existing` para los `podman_secret`). Para rotar credenciales
  reales, se elimina y vuelve a crear el secret con el nuevo valor.
- **Visibilidad.** `podman secret ls` lista los secretos del store y su
  driver. La Fase 5 del pipeline lo muestra en el log del stage.

---

## 10. Selección del agente efímero

- Cada plantilla declara uno o varios **labels**
  (`labelString: "maven maven-jdk17"`).
- El pipeline selecciona con `agent { label 'maven-jdk17' }`.
- Jenkins busca un agente con ese label; si no existe, el *cloud provider* crea
  un contenedor de la plantilla que lo ofrece.
- Un contenedor equivale a un executor (por defecto `mode: EXCLUSIVE`). Para
  permitir varios builds en el mismo contenedor, `mode: NORMAL` con
  `numExecutors > 1`, aunque no es lo habitual con agentes efímeros.
- Distintos stages pueden usar distintos labels:

```groovy
stage('Backend')   { agent { label 'maven-jdk17' }   ; steps { sh 'cd backend && mvn -t /opt/toolchains/toolchains.xml -B clean package' } }
stage('Frontend')  { agent { label 'node20' }         ; steps { sh 'cd frontend && npm ci && npm run build' } }
stage('Imagen')    { agent { label 'podman-build' }   ; steps { sh 'podman build -t app:${BUILD_NUMBER} -f backend/Dockerfile .' } }
```

---

## 11. Pipeline de referencia

El pipeline de referencia del laboratorio reside en
`Podman-Cloud/jenkins-config/jobs/reference-pipeline.groovy` (idéntico a
`samples/reference-pipeline-podman-cloud.groovy`) y reproduce un flujo
completo de compilación y empaquetado, con un agente distinto por stage. La
versión que se muestra a continuación coincide con la implementación canónica
del repositorio.

```groovy
// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Cloudsdoers
// =====================================================================
// reference-pipeline.groovy — Pipeline del laboratorio Podman-Cloud
// =====================================================================
// Modelo "Cloud" (plugin docker-plugin): NO se usa "agent { docker }".
// Cada stage elige un label y el plugin aprovisiona un contenedor-agente
// a partir de la plantilla correspondiente (maven-jdk17 / node20 /
// podman-build) y lo destruye al terminar.
//
// El workspace y las caches los define la PLANTILLA (ver
// create-cloud.groovy), no el pipeline: el workspace esta compartido en
// /datos/jenkins/pipelines-workspace y las caches son named volumes
// montados en /cache/.m2 y /cache/.npm.
//
// El codigo (backend/, frontend/) lo copia Ansible al workspace del job
// (no se usa SCM). Ver jenkins-config/samples/reference-app/.
// =====================================================================

pipeline {
    // Sin agente global: cada stage elige su plantilla por label.
    agent none

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    stages {

        // -----------------------------------------------------------------
        // FASE 1: COMPILACION BACKEND (agente maven-jdk17)
        // -----------------------------------------------------------------
        // El agente corre sobre JDK 21 (el de la imagen), pero el backend
        // se compila con JDK 17: Maven lo selecciona via el toolchains.xml
        // que trae la imagen (maven-toolchains-plugin en el pom). Por eso
        // se pasa "-t /opt/toolchains/toolchains.xml".
        // -----------------------------------------------------------------
        stage('Construccion Backend (Maven)') {
            agent { label 'maven-jdk17' }
            steps {
                configFileProvider([configFile(fileId: 'maven-settings-modern',
                                                variable: 'MAVEN_SETTINGS')]) {
                    sh '''
                        set -euo pipefail
                        mkdir -p /cache/.m2
                        if [ -f backend/pom.xml ]; then
                            cd backend
                            echo "JDK del agente (runtime): $(java -version 2>&1 | head -n1)"
                            mvn -s "$MAVEN_SETTINGS" \
                                -t /opt/toolchains/toolchains.xml \
                                -Dmaven.repo.local=/cache/.m2/repository \
                                -B -ntp \
                                clean package
                        else
                            echo "AVISO: backend/pom.xml no encontrado, saltando build"
                        fi
                    '''
                }
            }
        }

        // -----------------------------------------------------------------
        // FASE 2: COMPILACION FRONTEND (agente node20)
        // -----------------------------------------------------------------
        stage('Construccion Frontend (Node)') {
            agent { label 'node20' }
            steps {
                configFileProvider([configFile(fileId: 'npmrc-frontend',
                                                variable: 'NPMRC_FILE')]) {
                    sh '''
                        set -euo pipefail
                        mkdir -p /cache/.npm
                        export NPM_CONFIG_USERCONFIG="$NPMRC_FILE"
                        npm config set cache /cache/.npm
                        if [ -f frontend/package.json ]; then
                            cd frontend
                            npm install
                            npm run build
                        else
                            echo "AVISO: frontend/package.json no encontrado, saltando build"
                        fi
                    '''
                }
            }
        }

        // -----------------------------------------------------------------
        // FASE 3: EMPAQUETADO IMAGEN BACKEND (agente podman-build)
        // -----------------------------------------------------------------
        stage('Empaquetar Imagen Backend') {
            agent { label 'podman-build' }
            steps {
                sh '''
                    set -euo pipefail
                    if [ -f backend/Dockerfile ]; then
                        podman build --format docker \
                                     -t reference-backend:latest \
                                     -f backend/Dockerfile .
                    else
                        echo "AVISO: backend/Dockerfile no encontrado, saltando build"
                    fi
                '''
            }
        }

        // -----------------------------------------------------------------
        // FASE 4: EMPAQUETADO IMAGEN FRONTEND (agente podman-build)
        // -----------------------------------------------------------------
        stage('Empaquetar Imagen Frontend') {
            agent { label 'podman-build' }
            steps {
                sh '''
                    set -euo pipefail
                    if [ -f frontend/Dockerfile ]; then
                        podman build --format docker \
                                     -t reference-frontend:latest \
                                     -f frontend/Dockerfile .
                    else
                        echo "AVISO: frontend/Dockerfile no encontrado, saltando build"
                    fi
                '''
            }
        }

        // -----------------------------------------------------------------
        // FASE 5: CONSUMO DE SECRETS + HERRAMIENTAS KUBERNETES
        // -----------------------------------------------------------------
        // Ejemplo de buenas practicas en el propio pipeline:
        //   1) consumir Podman Secrets SIN pasarlos por argumentos: el
        //      motor los monta como ficheros en /run/secrets/<nombre>
        //      dentro del contenedor. El laboratorio crea tres secrets
        //      de ejemplo, uno por cada driver disponible
        //      (file / pass / shell), que se iteran a continuacion;
        //   2) inyectar un kubeconfig via Config File Provider (no se
        //      hornea en la imagen) y exportarlo como KUBECONFIG para
        //      kubectl/kubectx/kubens.
        stage('Secrets y Kubernetes') {
            agent { label 'podman-build' }
            steps {
                configFileProvider([configFile(fileId: 'kubeconfig-demo',
                                                variable: 'KUBECONFIG')]) {
                    sh '''
                        set -euo pipefail

                        echo "--- Secrets en el store rootful del podman-host ---"
                        podman secret ls || true

                        echo "--- Consumo de los Podman Secrets del laboratorio ---"
                        # Los 3 secrets se crean en la Fase 5 del playbook
                        # (Podman Secrets a nivel de sistema, rootful).
                        # El motor los monta como ficheros en
                        # /run/secrets/<nombre> dentro del contenedor.
                        for SEC in api_token_prod_file api_token_prod_pass api_token_prod_shell; do
                            if podman secret inspect "$SEC" >/dev/null 2>&1; then
                                echo "Consumiendo $SEC:"
                                podman run --rm --secret "$SEC" \
                                    docker.io/library/alpine:3.20 \
                                    sh -c "echo \"  /run/secrets/$SEC =>\"; cat /run/secrets/$SEC"
                            else
                                echo "AVISO: secret $SEC no encontrado, saltando demo"
                            fi
                        done

                        echo "--- kubeconfig inyectado (kubectl/kubectx/kubens) ---"
                        kubectl config get-contexts || true
                        echo "contexto actual: $(kubectl config current-context 2>/dev/null || echo '(sin cluster real)')"
                        kubectx 2>/dev/null || true
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "Pipeline completado correctamente en build #${env.BUILD_NUMBER}"
        }
        failure {
            echo "Pipeline FALLO en build #${env.BUILD_NUMBER}. Revisa los logs."
        }
    }
}
```

Notas sobre el diseño:

- El usuario del contenedor, los mounts, el socket y las variables de
  entorno se definen en la **plantilla**, no en el pipeline.
- El workspace se comparte porque las tres plantillas montan el mismo
  directorio del host.
- El backend se compila con JDK 17 mediante Maven Toolchains (sección 5.3).
- La Fase 5 demuestra el consumo de los tres Podman Secrets del laboratorio
  y la inyección del *kubeconfig* mediante Config File Provider (sección 9.3
  para el detalle de los secrets).

### 11.1 Consumo de secrets y kubeconfig

La Fase 5 del pipeline anterior ilustra dos buenas prácticas:

- **Secrets.** Los Podman Secrets residen en el store rootful del
  `podman-cloud-host` (sección 9.1); como el agente `podman-build` monta el
  socket rootful, `podman run --secret` los ve y los monta como fichero
  (`/run/secrets/<nombre>`). El pipeline **no** recibe el valor: el motor lo
  inyecta y lo destruye de memoria al terminar. Los nombres por defecto
  (`api_token_prod_file`, `api_token_prod_pass`, `api_token_prod_shell`)
  se iteran en el bucle para mostrar los tres drivers disponibles.
- **kubeconfig.** Se define una sola vez como *Managed Config File*
  (`configFileProvider`) y el pipeline lo recibe como variable
  `KUBECONFIG`. Rotar credenciales del clúster no obliga a reconstruir la
  imagen del agente. El `kubeconfig-demo` del laboratorio es un ejemplo sin
  credenciales reales; se sustituye por el del clúster correspondiente (o se
  crea un *Secret file* con el kubeconfig real y se ajusta el `fileId`).

---

## 12. Ventajas, inconvenientes y criterios de elección

### Podman-Cloud — ventajas

- **Mentalidad de nodo**: los agentes aparecen en `/computer`, con labels,
  retención e *idle timeout*. Resulta natural para equipos con experiencia en
  Kubernetes.
- **Aislamiento por proyecto**: cada plantilla es una imagen y un entorno; no
  se depende del nodo anfitrión salvo para la API.
- **Escalado y gestión centralizados**: una Cloud sirve a múltiples jobs; añadir
  una toolchain equivale a añadir una plantilla.
- **Pipeline limpio**: no es necesario conocer Docker para escribir stages; solo
  labels.
- `containerCap` limita el consumo.

### Podman-Cloud — inconvenientes

- **Las imágenes deben ser agentes Jenkins** (JDK + inbound-agent/sshd). Es el
  coste principal: mantenimiento de imágenes híbridas.
- Requiere **exponer la API de Podman** de forma segura (mTLS o túnel SSH).
- **Workspace y cachés se configuran en la plantilla**, no en el pipeline; si
  varían por proyecto, hay que modificar infraestructura (o usar una plantilla
  por proyecto).
- La depuración es más indirecta: los errores de arranque del contenedor/agente
  aparecen en los logs del *cloud* y en `/computer`, no en el log del stage.
- Escribir la configuración de la Cloud en JCasC es verboso y sensible a
  versiones.

### Podman-Host (`agent { docker {} }`) — ventajas

- **Cualquier imagen es válida** (toolchain pura), con menos imágenes que
  mantener.
- Todo es visible en el código del pipeline: los `-v`, `--userns`, las cachés.
  Favorece el enfoque "todo en el código" (GitOps) y el versionado por
  proyecto.
- Un contenedor **por stage**, muy efímero; el workspace lo controla el
  pipeline.
- Depuración directa en el log del stage.

### Podman-Host — inconvenientes

- El código del pipeline conoce Docker (`args`, `--userns=keep-id`, mounts):
  mayor acoplamiento y fragilidad.
- No hay nodos de agente visibles; el concepto de agente es el nodo anfitrión
  (uno solo) y los contenedores son sidecars efímeros.
- Menor aislamiento entre proyectos que Podman-Cloud.

### Criterios de elección

- **Un único nodo Podman, varios proyectos, toolchains cambiantes**:
  Podman-Host. Es el modelo que mejor encaja y el que menos imágenes exige.
- **Múltiples equipos/proyectos, necesidad de labels, cuotas y aislamiento**:
  Podman-Cloud (o Jenkins-Kubernetes). Merece la pena cuando el coste de
  construir imágenes-agente se amortiza.
- Es posible **combinar ambos**: `docker-plugin` para agentes "completos" y
  `agent { docker {} }` para los efímeros ligeros, dentro del mismo Jenkins.

---

## 13. Checklist de implantación del modelo Podman-Cloud

1. **Exponer la API de Podman de forma segura**: mTLS (o túnel SSH). En este
   laboratorio: PKI propia + `podman-tcp.service` en `:2376` (ADR-0005).
2. **Definir el usuario del contenedor en el `DockerTemplateBase`**
   (`user: 0`), no en el conector JNLP, para que el agente pueda escribir en el
   workspace.
3. **Construir imágenes-agente híbridas** (base `jenkins/inbound-agent` +
   toolchain) e importarlas o construirlas en el `podman-host`.
4. **Crear una plantilla por imagen** con:
   - `labelString` distinto,
   - `remoteFs` apuntando a la ruta compartida,
   - mount del workspace y de las cachés en formato `key=value`,
   - `securityOpts = "label=disable"` en todas las plantillas,
   - (para la de build) socket rootful + `CONTAINER_HOST`,
   - *Connect method* JNLP con el `jenkinsUrl` del controller.
5. **Compartir el workspace** montando el mismo directorio del host en todas las
   plantillas (para transmitir artefactos entre stages).
6. **Decidir el JDK de compilación**: usar Maven Toolchains si difiere del JDK
   del agente.
7. **Gestionar los secrets** en el store rootful y consumirlos con
   `podman run --secret`; no pasar valores por argumentos.
8. **Inyectar el kubeconfig** (u otras credenciales) como *Managed Config File*,
   no en la imagen.
9. **Validar**: forzar cada label, comprobar `/computer`, verificar la
   persistencia del workspace y la reutilización de cachés.
10. **Endurecer y mantener**: `containerCap`, *idle timeout* razonable, job de
    purga que no elimine las imágenes-agente ni los volúmenes de caché.

---

## 14. Resolución de problemas

- **Cambio en `init.groovy.d/` (Cloud, plantillas, pipeline…) y Jenkins no lo
  aplica.** Los scripts `init.groovy.d/` solo se ejecutan al arrancar Jenkins.
  Debe reejecutarse el playbook (`cd ansible && ansible-playbook site.yml`),
  que copia los scripts y reinicia Jenkins automáticamente. El playbook lee el
  `.env` del laboratorio, por lo que no es necesario exportar secretos
  manualmente. No debe usarse `./deploy.sh` para este fin (destruye y recrea las
  VMs).
- **"Agent is being disconnected" / el contenedor arranca pero no conecta.** El
  controller no es alcanzable desde el contenedor. Comprobar `jenkinsUrl` (debe
  usar la IP del controller, no `localhost`) y que el puerto HTTP/JNLP sea
  accesible desde la red de contenedores del `podman-host`.
- **"no such image" con imágenes locales.** Seleccionar *Pull strategy* =
  `Never pull`.
- **`Permission denied` al construir imágenes.** El socket no está montado,
  SELinux lo bloquea (usar `--security-opt label=disable`) o el UID del
  contenedor no coincide con el propietario del socket.
- **`Invalid mount: expected key=value comma separated…`.** Se está usando la
  sintaxis `-v host:contenedor` en el campo *Volumes*/`mounts` del template.
  Dicho campo espera pares `key=value`, por ejemplo
  `type=bind,source=/datos/jenkins/pipelines-workspace,destination=/datos/jenkins/pipelines-workspace`
  y `type=volume,source=maven-cache,destination=/cache/.m2`. Es un error del
  propio `docker-plugin`, no de Podman.
- **`java.nio.file.AccessDeniedException: …/workspace/<job>@tmp`** (o
  `Permission denied` al escribir en el workspace). Dos causas habituales:
  1. **Usuario del contenedor**: debe ser `user: 0` en el
     `DockerTemplateBase` (campo *User* de la plantilla), no en el conector
     JNLP (su campo `user` es *legacy* y no modifica el usuario del
     contenedor). Si el agente corre como `jenkins` (UID 1000), no puede
     escribir en el workspace del host (UID 1100).
  2. **SELinux** (`enforcing`): el *bind mount* del workspace requiere
     `securityOpts = "label=disable"` (el plugin no puede expresar `:z`).
     Comprobar con `ausearch -m avc -ts recent` o `journalctl -t audit`.
- **El workspace "desaparece" entre builds.** No se ha montado el volumen del
  host, o `remoteFs` no apunta a la ruta montada.
- **Cachés corruptas o builds concurrentes que fallan.** Varios ejecutores
  comparten `maven-cache`; usar `disableConcurrentBuilds()` o un volumen
  distinto por agente.
- **Contenedores que se acumulan.** Ajustar *Idle timeout* / *Container Cap*;
  para la limpieza, ver la sección 8.1.
- **`PKIX path building failed` / `certificate signed by unknown authority`.**
  La credencial X.509 no se ha creado o la Cloud no la referencia. Comprobar que
  `00-create-docker-credentials.groovy` registra `DOCKER_CREDENTIAL_CREATED` en
  el log de Jenkins y que `JENKINS_PODMAN_CLOUD_TLS_CREDENTIALS` está definida
  (ADR-0005). Si ha cambiado la IP del `podman-host`, debe regenerarse la PKI
  (el SAN del certificado de servidor incluye esa IP).
- **`no such file or directory` al leer `/etc/jenkins/podman-tls/…`.** Ansible
  no copió el material de cliente (el orden de fases es incorrecto) o el usuario
  `jenkins` no puede leerlo. Comprobar permisos (`0700` en el directorio, `0600`
  en la clave).
- **`kubectl` responde `no configuration` / `error: current-context`.** El
  pipeline no inyectó el kubeconfig. Comprobar que el *Managed File*
  `kubeconfig-demo` existe (Config File Provider) y que el stage envuelve el
  `sh` en `configFileProvider([configFile(... variable: 'KUBECONFIG')])`.
- **El secret no aparece en `/run/secrets`.** El agente `podman-build` no monta
  el socket rootful (revisar los `mounts` de la plantilla) o el secret no existe
  (`create_example_secrets: true` en `vars.yml`).

---

## 15. Referencias

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
- Guía principal del laboratorio Podman-Host:
  [`Ephemeral-Jenkins-Agents-Podman-host.md`](../../Podman-Host/docs/Ephemeral-Jenkins-Agents-Podman-host.md)
- ADRs del laboratorio Podman-Cloud:
  [ADR-0001](./adr/0001-rootful-api-y-motor.md),
  [ADR-0002](./adr/0002-imagenes-agente-hibridas.md),
  [ADR-0003](./adr/0003-api-tcp-sin-tls.md) (sustituido por ADR-0005),
  [ADR-0004](./adr/0004-aprovisionamiento-via-cloud.md),
  [ADR-0005](./adr/0005-mtls-api-podman.md) (mTLS),
  [ADR-0006](./adr/0006-jdk-agente-vs-jdk-compilacion-toolchains.md) (JDK del agente vs JDK de compilación).
- ADRs relacionados (laboratorio Podman-Host):
  [ADR-0003](../../Podman-Host/docs/adr/0003-init-groovy-vs-jcasc.md) (init.groovy vs JCasC),
  [ADR-0005](../../Podman-Host/docs/adr/0005-podman-secrets-como-root.md) (secrets root),
  [ADR-0009](../../Podman-Host/docs/adr/0009-reference-app-sin-scm.md) (app de referencia sin SCM),
  [ADR-0010](../../Podman-Host/docs/adr/0010-habilitar-podman-socket.md) (podman.socket),
  [ADR-0011](../../Podman-Host/docs/adr/0011-security-opt-label-disable-podman-socket.md) (`--security-opt label=disable`).

---

*Licencia: [CC BY 4.0](../../guides/LICENSE). © 2026 Cloudsdoers.*
