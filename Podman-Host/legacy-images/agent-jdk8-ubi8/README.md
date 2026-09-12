# agent-jdk8-ubi8

Imagen legacy para Jenkins con **JDK 8 puro** (sin Maven) sobre UBI 8 minimal.

## Por que existe esta imagen

La guia original del proyecto ("Arquitectura de Agentes Efimeros con Podman
y Jenkins") documenta que los proyectos legacy que usan **Java 8** no pueden
construirse sobre las imagenes UBI 9 oficiales de Red Hat, porque Red Hat
solo empaqueta JDKs modernos (11, 17, 21).

Tampoco existe una imagen `openjdk-8` oficial mantenida en Red Hat para UBI,
asi que hay que construir una propia. Esta imagen es **la minima posible**:
solo el JDK y las utilidades necesarias para descomprimir binarios en runtime.

## Que incluye

- **Temurin JDK 8u504-b01** (la unica distribucion publica mantenida de
  OpenJDK 8, gestionada por la Eclipse Adoptium Working Group).
- `tar`, `gzip` y `shadow-utils` (para `useradd`).
- Usuario no-privilegiado `jenkins_agent` con UID/GID 1001.
- `JAVA_HOME=/opt/jdk8`.
- Nada mas: ni Maven, ni Gradle, ni Node. Para herramientas adicionales,
  ver `agent-jdk8-maven-ubi8/`.

## Que NO incluye

- **Maven**: si necesitas Maven, usa `agent-jdk8-maven-ubi8/`.
- **Gradle**: hay que instalarlo en runtime o construir otra imagen.
- **Cualquier herramienta de build**: el proposito es solo el JDK.

## Como construirla

### Pre-requisitos

- Podman >= 4.0 (probada con Podman 5.x en AlmaLinux 9).
- Conexion a internet para descargar Temurin 8 (~50 MB).

### Build

Desde este directorio:

```bash
podman build -t agent-jdk8-ubi8:1.0.0 .
```

Sin tags adicionales:

```bash
podman build -t agent-jdk8-ubi8:latest .
```

Build con proxy/cache corporativo:

```bash
podman build --build-arg HTTP_PROXY=http://proxy.empresa.local:8080 \
             -t agent-jdk8-ubi8:1.0.0 .
```

### Verificacion local

```bash
# Arrancar un contenedor de prueba
podman run --rm -it agent-jdk8-ubi8:1.0.0 bash

# Dentro del contenedor
java -version
# debe mostrar "openjdk version \"8u504\" ..."
javac -version
# debe mostrar "javac 8u504"
echo $JAVA_HOME
# debe mostrar /opt/jdk8
```

Verificacion rapida sin entrar al contenedor:

```bash
podman run --rm agent-jdk8-ubi8:1.0.0 java -version
```

## Como subirla a un registro

### Registro local inseguro (registry:2 sobre Podman)

```bash
# Arrancar el registro local en el host
podman run -d -p 5000:5000 --restart=always --name registry \
    -v /var/lib/registry:/var/lib/registry \
    docker.io/library/registry:2

# Tagear la imagen
podman tag agent-jdk8-ubi8:1.0.0 localhost:5000/agent-jdk8-ubi8:1.0.0

# Subir
podman push localhost:5000/agent-jdk8-ubi8:1.0.0 --tls-verify=false

# Verificar que aparece
curl -s http://localhost:5000/v2/_catalog | python3 -m json.tool
```

### Registry con autenticacion (ejemplo con Quay)

```bash
# Login
podman login quay.io

# Tagear con la ruta completa del registry
podman tag agent-jdk8-ubi8:1.0.0 quay.io/mi-org/agent-jdk8-ubi8:1.0.0

# Subir
podman push quay.io/mi-org/agent-jdk8-ubi8:1.0.0
```

### Red Hat Quay.io (recomendado para empresas)

Quay tiene planes gratuitos con escaneo de vulnerabilidades integrado. Es
especialmente relevante porque escanea imagenes basadas en UBI automaticamente
y avisa de CVEs.

### GitHub Container Registry (ghcr.io)

```bash
# Login con token de GitHub
echo "$GITHUB_TOKEN" | podman login ghcr.io -u mi-usuario --password-stdin

# Tagear y subir
podman tag agent-jdk8-ubi8:1.0.0 ghcr.io/mi-org/agent-jdk8-ubi8:1.0.0
podman push ghcr.io/mi-org/agent-jdk8-ubi8:1.0.0
```

## Como usarla desde Jenkins

En el `Jenkinsfile` o pipeline declarativo:

```groovy
pipeline {
    agent {
        docker {
            image 'localhost:5000/agent-jdk8-ubi8:1.0.0'
            reuseNode true
            args '--userns=keep-id -v maven-cache-${EXECUTOR_NUMBER}:/cache/.m2:z'
        }
    }
    stages {
        stage('Build') {
            steps {
                sh 'java -version'
                sh 'javac MiClase.java'
                sh 'java -jar mi-app.jar'
            }
        }
    }
}
```

## Versionado

Esta imagen sigue **Semantic Versioning**:

- **MAJOR**: cambio incompatible (ej. migrar de Temurin a otro JDK).
- **MINOR**: anadir funcionalidad compatible (ej. instalar Maven).
- **PATCH**: fix o cambio cosmético (ej. actualizar version del Temurin).

Tags generados:
- `agent-jdk8-ubi8:1.0.0` (version explicita, recomendada para produccion).
- `agent-jdk8-ubi8:1.0` (minor + patch, para upgrades automaticos).
- `agent-jdk8-ubi8:1` (major + minor).
- `agent-jdk8-ubi8:latest` (alias de la ultima estable, NO recomendado para produccion).

## Actualizacion

Cuando sale una nueva Temurin 8:

1. Buscar la version: `curl -sIL https://github.com/adoptium/temurin8-binaries/releases/latest`.
2. Editar el ARG `JDK_VERSION` en el `Containerfile`.
3. Reconstruir y re-tagear: `podman build -t agent-jdk8-ubi8:1.0.1 .`.
4. Probar en un job de Jenkins antes de promover.
5. Push al registro.

## Troubleshooting

**La build falla con "Could not resolve host"**: no hay conectividad a
GitHub. Verificar proxy y DNS.

**La build falla con "checksum mismatch"**: Temurin cambio la URL o el
formato. Consultar https://adoptium.net/temurin/releases/?version=8.

**El contenedor arranca pero `java -version` da error**: probablemente se
descargo una imagen incorrecta (por ejemplo, el JRE en vez del JDK).
Verificar que la URL apunta a `OpenJDK8U-jdk_x64_linux_hotspot_...` (con
`-jdk-`, no `-jre-`).

**Permission denied al escribir en el workspace**: el UID 1001 dentro del
contenedor debe coincidir con el UID del usuario jenkins en el host.
Esto se garantiza con `--userns=keep-id` en el pipeline.

## Referencias

- Temurin 8: https://adoptium.net/temurin/releases/?version=8
- Adoptium API: https://api.adoptium.net/
- UBI 8 minimal: https://catalog.redhat.com/software/containers/ubi8/ubi-minimal
- OpenJDK 8 EOL: April 2026 (parcial), December 2030 (commercial).
