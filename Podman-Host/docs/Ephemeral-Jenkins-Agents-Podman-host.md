# Arquitectura de Agentes Efímeros con Podman y Jenkins

> **Modelo: `Podman-Host`.** Implementado con el plugin `docker-workflow`
> (`agent { docker { ... } }`): contenedores efímeros creados **desde el propio
> código del pipeline**, un contenedor por stage, sobre un Podman Host.

Esta guía define el estándar operativo para la implementación, configuración
y uso de agentes efímeros en Jenkins con Podman en modo rootless. La
arquitectura garantiza entornos de compilación limpios, aislados por etapa y
seguros, bajo sistemas empresariales (RHEL/UBI 9), y mitiga los problemas de
concurrencia en la infraestructura.

## Configuración del Jenkins Controller

Para que Jenkins orqueste contenedores dinámicamente, el Podman Host se
registra como un **nodo (agente permanente) base**, que actúa como
intermediario para lanzar los contenedores efímeros.

### Instalación de plugins

1. En el Jenkins Controller, se accede a `Manage Jenkins > Plugins > Available
   plugins`.
2. Se buscan e instalan los plugins **Docker Pipeline** y **Docker plugin**.
3. Jenkins se reinicia si es necesario.

### Registro del Podman Host (nodo base)

La conexión se realiza como nodo trabajador mediante *Inbound/WebSocket*:

1. Se accede a `Manage Jenkins > Nodes > New Node`.
2. Se introduce el nombre del servidor (p. ej. `podman-host-rhel9`) y se
   selecciona **Permanent Agent**.
3. En la configuración del nodo se definen:
   - **Remote root directory:** `/datos/jenkins/pipelines-workspace` (ruta que
     monta el plugin para el código fuente en los contenedores).
   - **Labels:** `podman-node` (para enrutar los pipelines a esta máquina).
   - **Launch method:** **Launch agent by connecting it to the controller**
     con la casilla **Use WebSocket** marcada.
4. Se guarda el nodo. Jenkins muestra una pantalla con un `secret` y un comando
   `java -jar agent.jar ...`. El `secret` se conserva para configurar el
   servidor.

> **Nota de enrutamiento.** En el pipeline, este nodo base se asigna a nivel
> global con `agent { label 'podman-node' }`. En cada etapa de compilación se
> indica a los contenedores efímeros `reuseNode true` para heredar el workspace.

---

## Aprovisionamiento del Podman Host (nodo trabajador)

El servidor RHEL/UBI 9 que actúa como host de los contenedores se configura
con Podman y se conecta a Jenkins mediante un servicio de sistema gestionado
por WebSockets.

### Instalación de dependencias y usuario

Los comandos siguientes se ejecutan como `root` en el servidor:

```bash
# 1. Instalar podman y el paquete de compatibilidad con docker
dnf install -y podman podman-docker java-21-openjdk

# 2. Crear el usuario dedicado para Jenkins
useradd -m jenkins

# 3. Habilitar "linger" para los servicios de contenedores rootless
loginctl enable-linger jenkins

# 4. Crear los directorios base con los permisos adecuados
mkdir -p /datos/jenkins/agent
mkdir -p /datos/jenkins/pipelines-workspace
mkdir -p /datos/jenkins/tmp
chown -R jenkins:jenkins /datos/jenkins
```

El paquete `podman-docker` es clave: crea un alias a nivel de sistema que
redirige de forma nativa `docker run` a `podman run`, de modo que el plugin de
Jenkins no requiere la CLI real de Docker.

### Configuración del agente (systemd a nivel de usuario)

Se inicia sesión como el usuario `jenkins` para descargar el agente y aislar
el servicio en su entorno, sin requerir privilegios de superusuario:

```bash
su - jenkins

# 1. Descargar el agente desde el Controller
curl -sO https://jenkins.caser.local/jnlpJars/agent.jar
mv agent.jar /datos/jenkins/agent/

# 2. Guardar el secreto proporcionado por la interfaz de Jenkins
echo "TU_SECRETO_AQUI" > /datos/jenkins/agent/secret-file
chmod 400 /datos/jenkins/agent/secret-file

# 3. Crear el directorio para los servicios del usuario
mkdir -p ~/.config/systemd/user/

# 4. Crear el archivo del servicio
cat <<EOF > ~/.config/systemd/user/jenkins-agent.service
[Unit]
Description=Jenkins Agent Service (Podman Host Rootless)
After=network.target

[Service]
Environment="JAVA_OPTS=-Djava.io.tmpdir=/datos/jenkins/tmp"
ExecStart=/usr/bin/java \$JAVA_OPTS -jar /datos/jenkins/agent/agent.jar -url https://jenkins.caser.local/ -secret @/datos/jenkins/agent/secret-file -name podman-host-rhel9 -webSocket -workDir /datos/jenkins/pipelines-workspace
Restart=always
RestartSec=10

[Install]
WantedBy=default.target
EOF

# 5. Recargar demonios del usuario, habilitar y arrancar el agente
systemctl --user daemon-reload
systemctl --user enable --now jenkins-agent.service
```

## Buenas prácticas en el uso de agentes efímeros en Jenkins

1. **Especialización de imágenes.** Se mantiene un conjunto de imágenes con
   la combinación exacta de herramientas necesaria para cada tipo de
   proyecto.
2. **Asignación granular.** Los agentes se asignan a nivel de stages
   dentro del pipeline.
3. **Caché de Nivel 1 (proxy de red).** Todos los proyectos se configuran
   (vía `settings.xml` o `.npmrc`) para descargar las dependencias de un
   proxy de caché (Nexus/Artifactory) en la red interna, nunca directamente
   de Internet. Si el nodo físico muere o se escala en uno nuevo, los
   volúmenes locales estarán vacíos y la recarga desde un proxy de red local
   es lo único que mantiene los tiempos de compilación.
4. **Caché de Nivel 2 (aislamiento local por ejecutor).** Se utilizan
   volúmenes nombrados de Podman para las cachés de Maven o npm y se montan
   con el sufijo del ejecutor cuando se selecciona el agente en el pipeline.
   El motor de contenedores crea el volumen de forma automática e invisible
   la primera vez que se ejecuta, si no existe.
5. **Gestión de permisos en Podman rootless.** Es obligatorio incluir el
   parámetro `--userns=keep-id` en los argumentos de Podman en el pipeline.
   Sin él, Podman asignará UIDs incorrectos en el contenedor (p. ej.
   `UID 100999`), lo que produce errores de "Permiso denegado" al escribir
   sobre el workspace. Ejemplo:
   `--userns=keep-id -v maven-cache-${env.EXECUTOR_NUMBER}:/cache/.m2:z`.
6. **Rutas absolutas independientes.** Los volúmenes de caché se montan
   usando rutas absolutas fuera del `HOME` de cualquier usuario y se
   comunican a la herramienta. Por ejemplo:
   `mvn -Dmaven.repo.local=/cache/.m2/repository`.
7. **Compatibilidad con SELinux.** Si el host utiliza SELinux, se añade el
   sufijo `:z` al final de los montajes de volúmenes **normales** (cachés,
   artefactos). **Excepción importante:** el socket de Podman es
   "contenido de sistema" (lo gestiona la unidad `podman.socket`) y **no**
   debe relabelarse con `:z`; en su lugar se usa
   `--security-opt label=disable`. Relabelarlo con `:z` rompe el contexto
   SELinux que la política espera para permitir la conexión (ADR-011).
8. **Montaje simétrico (Docker-out-of-Docker) y automático.** En la
   configuración del nodo permanente en el Jenkins Controller se define
   el directorio local como Remote root directory. Al configurar esto, el
   plugin Docker Pipeline lee la ruta del nodo y realiza el montaje del
   workspace de forma **completamente automática e invisible** en cada
   contenedor efímero. No es necesario ni recomendable declarar volúmenes
   para el código fuente en el pipeline.
9. **Delegación del empaquetado (montaje del socket).** Jenkins lanza los
   agentes efímeros a través de la CLI local, por lo que no necesita red
   para orquestarlos. **Excepción:** si una etapa específica necesita
   empaquetar una imagen (p. ej. `podman build`), se monta el socket
   rootless del host dentro de esa etapa con la ruta del UID del usuario
   `jenkins` (1100) y **sin `:z`**, añadiendo
   `--security-opt label=disable`. Ejemplo:
   `--security-opt label=disable -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock`
   (ADR-011).

## Gestión centralizada de configuraciones con Config File Provider

El plugin **Config File Provider** resuelve la distribución segura de
configuraciones de acceso a proxies (Artifactory, Nexus) eliminando la
necesidad de mantener archivos físicos (`settings.xml`, `.npmrc`)
sincronizados entre servidores. Toda la infraestructura subyacente (URLs,
credenciales y certificados) la administra de forma centralizada el equipo
DevOps directamente en el Jenkins Controller. Esto garantiza una operación
segura y transparente en arquitecturas de agentes efímeros, y permite a los
desarrolladores centrarse exclusivamente en el código.

### Gestión de catálogo

A través de la interfaz de Jenkins (`Manage Jenkins > Managed files`) se
crean múltiples versiones de estos archivos y se les asignan `ID` únicos
(p. ej. `maven-settings-legacy`, `maven-settings-modern`,
`npmrc-frontend`). El pipeline solicita bajo demanda la versión exacta que
requiere la herramienta.

### Flujo de ejecución seguro

Durante la ejecución del pipeline, el proceso opera de la siguiente forma:

1. El pipeline solicita a Jenkins la inyección de un archivo de configuración
   mediante su ID.
2. Jenkins recupera el archivo de su base de datos segura y crea una copia
   temporal oculta dentro del workspace del agente efímero.
3. El sistema expone una variable de entorno con la ruta exacta al archivo
   temporal.
4. El comando de compilación utiliza esta ruta para autenticarse y
   descargar dependencias.
5. Al finalizar la etapa, Jenkins destruye automáticamente el archivo
   temporal, sin dejar rastro de credenciales en el agente.

### Ventajas operativas

- **Agnóstico de nodos.** No se necesitan archivos físicos en el Controller
  ni en los agentes; los servidores de infraestructura quedan
  completamente libres de configuración.
- **Seguridad dinámica.** El archivo con los tokens de Artifactory se
  inyecta en milisegundos y Jenkins lo destruye automáticamente del workspace
  al cerrar el bloque `configFileProvider { ... }`.
- **Mantenibilidad.** Si Artifactory rota sus contraseñas, se actualiza el
  archivo una única vez en la interfaz del Jenkins Controller; todos los
  pipelines adoptan el cambio en la siguiente ejecución.

## Aclaración de infraestructura: gestión de versiones de Java y `agent.jar`

Una duda arquitectónica frecuente al usar Jenkins Controllers actualizados
(p. ej. ejecutándose sobre Java 21) es cómo afecta a la compilación de
proyectos legacy (p. ej. Java 8) dentro de contenedores efímeros.

Es importante comprender que el plugin Docker de Jenkins **no inyecta el
proceso `agent.jar` dentro de los contenedores efímeros** declarados en las
etapas del pipeline. La arquitectura se divide en dos capas estrictas:

1. **Capa de control (el Podman Host).** El nodo base que se conecta al
   Jenkins Controller es el único que ejecuta el proceso `agent.jar`. Este
   sistema operativo anfitrión **sí debe tener instalado Java 21** (o la
   versión requerida por el Controller) para mantener abierto el canal de
   comunicación bidireccional.
2. **Capa de ejecución (el contenedor efímero).** Cuando una etapa declara
   `agent { docker { image '.../openjdk-8' } }`, el `agent.jar` del host
   lanza el contenedor en segundo plano, monta el workspace dinámicamente y
   utiliza la API del motor de contenedores (`docker exec` / `podman exec`)
   para introducir y ejecutar los comandos del bloque `steps`.

**Conclusión:** el contenedor efímero es completamente agnóstico a la
infraestructura de Jenkins. Si el proyecto requiere compilarse con Java 8,
la imagen del contenedor solo necesita tener instalado Java 8. No hay
conflicto de versiones: el motor interno de Jenkins (Java 21) trabaja
exclusivamente hacia el exterior, en el sistema operativo del host.

## Pipeline maestro de referencia

Este ejemplo consolida todas las reglas arquitectónicas y el uso de
configuraciones centralizadas en un único pipeline funcional.

> La versión **canónica y validada** de este pipeline reside en el
> repositorio, en `Podman-Host/jenkins-config/jobs/reference-pipeline.groovy`.
> El ejemplo de abajo es la referencia arquitectónica; en el laboratorio
> **no hay registry interno**, por lo que el empaquetado solo etiqueta la
> imagen localmente (`reference-backend:latest` /
> `reference-frontend:latest`) en lugar de hacer `podman push`.

```groovy
pipeline {
    // Asignamos el host RHEL 9 de forma global para todo el pipeline
    agent {
        label 'podman-node'
    }

    stages {
        // --------------------------------------------------------
        // FASE 1: COMPILACIÓN (Imágenes UBI 9)
        // --------------------------------------------------------
        stage('Construcción Backend (Java/Maven)') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/openjdk-17:latest'
                    reuseNode true
                    args "--userns=keep-id -v maven-cache-${env.EXECUTOR_NUMBER}:/cache/.m2:z"
                }
            }
            steps {
                configFileProvider([configFile(fileId: 'maven-settings-modern', variable: 'MAVEN_SETTINGS')]) {
                    sh 'mvn -s $MAVEN_SETTINGS -Dmaven.repo.local=/cache/.m2/repository clean package'
                }
            }
        }

        stage('Construcción Frontend (Node/npm)') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/nodejs-20:latest'
                    reuseNode true
                    args "--userns=keep-id -v npm-cache-${env.EXECUTOR_NUMBER}:/cache/.npm:z"
                }
            }
            steps {
                configFileProvider([configFile(fileId: 'npmrc-frontend', variable: 'NPMRC_FILE')]) {
                    sh '''
                    export NPM_CONFIG_USERCONFIG=$NPMRC_FILE
                    npm config set cache /cache/.npm
                    npm install
                    npm run build
                    '''
                }
            }
        }

        // --------------------------------------------------------
        // FASE 2: EMPAQUETADO (Cliente Podman en UBI 9)
        // --------------------------------------------------------
        stage('Empaquetar Imagen Backend') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/podman:latest'
                    reuseNode true
                    args "--userns=keep-id --security-opt label=disable -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock"
                }
            }
            steps {
                sh '''
                export CONTAINER_HOST=unix:///run/podman/podman.sock

                podman build -t artifactory.mi-empresa.local/backend-app:${BUILD_NUMBER} -f backend/Dockerfile .
                podman push artifactory.mi-empresa.local/backend-app:${BUILD_NUMBER}
                '''
            }
        }

        stage('Empaquetar Imagen Frontend') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/podman:latest'
                    reuseNode true
                    args "--userns=keep-id --security-opt label=disable -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock"
                }
            }
            steps {
                sh '''
                export CONTAINER_HOST=unix:///run/podman/podman.sock

                podman build -t artifactory.mi-empresa.local/frontend-app:${BUILD_NUMBER} -f frontend/Dockerfile .
                podman push artifactory.mi-empresa.local/frontend-app:${BUILD_NUMBER}
                '''
            }
        }
    }
}
```

## Imágenes de contenedores de agentes efímeros

- **Alternativa oficial para Java 8.** Si un proyecto legacy requiere
  estrictamente compilarse con Java 8, se utiliza la imagen basada en la
  versión anterior del sistema operativo:
  [`registry.access.redhat.com/ubi8/openjdk-8`](https://registry.access.redhat.com/ubi8/openjdk-8).
  Sigue siendo una imagen de grado empresarial, gratuita, redistribuible y
  totalmente compatible con las políticas de seguridad.

- **Inclusión de Maven.** Todas las imágenes de la familia `openjdk`
  publicadas por Red Hat (tanto UBI 8 como UBI 9) incluyen Maven
  preinstalado. Red Hat diseña estas imágenes específicamente como entornos
  de construcción (denominados `Source-to-Image` o `S2I`), por lo que
  incluyen el JDK completo y el binario de Maven configurado en el PATH.

- **Proyectos legacy con Java 6 o Java 7.** Java 6 alcanzó el fin de vida en
  2013 y Java 7 en 2015. El programa UBI de Red Hat no empaqueta ni da
  soporte a software sin parches de seguridad (OpenJDK 8, 11, 17 y 21). La
  principal ventaja de las imágenes UBI es que están libres de
  vulnerabilidades conocidas de serie; Red Hat no publica esas versiones en
  sus registros oficiales. Para proyectos legacy Java 6/7 se consideran dos
  opciones:

  1. **Compilación cruzada con Java 8 (recomendada).** Se utiliza la imagen
     oficial `ubi8/openjdk-8` y se configura el `pom.xml` del proyecto legacy
     (o los parámetros de Maven en el pipeline) para que el compilador de
     Java 8 genere bytecode compatible con Java 6 o 7:
     `mvn clean package -Dmaven.compiler.source=1.6 -Dmaven.compiler.target=1.6`.
     Se sigue usando una imagen oficial, segura y soportada por Red Hat.
  2. **Imagen propia basada en UBI 8 con el JDK antiguo.** Si la compilación
     cruzada da problemas por dependencias muy estrictas, se crea un
     `Dockerfile` propio sobre una UBI 8 mínima y se le inyecta el binario
     antiguo descargado manualmente desde el archivo de Oracle (no existen
     repositorios públicos por restricciones de licencia). Por ejemplo:
     - Java 6: `jdk-6u45-linux-x64.bin`
     - Java 7: `jdk-7u80-linux-x64.tar.gz`

     Los binarios se ubican en el mismo directorio que el `Dockerfile`.

### Imagen para Java 6 (con Maven 3.2.5)

`Maven 3.2.5` es la última versión oficial compatible con Java 6. Se parte
de la imagen `ubi8` estándar para asegurar que las utilidades básicas del
sistema (`tar`, `gzip`, `curl`) estén disponibles para el pipeline de
Jenkins.

```dockerfile
FROM registry.access.redhat.com/ubi8/ubi:latest

LABEL maintainer="DevOps Team"
LABEL description="Agente efímero Jenkins con Oracle JDK 6 y Maven 3.2.5 sobre UBI 8"

COPY jdk-6u45-linux-x64.bin /opt/

RUN yum install -y tar gzip curl && \
    yum clean all && \
    cd /opt && \
    chmod +x jdk-6u45-linux-x64.bin && \
    ./jdk-6u45-linux-x64.bin && \
    rm jdk-6u45-linux-x64.bin

ENV JAVA_HOME=/opt/jdk1.6.0_45
ENV PATH=$JAVA_HOME/bin:$PATH

RUN curl -O https://archive.apache.org/dist/maven/maven-3/3.2.5/binaries/apache-maven-3.2.5-bin.tar.gz && \
    tar xzf apache-maven-3.2.5-bin.tar.gz -C /opt && \
    ln -s /opt/apache-maven-3.2.5 /opt/maven && \
    rm apache-maven-3.2.5-bin.tar.gz

ENV M2_HOME=/opt/maven
ENV PATH=$M2_HOME/bin:$PATH

CMD ["bash"]
```

### Imagen para Java 7 (con Maven 3.5.4)

`Maven 3.5.4` es la última versión oficial de la rama `3.5.x`, que es la
última generación compatible con Java 7 (las versiones `3.6.x` en adelante
exigen Java 8).

```dockerfile
FROM registry.access.redhat.com/ubi8/ubi:latest

LABEL maintainer="DevOps Team"
LABEL description="Agente efímero Jenkins con Oracle JDK 7 y Maven 3.5.4 sobre UBI 8"

COPY jdk-7u80-linux-x64.tar.gz /opt/

RUN yum install -y tar gzip curl && \
    yum clean all && \
    cd /opt && \
    tar -xzf jdk-7u80-linux-x64.tar.gz && \
    rm jdk-7u80-linux-x64.tar.gz

ENV JAVA_HOME=/opt/jdk1.7.0_80
ENV PATH=$JAVA_HOME/bin:$PATH

RUN curl -O https://archive.apache.org/dist/maven/maven-3/3.5.4/binaries/apache-maven-3.5.4-bin.tar.gz && \
    tar xzf apache-maven-3.5.4-bin.tar.gz -C /opt && \
    ln -s /opt/apache-maven-3.5.4 /opt/maven && \
    rm apache-maven-3.5.4-bin.tar.gz

ENV M2_HOME=/opt/maven
ENV PATH=$M2_HOME/bin:$PATH

CMD ["bash"]
```

En resumen, para todo lo anterior a Java 8 se estaría fuera del ecosistema
de imágenes preparadas y mantenidas por Red Hat, por lo que se asume el
mantenimiento de esas imágenes o se delega la compatibilidad hacia atrás
en el compilador de Java 8.

Una vez construidas (`podman build -t mi-registro/agente-java6-ubi8`), estas
imágenes operan en el pipeline de forma idéntica a las modernas, respetando
los montajes de caché, la inyección del plugin Config File Provider y el uso
del parámetro `--userns=keep-id`.

## Imágenes sugeridas para proyectos backend

| Imagen base recomendada | Versión de Java | Versión de Maven | Origen |
|---|---|---|---|
| `registry.access.redhat.com/ubi9/openjdk-21:latest` | Java 21 | 3.9.x | Oficial Red Hat (soporte activo) |
| `registry.access.redhat.com/ubi9/openjdk-17:latest` | Java 17 | 3.8.x – 3.9.x | Oficial Red Hat (soporte activo) |
| `registry.access.redhat.com/ubi9/openjdk-11:latest` | Java 11 | 3.8.x | Oficial Red Hat (soporte activo) |
| `registry.access.redhat.com/ubi8/openjdk-8:latest` | Java 8 | 3.8.x | Oficial Red Hat (legacy soportado) |
| `mi-registro-interno/agente-java7-ubi8:latest` | Java 7 | 3.5.4 | Custom (construcción manual sobre UBI 8) |
| `mi-registro-interno/agente-java6-ubi8:latest` | Java 6 | 3.2.5 | Custom (construcción manual sobre UBI 8) |

Las imágenes oficiales de Node.js proporcionadas por Red Hat no incluyen
Angular CLI preinstalado. La mejor práctica es utilizar la imagen base
correspondiente a la versión de Node exigida por el proyecto de Angular y
crear un `Dockerfile` propio que instale el CLI globalmente
(`RUN npm install -g @angular/cli@<version>`).

## Imágenes sugeridas para proyectos frontend

| Imagen base recomendada | Versión de Node.js | Versión de npm | Versiones de Angular compatibles | Origen |
|---|---|---|---|---|
| `registry.access.redhat.com/ubi9/nodejs-20:latest` | Node 20.x | 10.x | Angular 17, 18 y superiores | Oficial Red Hat |
| `registry.access.redhat.com/ubi9/nodejs-18:latest` | Node 18.x | 9.x | Angular 15, 16, 17 | Oficial Red Hat |
| `registry.access.redhat.com/ubi9/nodejs-16:latest` | Node 16.x | 8.x | Angular 13, 14, 15 | Oficial Red Hat |
| `registry.access.redhat.com/ubi8/nodejs-14:latest` | Node 14.x | 6.x | Angular 11, 12, 13, 14 | Oficial Red Hat (legacy) |
| `mi-registro-interno/agente-node12-ubi8:latest` | Node 12.x | 6.x | Angular 9, 10, 11 | Custom (construcción manual sobre UBI 8) |
| `mi-registro-interno/agente-node10-ubi8:latest` | Node 10.x | 6.x | Angular 8, 9 | Custom (construcción manual sobre UBI 8) |
| `mi-registro-interno/agente-node8-ubi8:latest` | Node 8.x | 5.x | Angular 5, 6, 7 | Custom (construcción manual sobre UBI 8) |

### Imagen base UBI 9 con Node 20 y Angular CLI 17

El detalle arquitectónico más relevante al trabajar con las imágenes UBI de
Red Hat es que vienen configuradas con un usuario sin privilegios
(UID 1001) por motivos de seguridad. Por tanto, para instalar un paquete
global como Angular CLI se eleva temporalmente a `root` durante la
construcción y se devuelve el control al usuario estándar para garantizar
la compatibilidad con Podman rootless (`--userns=keep-id`).

```dockerfile
FROM registry.access.redhat.com/ubi9/nodejs-20:latest

LABEL maintainer="DevOps Team"
LABEL description="Agente efímero Jenkins con Node.js 20 y Angular CLI 17 sobre UBI 9"

USER root

RUN npm install -g @angular/cli@17.3.0 && \
    npm cache clean --force

USER 1001

CMD ["/bin/bash"]
```

El ecosistema oficial de Red Hat UBI no mantiene imágenes para Node 8, 10 o
12, ya que alcanzaron el fin de vida hace años. Para mantener la política de
utilizar sistemas base empresariales (UBI 8) y garantizar la trazabilidad de
los artefactos, la mejor práctica es descargar el binario oficial de Linux
desde `nodejs.org` e inyectarlo en la imagen mínima de UBI 8.

Al crear estas imágenes custom se crea explícitamente el usuario sin
privilegios (UID 1001) para mantener la compatibilidad con `--userns=keep-id`
en una arquitectura rootless.

### Dockerfile para Node 12 y Angular CLI 11

```dockerfile
FROM registry.access.redhat.com/ubi8/ubi:latest

LABEL maintainer="DevOps Team"
LABEL description="Agente efímero Jenkins con Node.js 12.22.12 y Angular CLI 11 sobre UBI 8"

RUN yum install -y tar xz curl && \
    yum clean all

RUN curl -fsSLO https://nodejs.org/dist/v12.22.12/node-v12.22.12-linux-x64.tar.xz && \
    tar -xJf node-v12.22.12-linux-x64.tar.xz -C /usr/local --strip-components=1 && \
    rm node-v12.22.12-linux-x64.tar.xz

RUN npm install -g @angular/cli@11.2.14 && \
    npm cache clean --force

RUN useradd -u 1001 -m jenkins_agent
USER 1001

CMD ["bash"]
```

### Dockerfile para Node 10 y Angular CLI 8

```dockerfile
FROM registry.access.redhat.com/ubi8/ubi:latest

LABEL maintainer="DevOps Team"
LABEL description="Agente efímero Jenkins con Node.js 10.24.1 y Angular CLI 8 sobre UBI 8"

RUN yum install -y tar xz curl && \
    yum clean all

RUN curl -fsSLO https://nodejs.org/dist/v10.24.1/node-v10.24.1-linux-x64.tar.xz && \
    tar -xJf node-v10.24.1-linux-x64.tar.xz -C /usr/local --strip-components=1 && \
    rm node-v10.24.1-linux-x64.tar.xz

RUN npm install -g @angular/cli@8.3.29 && \
    npm cache clean --force

RUN useradd -u 1001 -m jenkins_agent
USER 1001

CMD ["bash"]
```

### Dockerfile para Node 8 y Angular CLI 6

```dockerfile
FROM registry.access.redhat.com/ubi8/ubi:latest

LABEL maintainer="DevOps Team"
LABEL description="Agente efímero Jenkins con Node.js 8.17.0 y Angular CLI 6 sobre UBI 8"

RUN yum install -y tar xz curl && \
    yum clean all

RUN curl -fsSLO https://nodejs.org/dist/v8.17.0/node-v8.17.0-linux-x64.tar.xz && \
    tar -xJf node-v8.17.0-linux-x64.tar.xz -C /usr/local --strip-components=1 && \
    rm node-v8.17.0-linux-x64.tar.xz

RUN npm install -g @angular/cli@6.2.1 && \
    npm cache clean --force

RUN useradd -u 1001 -m jenkins_agent
USER 1001

CMD ["bash"]
```

## Uso de Podman Secrets para credenciales y configuraciones

Para evitar la exposición de credenciales, contraseñas de bases de datos o
archivos de configuración sensibles (como un `kubeconfig` para Kubernetes)
en el código fuente o en variables de entorno en claro, la arquitectura
incorpora el soporte nativo de **Podman Secrets**.

A diferencia del plugin Config File Provider (orientado a proxies de caché
gestionados por Jenkins), Podman Secrets reside en el motor de contenedores
y protege la información directamente en el host trabajador.

### Instalación de prerrequisitos

Para integrar estas herramientas de cifrado en el servidor RHEL 9, se
instalan con el usuario `root` antes de configurar los secretos. Como
`sops`, `age` y `crypta` son binarios modernos (Go y Rust), la forma más
clara y libre de dependencias de instalarlos es descargando sus versiones
precompiladas directamente desde sus repositorios oficiales en GitHub.

```bash
dnf install -y gnupg2 pass jq tar wget

AGE_VERSION="v1.1.1"
wget -qO- "https://github.com/FiloSottile/age/releases/download/${AGE_VERSION}/age-${AGE_VERSION}-linux-amd64.tar.gz" | tar xz
mv age/age age/age-keygen /usr/local/bin/
rm -rf age/

SOPS_VERSION="v3.8.1"
wget -qO /usr/local/bin/sops "https://github.com/getsops/sops/releases/download/${SOPS_VERSION}/sops-${SOPS_VERSION}.linux.amd64"
chmod +x /usr/local/bin/sops

wget -qO /usr/local/bin/crypta "https://github.com/atareao/crypta/releases/latest/download/crypta"
chmod +x /usr/local/bin/crypta
```

### Backends de almacenamiento (drivers)

Podman utiliza *drivers* para definir cómo se guardan y recuperan físicamente
los secretos. Dependiendo del nivel de seguridad requerido, se dispone de
tres enfoques:

1. **Driver `file` (por defecto).** Almacena los secretos sin cifrar en el
   disco (`~/.local/share/containers/storage/secrets`). Está protegido
   únicamente por los permisos del sistema de archivos de Linux.
2. **Driver `pass` (cifrado GPG).** Utiliza la utilidad nativa `pass`. Cifra
   el secreto físicamente en el disco mediante una clave GPG, garantizando
   protección en reposo.
3. **Driver `shell` con `Crypta/SOPS/Age` (ejecución dinámica).** El
   estándar más avanzado. El secreto no se almacena en Podman; en su lugar,
   Podman delega la recuperación a un script externo (`crypta`) que
   descifra el valor al vuelo con criptografía moderna (`age`) únicamente en
   el instante en que el contenedor arranca.

### Creación y gestión de los secretos (en el Podman Host)

Se inicia sesión en el Podman Host con el usuario `jenkins` (`su - jenkins`)
y se describe cada uno de los tres flujos.

#### Opción A: driver `file` (almacenamiento estándar)

Apto para entornos aislados con control de acceso estricto al servidor
físico.

```bash
echo "abc123supersecreto" | podman secret create api_token_prod -

podman secret create k8s_config /rutas/seguras/admin.kubeconfig
```

#### Opción B: driver `pass` (cifrado GPG en reposo)

Requiere una inicialización previa del almacén GPG. Es la práctica
recomendada para auditorías de seguridad (Zero Trust) con herramientas
clásicas.

```bash
# 1. Generar una clave GPG sin contraseña (para automatización rootless)
cat <<EOF > gpg-gen.conf
%echo Generando clave GPG...
Key-Type: RSA
Key-Length: 2048
Name-Real: Jenkins Podman
Name-Email: jenkins@mi-empresa.local
Expire-Date: 0
%no-protection
%commit
EOF
gpg --batch --generate-key gpg-gen.conf && rm -f gpg-gen.conf

# 2. Extraer el ID de la clave e inicializar el almacén pass
GPG_ID=$(gpg --list-keys --with-colons jenkins@mi-empresa.local | awk -F: '/^pub:/ { print $5 }')
pass init $GPG_ID

# 3. Crear secretos cifrados indicando el driver
echo "abc123supersecreto" | podman secret create --driver pass api_token_prod -
```

#### Opción C: driver `shell` con Crypta (SOPS + Age)

Este modelo descentraliza la seguridad. Crypta almacena y cifra los secretos
con Age (más rápido y seguro que GPG). Podman invoca a Crypta cuando necesita
el dato.

```bash
mkdir -p ~/.config/age
age-keygen -o ~/.config/age/keys.txt

export AGE_RECIPIENT=$(grep "public key" ~/.config/age/keys.txt | awk '{print $4}')

export SOPS_AGE_KEY_FILE=~/.config/age/keys.txt
crypta config set --recipient $AGE_RECIPIENT

crypta set api_token_prod "abc123supersecreto"

podman secret create --driver shell \
  --opt path=/usr/local/bin/crypta \
  --opt arg1=get \
  --opt arg2=api_token_prod \
  api_token_prod_podman
```

### Consumo del secreto en el pipeline

Una de las mayores ventajas arquitectónicas es que, sin importar cuál de los
tres drivers se haya utilizado para crear el secreto en el host, **el código
del pipeline es agnóstico y no cambia en absoluto**.

Dentro de los argumentos del agente Docker se utiliza el parámetro `--secret`
apuntando al nombre registrado en Podman. El motor aplica internamente el
driver correspondiente, descifra el valor, lo inyecta en el contenedor
efímero y lo destruye de memoria al finalizar.

```groovy
stage('Despliegue a Kubernetes') {
    agent {
        docker {
            image 'registry.access.redhat.com/ubi9/podman:latest'
            reuseNode true

            // Se inyecta el secreto como variable de entorno
            // (si se ha utilizado la opción C, el nombre de registro es api_token_prod_podman)
            args '''
                --userns=keep-id
                --secret api_token_prod,type=env,target=API_TOKEN
            '''
        }
    }
    steps {
        sh '''
        # El token está disponible de forma segura como variable
        echo "Autenticando API externa con $API_TOKEN"
        '''
    }
}
```

## Estrategia de mantenimiento automatizado (pruning)

Las dependencias de Maven y npm acumulan versiones antiguas que ya no se
utilizan en el código, por lo que se recomienda forzar una purga periódica de
los volúmenes locales (p. ej. cada fin de semana). Al borrar los volúmenes,
el sistema los recrea en la siguiente ejecución y descarga dependencias
frescas desde el proxy Artifactory/Nexus (Caché Nivel 1).

Se implementa como un pipeline programado (`cron`) en Jenkins que se ejecuta
en el `Podman Host` durante una ventana de mantenimiento:

```groovy
pipeline {
    // Este pipeline se ejecuta directamente en el host base, no en un contenedor efímero
    agent { label 'podman-node' }

    triggers {
        cron('H 2 * * 7')
    }

    stages {
        stage('Limpieza de imágenes y contenedores huérfanos') {
            steps {
                sh '''
                echo "Eliminando contenedores detenidos e imágenes sin etiquetar (dangling)..."
                podman system prune -a -f
                '''
            }
        }

        stage('Purga de volúmenes de caché (Nivel 2)') {
            steps {
                sh '''
                echo "Borrando cachés locales de Maven y npm para liberar espacio..."
                VOLUMES=$(podman volume ls -q | grep -E 'maven-cache|npm-cache' || true)

                if [ -n "$VOLUMES" ]; then
                    podman volume rm -f $VOLUMES
                    echo "Volúmenes de caché purgados exitosamente."
                else
                    echo "No se encontraron volúmenes de caché para eliminar."
                fi
                '''
            }
        }
    }
}
```

### Beneficios

1. **Previene la degradación silenciosa.** Un disco al 100% en el nodo
   trabajador es la causa principal de caídas inesperadas en CI/CD.
2. **Higiene de dependencias.** Garantiza que los volúmenes del ejecutor no
   arrastren gigabytes de librerías de proyectos antiguos o ramas de Git que
   ya han sido eliminadas.
3. **Visibilidad centralizada.** Al hacerlo mediante un pipeline, el equipo
   DevOps dispone de un log histórico en la interfaz de Jenkins con la
   cantidad de espacio liberado y la fecha de última ejecución.

---

*Licencia: [CC BY 4.0](../../guides/LICENSE). © 2026 Cloudsdoers.*
