# agent-jdk7-ubi8

Imagen legacy para Jenkins con **Oracle JDK 7u80 + Maven 3.5.4** sobre
UBI 8 minimal.

## Por que existe esta imagen

JDK 7 fue la LTS que reemplazo a JDK 6 entre julio 2011 y julio 2019.
Aunque EOL, sigue siendo necesaria para:

- Aplicaciones enterprise antiguas que nunca se migraron.
- Proyectos que usan `sun.misc.Unsafe` o APIs internas de HotSpot que
  desaparecieron en Java 8+.
- Sistemas que requieren compatibilidad con productos comerciales
  certificados solo para Java 7 (ej. SAP NetWeaver 7.x, Oracle Forms
  12c).
- Maven 3.5.x (la ultima compatible con Java 7) y muchos plugins de
  Maven que aun requieren Java 7.

Para casos donde sea posible migrar, se recomienda **JDK 8 LTS** (la
siguiente LTS soportada). Ver `agent-jdk8-ubi8/`.

## Que incluye

- **Oracle JDK 7u80** (el ultimo patch level publico).
- **Apache Maven 3.5.4** (la ultima version compatible con Java 7).
- `tar`, `gzip`, `shadow-utils`, `ca-certificates`.
- Usuario no-privilegiado `jenkins_agent` (UID/GID 1001).
- `JAVA_HOME=/opt/jdk7`, `MAVEN_HOME=/opt/maven`.

## Que NO incluye

- **binario de Oracle JDK**: hay que descargarlo manualmente y ponerlo
  en `binaries/jdk-7u80-linux-x64.tar.gz` antes del build. Ver
  `binaries/README.md`.

## Como construirla

### Pre-requisito critico

Tener el binario `jdk-7u80-linux-x64.tar.gz` en `binaries/`. Sin esto,
el build falla. Ver `binaries/README.md` para instrucciones de descarga.

### Build

```bash
podman build -t agent-jdk7-ubi8:1.0.0 .
```

### Verificacion local

```bash
podman run --rm agent-jdk7-ubi8:1.0.0 bash -c \
    "java -version 2>&1 && echo '---' && mvn --version"
```

Salida esperada:

```
java version "1.7.0_80"
Java(TM) SE Runtime Environment (build 1.7.0_80-b15)
Java HotSpot(TM) 64-Bit Server VM (build 24.80-b11, mixed mode)
---
Apache Maven 3.5.4 (da9f9c57a9b461551adcf53fb52f8f9cf21314f7)
Maven home: /opt/maven
Java version: 1.7.0_80, vendor: Oracle Corporation, runtime: ...
```

## Como subirla a un registro

Igual que las otras imagenes. Ver `agent-jdk8-ubi8/README.md` seccion
"Como subirla a un registro".

## Como usarla desde Jenkins

Igual que las otras imagenes. Ver `agent-jdk8-ubi8/README.md` seccion
"Como usarla desde Jenkins".

## Compatibilidad con Maven

| Maven | Java minimo | Java maximo |
|---|---|---|
| 3.0-3.2.5 | 5 | 8 |
| 3.3.x | 7 | 8 |
| 3.5.x | 7 | 9 |
| 3.6.x | 7 | 11 |

Esta imagen usa Maven 3.5.4, la **ultima 3.5.x** y la **ultima con soporte
pleno para Java 7 en build time** (Maven 3.6.x compila con Java 7 pero
tiene issues conocidos con plugins antiguos).

## Compatibilidad con frameworks

| Framework/Plataforma | JDK 7 OK? |
|---|---|
| Apache Struts 2.5.x | ✅ |
| Spring Framework 4.3.x | ✅ (5.x requiere 8) |
| Hibernate 5.0-5.2 | ✅ |
| Apache CXF 3.x | ✅ |
| Play Framework 2.5.x | ✅ (2.6+ requiere 8) |
| WildFly 8-10 | ✅ |
| Tomcat 7, 8 | ✅ |

## Seguridad

**JDK 7 es EOL** desde julio 2019 (publico) / julio 2022 (comercial).
NO recibe parches de seguridad. Esta imagen es **solo para entornos
aislados** con las mismas restricciones que JDK 6.

**NO usar para** servidores en produccion expuestos a internet.

## Versionado

Tags generados:

- `agent-jdk7-ubi8:1.0.0` (version explicita).
- `agent-jdk7-ubi8:1.0` (minor + patch).
- `agent-jdk7-ubi8:1` (major + minor).
- `agent-jdk7-ubi8:latest` (no usar en produccion).

Esta imagen se considera **finalizada** porque JDK 7 esta EOL y no habra
nuevas versiones de Oracle.

## Troubleshooting

**`Cannot find /binaries/jdk-7u80-linux-x64.tar.gz`**: el binario no esta
en el directorio `binaries/`. Ver `binaries/README.md`.

**`tar: invalid magic`**: el archivo descargado no es un tar.gz valido.
Verificar el tamano (debe ser ~140 MB) y volver a descargar.

**`mvn --version` falla con `Unsupported class file major version 58`**: el
Maven 3.5.4 intentando cargar clases de Java 17. Verificar que
`JAVA_HOME` apunta a JDK 7, no a otra version del sistema.

**`Permission denied` al descomprimir**: el binario no es ejecutable o no
tiene permisos de lectura. `chmod +r jdk-7u80-linux-x64.tar.gz`.

## Referencias

- Oracle JDK 7 archive: https://www.oracle.com/java/technologies/javase-java-archive-downloads.html
- Maven 3.5.4: https://archive.apache.org/dist/maven/maven-3/3.5.4/
- UBI 8 minimal: https://catalog.redhat.com/software/containers/ubi8/ubi-minimal
- JDK 7 EOL: https://en.wikipedia.org/wiki/Java_version_history
