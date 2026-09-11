# legacy-images

Imagenes Containerfile para agentes Jenkins legacy que el Jenkins
Controller no puede levantar nativamente con las imagenes UBI oficiales.

## Por que existe este directorio

La guia original del proyecto documenta que los proyectos legacy
(empresariales, bancos, administracion publica) suelen depender de
versiones antiguas de Java (6, 7, 8) y Node.js (8, 10, 12) que:

- Ya estan **EOL** (End of Life) hace anos.
- **No existen** en los registries publicos de Red Hat (solo UBI 9).
- Requieren **binarios propietarios** (Oracle JDK 6/7) que no se pueden
  descargar via URL publica estable.

Este directorio contiene las **imagenes Containerfile listas para usar**
que permiten a Jenkins construir proyectos legacy sin necesidad de
mantener VMs o nodos fisicos con esos runtimes.

## Indice de imagenes

| Imagen | Stack | EOL | Fuente binarios | Build |
|---|---|---|---|---|
| [agent-jdk8-ubi8](./agent-jdk8-ubi8/) | Temurin JDK 8 | parcial | automatica | OK |
| [agent-jdk8-maven-ubi8](./agent-jdk8-maven-ubi8/) | Temurin JDK 8 + Maven 3.8.7 | parcial | automatica | OK |
| [agent-node8-ubi8](./agent-node8-ubi8/) | Node.js 8.17.0 | abril 2022 | automatica | OK |
| [agent-node10-ubi8](./agent-node10-ubi8/) | Node.js 10.24.1 | abril 2021 | automatica | OK |
| [agent-node12-ubi8](./agent-node12-ubi8/) | Node.js 12.22.12 | abril 2022 | automatica | OK |
| [agent-jdk6-ubi8](./agent-jdk6-ubi8/) | Oracle JDK 6u45 + Maven 3.2.5 | febrero 2013 | **manual** | falla sin binario |
| [agent-jdk7-ubi8](./agent-jdk7-ubi8/) | Oracle JDK 7u80 + Maven 3.5.4 | julio 2019 | **manual** | falla sin binario |

**Estado de cada build al cierre de la primera version:**
- 5 imagenes construidas y validadas (JDK 8 puro, JDK 8 + Maven, Node 8/10/12).
- 2 imagenes requieren descarga manual de binarios Oracle (JDK 6 y JDK 7).

## Como se construye una imagen

Cada subdirectorio contiene:

- `Containerfile`: el Containerfile (compatible con Docker) para construir.
- `README.md`: documentacion detallada (por que existe, que incluye, como
  construir, como usar, troubleshooting).
- `binaries/` (solo en JDK 6 y JDK 7): directorio para los binarios de Oracle
  que hay que descargar manualmente.

Para construir una imagen:

```bash
cd agent-jdk8-ubi8
podman build -t agent-jdk8-ubi8:1.0.0 .
```

Para subir a un registry:

```bash
podman tag agent-jdk8-ubi8:1.0.0 localhost:5000/agent-jdk8-ubi8:1.0.0
podman push localhost:5000/agent-jdk8-ubi8:1.0.0 --tls-verify=false
```

Para usar en Jenkins:

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
                sh 'mvn clean verify'
            }
        }
    }
}
```

## Versionado y mantenimiento

Todas las imagenes siguen **Semantic Versioning** (`MAJOR.MINOR.PATCH`):

- **MAJOR**: cambio incompatible (ej. migrar de Temurin 8 a 11).
- **MINOR**: anadir herramienta compatible (ej. anadir Gradle a JDK 8).
- **PATCH**: actualizacion de la version del JDK/Node/Maven.

Las imagenes de **versiones EOL** (JDK 6, 7, Node 8/10/12) se consideran
**finalizadas en v1.x**: no se esperan actualizaciones mayores.

Para mantener las imagenes vivas:

- **JDK 8 / JDK 8 + Maven**: monitorizar releases de Temurin 8 en
  https://adoptium.net/temurin/releases/?version=8. Anadir `-bXX` al
  ARG `JDK_VERSION`.
- **Node 8/10/12**: NO recibirn mas updates (EOL).
- **JDK 6 / JDK 7**: NO recibirn mas updates (EOL).

## Registro interno recomendado

Para un entorno corporativo, se recomienda levantar un **registry
privado** dentro del cluster o red interna:

```bash
# Registry v2 oficial sobre Podman
podman run -d -p 5000:5000 --restart=always --name registry \
    -v /var/lib/registry:/var/lib/registry \
    docker.io/library/registry:2

# Push de todas las imagenes (ejemplo)
for img in agent-jdk8-ubi8 agent-jdk8-maven-ubi8 \
           agent-node8-ubi8 agent-node10-ubi8 agent-node12-ubi8; do
    podman tag ${img}:1.0.0 localhost:5000/${img}:1.0.0
    podman push localhost:5000/${img}:1.0.0 --tls-verify=false
done
```

Para builds mas avanzados, **Quay** ofrece escaneo de vulnerabilidades
integrado (especialmente util para detectar CVEs en JDK 8 EOL).

## Automatizacion del build

Para construir y publicar todas las imagenes automaticamente, ver el
rol `legacy_images` propuesto en el CHANGELOG.md del proyecto
(`[Unreleased] > Medio plazo > Imagenes custom para proyectos legacy`).

## Seguridad

Ninguna de estas imagenes es segura para produccion expuesta:

- **JDK 6/7**: EOL, multiples CVEs sin parche.
- **JDK 8 (Temurin)**: solo recibe updates de seguridad LTS hasta
  noviembre 2026 (despues, requiere licencia commercial).
- **Node 8/10/12**: EOL, multiples CVEs en npm packages.

**Recomendacion de uso**:

- Builds legacy internos: OK.
- Builds legacy en CI/CD publico: solo si se acepta el riesgo.
- Servidores en produccion expuestos: NO.

Para entornos donde la seguridad es critica, considerar:

- Usar `--read-only` root filesystem.
- Limitar capabilities de los contenedores (`--cap-drop=ALL`).
- Escanear con `trivy` o `grype` antes de hacer push.
- Configurar admission controllers en el registry que rechacen imagenes
  con CVEs criticos.

## Referencias

- Guia original del proyecto: `../Ephemeral-Agent-Architecture-Podman-Jenkins.md`
- Temurin (Eclipse Adoptium): https://adoptium.net/
- Node.js archives: https://nodejs.org/dist/
- Apache Maven archives: https://archive.apache.org/dist/maven/
- Oracle JDK archives: https://www.oracle.com/java/technologies/javase-java-archive-downloads.html
- UBI 8 minimal: https://catalog.redhat.com/software/containers/ubi8/ubi-minimal
- Jenkins Docker Pipeline: https://www.jenkins.io/doc/book/pipeline/docker/
