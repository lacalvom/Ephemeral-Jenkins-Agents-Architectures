# Arquitectura de Agentes Efímeros con Podman y Jenkins

> **Modelo: `Podman-Host`.** Implementado con el plugin `docker-workflow`
> (`agent { docker { ... } }`): contenedores efímeros creados **desde el propio
> `Jenkinsfile`**, un contenedor por stage, sobre un Podman Host.

Esta guía define el estándar operativo para la implementación, configuración y uso de agentes efímeros en Jenkins utilizando Podman en modo rootless. Esta arquitectura garantiza entornos de compilación limpios, aislados por etapa y seguros, operando bajo las normativas de sistemas empresariales (RHEL/UBI 9) y mitigando los problemas de concurrencia de infraestructura.

## Configuración del Jenkins Controller

Para que Jenkins pueda orquestar contenedores dinámicamente, el Podman Host debe registrarse como un Nodo (Agente Permanente) base que actuará como intermediario para lanzar los contenedores efímeros.

### Instalación de Plugins

1. En el Jenkins Controller, navega a `Manage Jenkins > Plugins > Available plugins`.
2. Busca e instala los plugins **Docker Pipeline** y **Docker plugin**.
3. Reinicia Jenkins si es necesario.

### Registro del Podman Host (Nodo Base)

Conectaremos el servidor directamente como un nodo trabajador mediante conexión entrante (Inbound/WebSocket):

1. Navega a `Manage Jenkins > Nodes > New Node`.
2. Escribe el nombre del servidor (ej. `podman-host-rhel9`) y selecciona **Permanent Agent**.
3. En la configuración del nodo, define:
* **Remote root directory:** `/datos/jenkins/pipelines-workspace` (De aquí tomará Jenkins la ruta para montar automáticamente el volumen del código fuente en los contenedores).
* **Labels:** `podman-node` (Para poder enrutar los pipelines a esta máquina).
* **Launch method:** Selecciona **Launch agent by connecting it to the controller** y marca la casilla **Use WebSocket**.


4. Guarda el nodo. Jenkins te mostrará una pantalla con un `secret` y un comando `java -jar agent.jar...`. Guarda ese `secret` para configurarlo en el servidor.

> **Nota de enrutamiento:** En el código del `pipeline`, asignaremos este nodo base a nivel global utilizando `agent { label 'podman-node' }`. Posteriormente, en cada etapa de compilación, indicaremos a los contenedores efímeros que utilicen el parámetro `reuseNode true` para heredar este espacio de trabajo.

---

## Aprovisionamiento del Podman Host (Nodo Trabajador)

El servidor RHEL/UBI 9 que actuará como host de los contenedores debe configurarse con Podman y conectarse a Jenkins mediante un servicio de sistema gestionado por WebSockets.

### Instalación de Dependencias y Usuario

Ejecutar los siguientes comandos como `root` en el servidor:

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

*Aclaración:* El paquete `podman-docker` es crucial. Crea un alias transparente a nivel de sistema para que cuando el plugin de Jenkins intente ejecutar el comando `docker run`, el sistema operativo lo redirija de forma nativa a `podman run`.

### Configuración del Agente (Systemd a nivel de usuario)

Inicia sesión como el usuario `jenkins` para descargar el agente y aislar completamente el servicio en su entorno, sin requerir privilegios de superusuario.

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

1. **Especialización de imágenes:** Tener un conjunto de imágenes con la combinación exacta de las herramientas necesarias para cada tipo de proyecto.
2. **Asignación granular:** Utilizar los distintos agentes a nivel de etapas (stages) dentro del pipeline.
3. **Caché de Nivel 1 (Proxy de red):** Todos los proyectos deben estar configurados (vía `settings.xml` o `.npmrc`) para descargar las dependencias de un proxy de caché (Nexus/Artifactory) dentro de la red interna, nunca directamente de Internet. Si el nodo físico muere o se escala en un nodo nuevo, los volúmenes locales estarán vacíos y la recarga desde un proxy de red local es lo único que salvará los tiempos de compilación.
4. **Caché de Nivel 2 (Aislamiento local por ejecutor):** Hay que utilizar volúmenes nombrados de Podman/Docker para las cachés de Maven o npm y montarlos con el sufijo del ejecutor cuando se seleccione el agente en el pipeline. Al definir en el pipeline el argumento de montaje del volumen, el motor de contenedores creará el volumen de forma automática e invisible la primera vez que se ejecute si no existe.
5. **Gestión de permisos en Podman Rootless:** Es obligatorio agregar el parámetro `--userns=keep-id` en los argumentos de Podman en el pipeline. Si no se hace, Podman mapeará el usuario dentro del contenedor de forma incorrecta (ej. `UID 100999`) y fallarán los intentos de escritura sobre el Workspace (el código fuente montado desde el host) por errores de "Permiso denegado". Ejemplo: `--userns=keep-id -v maven-cache-${env.EXECUTOR_NUMBER}:/cache/.m2:z`.
6. **Rutas absolutas independientes:** Los volúmenes de cachés se deben montar usando rutas absolutas fuera del directorio Home de ningún usuario y pasárselo a la herramienta para que lo encuentre. Por ejemplo: `mvn -Dmaven.repo.local=/cache/.m2/repository`.
7. **Compatibilidad con SELinux:** Si el host utiliza SELinux, agregar el sufijo `:z` al final de los montajes de volúmenes **normales** (cachés, artefactos). **Excepción importante:** el socket de Podman es "contenido de sistema" (lo gestiona la unidad `podman.socket`) y **no** debe relabelarse con `:z`; en su lugar se usa `--security-opt label=disable`. Relabelarlo con `:z` rompe el contexto SELinux que la política espera para permitir la conexión (ver ADR-011).
8. **Montaje Simétrico (Docker-out-of-Docker) y Automático:** En la configuración del Nodo (Permanent Agent) en el Jenkins Controller, definir el directorio local como Remote root directory. Aclaración: Al configurar esto en la máquina base, el plugin Docker Pipeline lee la ruta del nodo y se encarga de realizar el montaje del workspace de forma **completamente automática e invisible** en cada contenedor efímero. No es necesario ni recomendable declarar volúmenes para el código fuente en el código del pipeline.
9. **Delegación de Empaquetado (Montaje del Socket):** Jenkins lanza los agentes efímeros a través del CLI local, por lo que no necesita red para orquestarlos. **Excepción en el Pipeline:** Si una etapa específica necesita empaquetar una imagen (ej. `podman build`), se debe montar el socket rootless del host dentro de esa etapa usando la ruta del UID del usuario `jenkins` (**1100**) y **sin `:z`**, añadiendo `--security-opt label=disable`. Ejemplo: `--security-opt label=disable -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock` (ver ADR-011).

## Gestión Centralizada de Configuraciones con Config File Provider

El plugin **Config File Provider** resuelve la distribución segura de configuraciones de acceso a proxies (como Artifactory o Nexus) eliminando la necesidad de mantener archivos físicos (`settings.xml`, `.npmrc`) sincronizados entre servidores. Toda la infraestructura subyacente —URLs, credenciales y certificados— es administrada de forma centralizada por el equipo DevOps directamente en el Jenkins Controller. Esto garantiza una operación segura y completamente transparente en arquitecturas de agentes efímeros, permitiendo a los desarrolladores centrarse exclusivamente en el código.

### Gestión de Catálogo
A través de la interfaz de Jenkins (`Manage Jenkins > Managed files`), es posible crear múltiples versiones de estos archivos y asignarles `IDs` únicos (por ejemplo, `maven-settings-legacy`, `maven-settings-modern`, `npmrc-frontend`). De este modo, el pipeline simplemente solicita bajo demanda la versión exacta que requiere la herramienta.

### Flujo de Ejecución Seguro
Durante la ejecución del pipeline, el proceso opera de la siguiente manera:

1. El pipeline solicita a Jenkins la inyección de un archivo de configuración mediante su ID.
2. Jenkins recupera el archivo de su base de datos segura y crea una copia temporal oculta dentro del workspace del agente efímero.
3. El sistema expone una variable de entorno con la ruta exacta hacia ese archivo temporal.
4. El comando de compilación utiliza esta ruta para autenticarse y descargar dependencias.
5. Al finalizar la etapa, Jenkins destruye automáticamente el archivo temporal, sin dejar rastro de credenciales en el agente.

### Ventajas Operativas
* **Limpieza de nodos:** Ya no se necesita gestionar archivos físicos en el Controller ni en los agentes. Los servidores de infraestructura quedan 100% agnósticos.
* **Seguridad dinámica:** El archivo con los tokens de Artifactory se inyecta en milisegundos y Jenkins lo destruye automáticamente del workspace en el momento en que se cierra el bloque `configFileProvider { ... }`.
* **Mantenibilidad:** Si Artifactory rota sus contraseñas, se actualiza el archivo una única vez en la interfaz del Jenkins Controller. Todos los pipelines adoptarán el cambio inmediatamente en su siguiente ejecución.

## Aclaración de Infraestructura: Gestión de Versiones de Java y `agent.jar`

Una duda arquitectónica frecuente al utilizar Jenkins Controllers actualizados (ej. ejecutándose sobre Java 21) es cómo afecta esto a la compilación de proyectos legacy (ej. Java 8) dentro de contenedores efímeros.

Es imperativo comprender que el plugin de Docker de Jenkins no inyecta el proceso `agent.jar` dentro de los contenedores efímeros declarados en las etapas del pipeline. La arquitectura se divide en dos capas estrictas:

1. **La Capa de Control (El Podman/Docker Host):** El nodo base que se conecta al Jenkins controller es el único que ejecuta el proceso `agent.jar`. Este sistema operativo anfitrión **sí debe tener instalado Java 21** (o la versión requerida por tu Controller) para mantener el canal de comunicación bidireccional abierto.
2. **La Capa de Ejecución (El Contenedor Efímero):** Cuando una etapa declara `agent { docker { image '.../openjdk-8' } }`, el `agent.jar` del host simplemente lanza el contenedor en segundo plano, monta el workspace dinámicamente y utiliza la API del motor de contenedores (`docker exec` / `podman exec`) para introducir y ejecutar los comandos del bloque steps.

**Conclusión:** El contenedor efímero es completamente agnóstico a la infraestructura de Jenkins. Si tu proyecto requiere compilarse con Java 8, la imagen del contenedor solo necesita tener instalado Java 8. No habrá ningún conflicto de versiones, ya que el motor interno de Jenkins (Java 21) se queda trabajando exclusivamente de puertas para afuera, en el sistema operativo del host.

## Pipeline Maestro de Referencia

Este ejemplo consolida todas las reglas arquitectónicas y el uso de configuraciones centralizadas en un único pipeline funcional.

> La version **canonica y validada** de este pipeline vive en el repositorio, en
> `Podman-Host/jenkins-config/jobs/reference-pipeline.groovy`. El ejemplo de
> abajo es la referencia arquitectonica; en el laboratorio **no hay registry
> interno**, por lo que el empaquetado solo etiqueta la imagen localmente
> (`reference-backend:latest` / `reference-frontend:latest`) en lugar de hacer
> `podman push`.

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
                    // Imagen oficial de Red Hat basada en RHEL 9 (incluye OpenJDK 17 y Maven)
                    image 'registry.access.redhat.com/ubi9/openjdk-17:latest'
                    // Obliga al contenedor a reutilizar el workspace y ejecutor del agente global
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
                    // Imagen oficial de Red Hat basada en RHEL 9 para Node.js 18
                    image 'registry.access.redhat.com/ubi9/nodejs-20:latest'
                    // Obliga al contenedor a reutilizar el workspace y ejecutor del agente global
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
                    // Imagen oficial de Red Hat con las herramientas de Podman
                    image 'registry.access.redhat.com/ubi9/podman:latest'
                    // Obliga al contenedor a reutilizar el workspace y ejecutor del agente global
                    reuseNode true
                    // Montamos el socket de Podman del host para delegar la construcción
                    args "--userns=keep-id --security-opt label=disable -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock"
                }
            }
            steps {
                sh '''
                # Se utiliza el cliente remoto de podman apuntando al socket del host
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
                    // Obliga al contenedor a reutilizar el workspace y ejecutor del agente global
                    reuseNode true
                    args "--userns=keep-id --security-opt label=disable -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock"
                }
            }
            steps {
                // Al usar reuseNode true, los artefactos generados en stages anteriores 
                // siguen estando presentes aquí en el mismo directorio físico.                
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

## Acerca de imágenes de contenedores de agentes efímeros

* **La alternativa oficial para Java 8:** Si se tiene un proyecto legacy que requiere estrictamente compilarse con Java 8, se debe utilizar la imagen basada en la versión anterior del sistema operativo: [registry.access.redhat.com/ubi8/openjdk-8](https://registry.access.redhat.com/ubi8/openjdk-8). Sigue siendo una imagen de grado empresarial, gratuita, redistribuible y totalmente compatible con las políticas de seguridad.

* **Inclusión de Maven:** Todas las imágenes de la familia `openjdk` publicadas por `Red Hat` (tanto `UBI 8` como `UBI 9`) vienen con `Maven` preinstalado. `Red Hat` diseña estas imágenes específicamente como entornos de construcción (conocidas en su ecosistema como imágenes `Source-to-Image` o `S2I`). Por lo tanto, incluyen el `JDK` completo y el binario de `Maven` ya configurado en el PATH.

* **Para proyectos legacy que necesiten Java 6 o Java 7:** Debido a que el Ciclo de vida (`EOL`) de `Java 6` alcanzó su fin de vida en **2013** y `Java 7` en **2015** y el programa `UBI` (`Universal Base Image`) de `Red Hat` es mucho más moderno; se popularizó con `RHEL 8`. `Red Hat` solo empaqueta y da soporte a software que aún recibe parches de seguridad (`OpenJDK 8`, `11`, `17` y `21`). La principal ventaja de usar imágenes `UBI` es que están libres de vulnerabilidades conocidas (`CVEs`) de serie. Empaquetar un `JDK` obsoleto violaría la garantía de seguridad de `Red Hat`, por lo que nunca publicarán esas versiones en sus registros oficiales. Así que sólo existen dos opciones para poder compilar estos proyectos legacy (`Java6/7`) manteniendo el estándar:

1. **Compilación cruzada con Java 8 (recomendada):** Utilizar la imagen oficial de `UBI 8` con `Java 8` ([registry.access.redhat.com/ubi8/openjdk-8](https://www.google.com/search?q=https://registry.access.redhat.com/ubi8/openjdk-8)) y configurar el `pom.xml` del proyecto legacy o los parámetros de `Maven` en el pipeline para que el compilador de `Java 8` genere bytecode compatible con `Java 6` o `7`: `mvn clean package -Dmaven.compiler.source=1.6 -Dmaven.compiler.target=1.6` la ventaja es que se sigue usando una imagen oficial, segura y soportada por `Red Hat` sin modificar la infraestructura.

2. **Construir una imagen propia basada en UBI con el JDK antiguo (manual):** Si la compilación cruzada da problemas por dependencias muy estrictas, habrá que crear un `Dockerfile` propio, y en este caso, usar como base una imagen `UBI 8 mínima` e inyectarle el binario antiguo descargado manualmente:

Para construir estas imágenes sobre la base oficial de `Red Hat` (`UBI 8`), habrá que descargar manualmente los binarios históricos desde el archivo de `Oracle`, ya que no existen repositorios públicos que los distribuyan por restricciones de licencia. por ejemplo:

* Para Java 6: `jdk-6u45-linux-x64.bin`
* Para Java 7: `jdk-7u80-linux-x64.tar.gz`

Y colocar los archivos descargados en el mismo directorio donde se encuentre el `Dockerfile`.

### Imagen para Java 6 (con Maven 3.2.5)

`Maven 3.2.5` es la última versión oficial compatible con `Java 6`. Se utiliza la imagen estándar `ubi8` para asegurar que las utilidades básicas del sistema (`tar`, `gzip`, `curl`) estén disponibles para el pipeline de Jenkins.

```Dockerfile
# Java 6
FROM registry.access.redhat.com/ubi8/ubi:latest

LABEL maintainer="DevOps Team"
LABEL description="Agente efímero Jenkins con Oracle JDK 6 y Maven 3.2.5 sobre UBI 8"

# 1. Inyectar el binario descargado manualmente
COPY jdk-6u45-linux-x64.bin /opt/

# 2. Instalar dependencias, extraer Java 6 y limpiar caché
RUN yum install -y tar gzip curl && \
    yum clean all && \
    cd /opt && \
    chmod +x jdk-6u45-linux-x64.bin && \
    ./jdk-6u45-linux-x64.bin && \
    rm jdk-6u45-linux-x64.bin

# 3. Configurar entorno Java
ENV JAVA_HOME=/opt/jdk1.6.0_45
ENV PATH=$JAVA_HOME/bin:$PATH

# 4. Instalar Maven 3.2.5 (Última versión para Java 6)
RUN curl -O https://archive.apache.org/dist/maven/maven-3/3.2.5/binaries/apache-maven-3.2.5-bin.tar.gz && \
    tar xzf apache-maven-3.2.5-bin.tar.gz -C /opt && \
    ln -s /opt/apache-maven-3.2.5 /opt/maven && \
    rm apache-maven-3.2.5-bin.tar.gz

# 5. Configurar entorno Maven
ENV M2_HOME=/opt/maven
ENV PATH=$M2_HOME/bin:$PATH

CMD ["bash"]

```

### Imagen para Java 7 (con Maven 3.5.4)

`Maven 3.5.4` es la última versión oficial de la rama `3.5.x`, la cual es la última generación compatible con `Java 7` (las versiones `3.6.x` en adelante exigen `Java 8`).

```Dockerfile
# Java 7
FROM registry.access.redhat.com/ubi8/ubi:latest

LABEL maintainer="DevOps Team"
LABEL description="Agente efímero Jenkins con Oracle JDK 7 y Maven 3.5.4 sobre UBI 8"

# 1. Inyectar el binario descargado manualmente
COPY jdk-7u80-linux-x64.tar.gz /opt/

# 2. Instalar dependencias, extraer Java 7 y limpiar caché
RUN yum install -y tar gzip curl && \
    yum clean all && \
    cd /opt && \
    tar -xzf jdk-7u80-linux-x64.tar.gz && \
    rm jdk-7u80-linux-x64.tar.gz

# 3. Configurar entorno Java
ENV JAVA_HOME=/opt/jdk1.7.0_80
ENV PATH=$JAVA_HOME/bin:$PATH

# 4. Instalar Maven 3.5.4 (Última versión compatible con Java 7)
RUN curl -O https://archive.apache.org/dist/maven/maven-3/3.5.4/binaries/apache-maven-3.5.4-bin.tar.gz && \
    tar xzf apache-maven-3.5.4-bin.tar.gz -C /opt && \
    ln -s /opt/apache-maven-3.5.4 /opt/maven && \
    rm apache-maven-3.5.4-bin.tar.gz

# 5. Configurar entorno Maven
ENV M2_HOME=/opt/maven
ENV PATH=$M2_HOME/bin:$PATH

CMD ["bash"]

```

En síntesis, para todo lo que sea anterior a `Java 8`, se estaría fuera del ecosistema de imágenes preparadas y mantenidas por `RedHat`, por lo que habría que asumir el mantenimiento de esas imágenes o delegar la compatibilidad hacia atrás en el compilador de `Java 8`.

Una vez construidas (`podman build -t mi-registro/agente-java6-ubi8`), estas imágenes operarán en el código del pipeline de manera idéntica a las imágenes modernas, respetando los montajes de caché, la inyección del plugin Config File Provider y el uso del parámetro `--userns=keep-id`.

## Imágenes sugeridas para proyectos backend

| Imagen Base Recomendada | Versión de Java | Versión de Maven | Origen de la Imagen |
| --- | --- | --- | --- |
| `registry.access.redhat.com/ubi9/openjdk-21:latest` | Java 21 | 3.9.x | Oficial Red Hat (Soporte activo) |
| `registry.access.redhat.com/ubi9/openjdk-17:latest` | Java 17 | 3.8.x - 3.9.x | Oficial Red Hat (Soporte activo) |
| `registry.access.redhat.com/ubi9/openjdk-11:latest` | Java 11 | 3.8.x | Oficial Red Hat (Soporte activo) |
| `registry.access.redhat.com/ubi8/openjdk-8:latest` | Java 8 | 3.8.x | Oficial Red Hat (Legacy soportado) |
| `mi-registro-interno/agente-java7-ubi8:latest` | Java 7 | 3.5.4 | Custom (Construcción manual sobre UBI 8) |
| `mi-registro-interno/agente-java6-ubi8:latest` | Java 6 | 3.2.5 | Custom (Construcción manual sobre UBI 8) |

Las imágenes oficiales de `Node.js` proporcionadas por `Red Hat` no incluyen `Angular CLI` preinstalado. La mejor práctica es utilizar la imagen base correspondiente a la versión de `Node` exigida por el proyecto de `Angular`, y crear un `Dockerfile` propio que instale el `CLI` globalmente (`RUN npm install -g @angular/cli@<version>`).

## Imágenes sugeridas para proyectos frontend

| Imagen Base Recomendada | Versión de Node.js | Versión de npm | Versiones de Angular Compatibles | Origen de la Imagen Base |
| --- | --- | --- | --- | --- |
| `registry.access.redhat.com/ubi9/nodejs-20:latest` | Node 20.x | 10.x | Angular 17, 18 y superiores | Oficial Red Hat |
| `registry.access.redhat.com/ubi9/nodejs-18:latest` | Node 18.x | 9.x | Angular 15, 16, 17 | Oficial Red Hat |
| `registry.access.redhat.com/ubi9/nodejs-16:latest` | Node 16.x | 8.x | Angular 13, 14, 15 | Oficial Red Hat |
| `registry.access.redhat.com/ubi8/nodejs-14:latest` | Node 14.x | 6.x | Angular 11, 12, 13, 14 | Oficial Red Hat (Legacy) |
| `mi-registro-interno/agente-node12-ubi8:latest` | Node 12.x | 6.x | Angular 9, 10, 11 | Custom (Construcción manual sobre UBI 8) |
| `mi-registro-interno/agente-node10-ubi8:latest` | Node 10.x | 6.x | Angular 8, 9 | Custom (Construcción manual sobre UBI 8) |
| `mi-registro-interno/agente-node8-ubi8:latest` | Node 8.x | 5.x | Angular 5, 6, 7 | Custom (Construcción manual sobre UBI 8) |

### Imagen base UBI9 con Node 20 y Angular CLI 17

El detalle arquitectónico más importante al trabajar con las imágenes `UBI` de `Red Hat` es que vienen configuradas por defecto con un usuario sin privilegios (`UID 1001`) por motivos de seguridad. Por lo tanto, para instalar un paquete global como `Angular CLI`, debemos elevar temporalmente los privilegios a `root` durante la construcción y luego devolver el control al usuario estándar para que funcione perfectamente con `Podman rootless` (`--userns=keep-id`).

```Dockerfile
# Partimos de la imagen oficial de Node 20 en UBI 9
FROM registry.access.redhat.com/ubi9/nodejs-20:latest

# Metadatos recomendados
LABEL maintainer="DevOps Team"
LABEL description="Agente efímero Jenkins con Node.js 20 y Angular CLI 17 sobre UBI 9"

# 1. Cambiamos a usuario root temporalmente para poder escribir en /usr/local/
USER root

# 2. Instalamos la versión específica de Angular CLI (ejemplo: 17.3.0)
# Se limpia la caché inmediatamente después para mantener la imagen ligera
RUN npm install -g @angular/cli@17.3.0 && \
    npm cache clean --force

# 3. Restauramos el usuario sin privilegios original de Red Hat (UID 1001)
# Esto garantiza la compatibilidad estricta con Podman rootless en tu pipeline
USER 1001

# El entrypoint por defecto se mantiene heredado de la imagen base, 
# pero nos aseguramos de que inicie en bash para las ejecuciones de Jenkins
CMD ["/bin/bash"]

```

El ecosistema oficial de `Red Hat UBI` no mantiene imágenes para `Node 8`, `10` o `12`, ya que alcanzaron su fin de vida (`EOL`) hace años. Para mantener la política de utilizar sistemas base empresariales (`UBI 8`) y garantizar la trazabilidad de los artefactos, la mejor práctica es descargar el binario oficial de Linux desde `nodejs.org` e inyectarlo en la imagen mínima de `UBI 8`.

Al crear estas imágenes `custom`, es imprescindible crear explícitamente el usuario sin privilegios (`UID 1001`) para que los contenedores sigan siendo 100% compatibles con la regla de `--userns=keep-id` en una arquitectura `rootless`.

### Dockerfile para Node 12 y Angular CLI 11

```Dockerfile
FROM registry.access.redhat.com/ubi8/ubi:latest

LABEL maintainer="DevOps Team"
LABEL description="Agente efímero Jenkins con Node.js 12.22.12 y Angular CLI 11 sobre UBI 8"

# 1. Instalar utilidades de descompresión
RUN yum install -y tar xz curl && \
    yum clean all

# 2. Descargar e instalar el binario oficial de Node.js 12
RUN curl -fsSLO https://nodejs.org/dist/v12.22.12/node-v12.22.12-linux-x64.tar.xz && \
    tar -xJf node-v12.22.12-linux-x64.tar.xz -C /usr/local --strip-components=1 && \
    rm node-v12.22.12-linux-x64.tar.xz

# 3. Instalar Angular CLI 11 globalmente
RUN npm install -g @angular/cli@11.2.14 && \
    npm cache clean --force

# 4. Crear un usuario sin privilegios (UID 1001) para compatibilidad con Podman rootless
RUN useradd -u 1001 -m jenkins_agent
USER 1001

CMD ["bash"]

```

### Dockerfile para Node 10 y Angular CLI 8

```Dockerfile
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

```Dockerfile
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

## Uso de Podman Secrets para Credenciales y Configuraciones

Para evitar la exposición de credenciales, contraseñas de bases de datos o archivos de configuración sensibles (como un `kubeconfig` para Kubernetes) en el código fuente o en variables de entorno planas, la arquitectura soporta el uso nativo de **Podman Secrets**.

A diferencia del plugin *Config File Provider* (orientado a proxies de caché gestionados por Jenkins), Podman Secrets reside en el motor de contenedores y protege la información directamente en el host trabajador.

### Instalación de Prerrequisitos

Para integrar estas herramientas de cifrado en el servidor RHEL 9, se deben instalar con el usuario `root` antes de configurar los secretos. Como `sops`, `age` y `crypta` son binarios modernos (escritos en Go y Rust), la forma más limpia y libre de dependencias de instalarlos es descargando sus versiones precompiladas directamente desde sus repositorios oficiales en GitHub.

Ejecutar el siguiente bloque en tu Podman Host:

```bash
# 1. Instalar dependencias base, GPG (pgp) y Pass desde los repositorios de Red Hat
dnf install -y gnupg2 pass jq tar wget

# 2. Instalar Age (Herramienta de cifrado moderna superior a GPG)
AGE_VERSION="v1.1.1"
wget -qO- "https://github.com/FiloSottile/age/releases/download/${AGE_VERSION}/age-${AGE_VERSION}-linux-amd64.tar.gz" | tar xz
mv age/age age/age-keygen /usr/local/bin/
rm -rf age/

# 3. Instalar SOPS (Secrets OPerationS de Mozilla)
SOPS_VERSION="v3.8.1"
wget -qO /usr/local/bin/sops "https://github.com/getsops/sops/releases/download/${SOPS_VERSION}/sops-${SOPS_VERSION}.linux.amd64"
chmod +x /usr/local/bin/sops

# 4. Instalar Crypta (Wrapper en Rust creado por Atareao para integrar SOPS/Age)
# Descarga directa del binario precompilado para arquitecturas x86_64
wget -qO /usr/local/bin/crypta "https://github.com/atareao/crypta/releases/latest/download/crypta"
chmod +x /usr/local/bin/crypta

```

### Backend de Almacenamiento (Drivers)

Podman utiliza "drivers" para definir cómo se guardan y recuperan físicamente los secretos. Dependiendo del nivel de seguridad requerido por el proyecto, el equipo DevOps puede utilizar tres enfoques:

1. **Driver `file` (Por defecto):** Almacena los secretos sin cifrar en el disco (`~/.local/share/containers/storage/secrets`). Está protegido únicamente por los permisos del sistema de archivos de Linux.
2. **Driver `pass` (Cifrado GPG):** Utiliza la utilidad nativa `pass`. Cifra el secreto físicamente en el disco mediante una clave GPG, garantizando protección en reposo.
3. **Driver `shell` con `Crypta/SOPS/Age` (Ejecución Dinámica):** El estándar más avanzado. El secreto no se almacena en Podman; en su lugar, Podman delega la recuperación a un script externo (`crypta`) que descifra el valor al vuelo utilizando criptografía moderna (`age`) solo en el instante en que el contenedor arranca.

---

### 1. Creación y Gestión de los Secretos (En el Podman Host)

Inicia sesión en el Podman Host con el usuario `jenkins` (`su - jenkins`). A continuación se describen los tres flujos operativos.

#### Opción A: Uso del Driver `file` (Almacenamiento Estándar)

Ideal para entornos aislados donde el servidor físico tiene un control de acceso estricto.

```bash
# Ejemplo 1: Crear un secreto desde un texto plano (ej. un token)
echo "abc123supersecreto" | podman secret create api_token_prod -

# Ejemplo 2: Crear un secreto desde un archivo físico (ej. kubeconfig)
podman secret create k8s_config /rutas/seguras/admin.kubeconfig

```

#### Opción B: Uso del Driver `pass` (Cifrado GPG en reposo)

Requiere una inicialización previa del almacén GPG. Es la práctica recomendada para auditorías de seguridad (Zero Trust) utilizando herramientas clásicas.

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

#### Opción C: Uso del Driver `shell` con Crypta (SOPS + Age)

Este modelo descentraliza la seguridad. `Crypta` almacena y cifra los secretos usando `Age` (más rápido y seguro que GPG). Podman simplemente invoca a `Crypta` cuando necesita el dato.

```bash
# 1. Generar la clave de identidad de Age para el usuario jenkins
mkdir -p ~/.config/age
age-keygen -o ~/.config/age/keys.txt

# 2. Extraer la clave pública para configurar SOPS/Crypta
export AGE_RECIPIENT=$(grep "public key" ~/.config/age/keys.txt | awk '{print $4}')

# 3. Configurar Crypta para usar Age como backend
export SOPS_AGE_KEY_FILE=~/.config/age/keys.txt
crypta config set --recipient $AGE_RECIPIENT

# 4. Guardar un secreto cifrado con Crypta (El dato queda gestionado por sops/crypta)
crypta set api_token_prod "abc123supersecreto"

# 5. Registrar el secreto en Podman usando el driver 'shell'
# Podman NO guarda el secreto, solo guarda la instrucción de cómo obtenerlo
podman secret create --driver shell \
  --opt path=/usr/local/bin/crypta \
  --opt arg1=get \
  --opt arg2=api_token_prod \
  api_token_prod_podman

```

---

### 2. Consumo del Secreto en el Pipeline

Una de las mayores ventajas arquitectónicas es que, sin importar cuál de los tres drivers utilices para crear el secreto en el host, **el código del `pipeline` es agnóstico y no cambia en absoluto**.

Dentro de los argumentos del agente Docker, se utiliza el parámetro `--secret` apuntando al nombre registrado en Podman. El motor se encargará internamente de aplicar el driver correspondiente, descifrarlo, inyectarlo en el contenedor efímero y destruirlo de la memoria al finalizar.

```groovy
stage('Despliegue a Kubernetes') {
    agent {
        docker {
            image 'registry.access.redhat.com/ubi9/podman:latest'
            reuseNode true
            
            // Inyectamos el secreto como variable de entorno
            // (Si usaste la Opción C, usa el nombre de registro: api_token_prod_podman)
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
        
        # Ejecutar operaciones securizadas
        # ./script-despliegue.sh
        '''
    }
}

```

## Estrategia de Mantenimiento Automatizado (Pruning)
Dado que las dependencias de `Maven` y `npm` acumulan versiones antiguas que ya no se usan en el código, es una buena práctica forzar una purga de los volúmenes locales periódicamente (ej. cada fin de semana). Al borrar los volúmenes, el sistema simplemente los recreará en la siguiente ejecución y descargará dependencias frescas desde proxy `Artifactory/Nexus` (`Caché Nivel 1`).

Se puede crear un pipeline programado (`cron`) en Jenkins que se ejecute en el `Podman Host` durante una ventana de mantenimiento:

```groovy

pipeline {
    // Este pipeline se ejecuta directamente en el host base, no en un contenedor efímero
    agent { label 'podman-node' } 
    
    // Programado para ejecutarse todos los domingos a las 02:00 AM
    triggers {
        cron('H 2 * * 7')
    }

    stages {
        stage('Limpieza de Imágenes y Contenedores Huérfanos') {
            steps {
                sh '''
                echo "Eliminando contenedores detenidos e imágenes sin etiquetar (dangling)..."
                podman system prune -a -f
                '''
            }
        }
        
        stage('Purga de Volúmenes de Caché (Nivel 2)') {
            steps {
                sh '''
                echo "Borrando cachés locales de Maven y npm para liberar espacio..."
                # Busca y elimina todos los volúmenes cuyo nombre contenga 'cache'
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
1. **Evita la degradación silenciosa:** Un disco al 100% en el nodo trabajador es la causa número uno de caídas inesperadas en CI/CD.
2. **Higiene de dependencias:** Garantiza que los volúmenes del ejecutor no arrastren gigabytes de librerías de proyectos antiguos o ramas de Git que ya han sido eliminadas.
3. **Visibilidad centralizada:** Al hacerlo mediante un pipeline, el equipo de DevOps tiene un log histórico en la interfaz de Jenkins de cuánto espacio se está limpiando y cuándo se ejecutó por última vez.


---

*Licencia: [CC BY 4.0](../../guides/LICENSE). © 2026 Cloudsdoers.*
