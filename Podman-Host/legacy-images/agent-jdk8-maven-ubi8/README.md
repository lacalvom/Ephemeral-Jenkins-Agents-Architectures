# agent-jdk8-maven-ubi8

Imagen legacy para Jenkins con **JDK 8 + Maven 3.8.7** sobre UBI 8 minimal.

## Por que existe esta imagen

La guia original del proyecto documenta que los proyectos legacy Java 8 con
Maven no pueden construirse sobre las imagenes UBI 9 oficiales porque:

1. Red Hat no empaqueta JDK 8 para UBI 9.
2. Las imagenes UBI 9 de openjdk vienen con JDK 11/17/21, no con 8.

Esta imagen combina Temurin 8 (la unica distribucion publica mantenida
de OpenJDK 8) con Apache Maven 3.8.7 (la ultima version de Maven
plenamente compatible con Java 8 en build time).

## Que incluye

- **Temurin JDK 8u504-b01** (OpenJDK 8 mantenido por Eclipse Adoptium).
- **Apache Maven 3.8.7** (la ultima con soporte oficial Java 8).
- `tar`, `gzip` y `shadow-utils`.
- Usuario no-privilegiado `jenkins_agent` (UID/GID 1001).
- `JAVA_HOME=/opt/jdk8` y `MAVEN_HOME=/opt/maven`.
- Ambos en el PATH.

## Que NO incluye

- **Gradle**: instalarlo en runtime o construir otra imagen.
- **Ant, Ivy, SBT**: idem.
- **Certificados SSL custom**: si necesitas conectarte a repositorios
  Maven internos con CA privada, montala via volume en runtime.
- **Settings.xml custom**: usa `Config File Provider` de Jenkins para
  inyectar el settings.xml adecuado.

## Como construirla

```bash
podman build -t agent-jdk8-maven-ubi8:1.0.0 .
```

Build con versiones custom (ej. para actualizar a Temurin 8u512):

```bash
podman build \
    --build-arg JDK_VERSION=8u512-b01 \
    --build-arg JDK_TARBALL_BASE=8u512b01 \
    --build-arg MAVEN_VERSION=3.8.8 \
    -t agent-jdk8-maven-ubi8:1.0.1 .
```

## Verificacion local

```bash
podman run --rm agent-jdk8-maven-ubi8:1.0.0 bash -c \
    "java -version && echo '---' && mvn --version"
```

Salida esperada:

```
openjdk version "1.8.0_504"
OpenJDK Runtime Environment (Temurin)(build 1.8.0_504-b01)
OpenJDK 64-Bit Server VM (Temurin)(build 25.504-b01, mixed mode)
---
Apache Maven 3.8.7 (ea98dd3003e14051f8e88dee1bb78e07fb9bb682)
Maven home: /opt/maven
Java version: 1.8.0_504, vendor: Temurin, runtime: ...
Default locale: en_US, platform encoding: ANSI_X3.4-1968
OS name: "linux", version: "...", arch: "amd64", family: "unix"
```

## Como subirla a un registro

### Registry local

```bash
podman tag agent-jdk8-maven-ubi8:1.0.0 localhost:5000/agent-jdk8-maven-ubi8:1.0.0
podman push localhost:5000/agent-jdk8-maven-ubi8:1.0.0 --tls-verify=false
```

### GitHub Container Registry (ghcr.io)

```bash
echo "$GITHUB_TOKEN" | podman login ghcr.io -u mi-usuario --password-stdin
podman tag agent-jdk8-maven-ubi8:1.0.0 ghcr.io/mi-org/agent-jdk8-maven-ubi8:1.0.0
podman push ghcr.io/mi-org/agent-jdk8-maven-ubi8:1.0.0
```

### Quay.io (recomendado para empresas)

```bash
podman login quay.io
podman tag agent-jdk8-maven-ubi8:1.0.0 quay.io/mi-org/agent-jdk8-maven-ubi8:1.0.0
podman push quay.io/mi-org/agent-jdk8-maven-ubi8:1.0.0
```

## Como usarla desde Jenkins

En el `Jenkinsfile`:

```groovy
pipeline {
    agent {
        docker {
            image 'localhost:5000/agent-jdk8-maven-ubi8:1.0.0'
            reuseNode true
            // --userns=keep-id mapea el UID 1001 del contenedor al
            // usuario jenkins del host, evitando problemas de permisos.
            // :z es necesario si SELinux esta en enforcing.
            args '--userns=keep-id -v maven-cache-${EXECUTOR_NUMBER}:/cache/.m2:z'
        }
    }
    stages {
        stage('Build') {
            steps {
                configFileProvider([
                    configFile(fileId: 'maven-settings-modern',
                                variable: 'MAVEN_SETTINGS')
                ]) {
                    sh '''
                        set -euo pipefail
                        # El path /cache/.m2 se mapea al volumen del host.
                        mvn -s "$MAVEN_SETTINGS" \
                            -Dmaven.repo.local=/cache/.m2/repository \
                            -B -ntp \
                            clean verify
                    '''
                }
            }
        }
    }
}
```

## Compatibilidad con JDK 8 y Maven

| Maven | Java minimo | Java maximo |
|---|---|---|
| 3.2.5 | 6 | 8 |
| 3.3.x | 6 | 8 |
| 3.5.x | 7 | 9 |
| 3.6.x | 7 | 11 |
| 3.8.x | 7 | 11 |
| 3.9.x | 8 | 20 |

Para **Java 6 estricto** (sin soporte para TLS 1.2 nativo, etc.), usa
la imagen `agent-jdk6-ubi8/` (que requiere binarios manuales de Oracle).
Para **Java 7 estricto**, usa `agent-jdk7-ubi8/`.

## Versionado

Sigue Semantic Versioning:

- **MAJOR**: cambio incompatible (ej. JDK 8 -> 11).
- **MINOR**: anadir herramienta compatible (ej. anadir Ant).
- **PATCH**: actualizacion de Temurin/Maven.

Tags generados:
- `agent-jdk8-maven-ubi8:1.0.0` (version explicita).
- `agent-jdk8-maven-ubi8:1.0` (minor + patch).
- `agent-jdk8-maven-ubi8:1` (major + minor).
- `agent-jdk8-maven-ubi8:latest` (alias, no usar en produccion).

## Troubleshooting

**Maven falla con "invalid target release: 11"**: el `pom.xml` del proyecto
requiere JDK 11+. Esta imagen es solo para proyectos Java 8. Si necesitas
Java 11+, usa la imagen `ubi9/openjdk-17` directamente.

**Maven no encuentra JAVA_HOME**: verificar con `mvn --version` dentro del
contenedor. Si no aparece, reconstruir la imagen (el RUN de verificacion
fallara y abortara el build).

**OutOfMemoryError en build**: Maven por defecto usa 256 MB. Si el proyecto
lo requiere, ajustar `MAVEN_OPTS` via Jenkins:
```groovy
sh 'export MAVEN_OPTS="-Xmx2g" && mvn ...'
```

## Referencias

- Temurin 8: https://adoptium.net/temurin/releases/?version=8
- Maven 3.8.7: https://archive.apache.org/dist/maven/maven-3/3.8.7/
- UBI 8 minimal: https://catalog.redhat.com/software/containers/ubi8/ubi-minimal
- Compatibilidad Maven/Java: https://maven.apache.org/docs/history.html
