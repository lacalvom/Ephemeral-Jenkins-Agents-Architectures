# agent-jdk6-ubi8

Imagen legacy para Jenkins con **Oracle JDK 6u45 + Maven 3.2.5** sobre
UBI 8 minimal.

## Por que existe esta imagen

Esta imagen es la **ultima opcion** para proyectos que requieren
estrictamente Java 6 y no pueden migrarse. Esta documentada en la guia
original del proyecto como caso limite.

Casos de uso:

- Software COBOL-to-Java legacy con APIs de Java 6 que fueron retiradas.
- Proyectos que dependen de bibliotecas ya no mantenidas y que solo
  compilan contra Java 6 (rt.jar quirks).
- Sistemas regulados (aeroespacial, defensa, salud) donde la
  certificacion solo cubre JDK 6.

Para todos los demas casos, **se recomienda NO usar JDK 6** y considerar
las alternativas documentadas en `binaries/README.md`.

## Que incluye

- **Oracle JDK 6u45** (el ultimo patch level publico).
- **Apache Maven 3.2.5** (la ultima version compatible con Java 6).
- `tar`, `gzip`, `shadow-utils`, `ca-certificates`.
- Usuario no-privilegiado `jenkins_agent` (UID/GID 1001).
- `JAVA_HOME=/opt/jdk6`, `MAVEN_HOME=/opt/maven`.

## Que NO incluye

- **binario de Oracle JDK**: hay que descargarlo manualmente y ponerlo
  en `binaries/jdk-6u45-linux-x64.bin` antes del build. Ver
  `binaries/README.md`.

## Como construirla

### Pre-requisito critico

Tener el binario `jdk-6u45-linux-x64.bin` en `binaries/`. Sin esto, el
build falla. Ver `binaries/README.md` para instrucciones de descarga.

### Build

```bash
podman build -t agent-jdk6-ubi8:1.0.0 .
```

### Verificacion local

```bash
podman run --rm agent-jdk6-ubi8:1.0.0 bash -c \
    "java -version 2>&1 && echo '---' && mvn --version"
```

Salida esperada:

```
java version "1.6.0_45"
Java(TM) SE Runtime Environment (build 1.6.0_45-b06)
Java HotSpot(TM) 64-Bit Server VM (build 20.45-b01, mixed mode)
---
Apache Maven 3.2.5 (ea98dd3003e14051f8e88dee1bb78e07fb9bb682)
Maven home: /opt/maven
Java version: 1.6.0_45, vendor: Sun Microsystems Inc., runtime: ...
Default locale: en_US, platform encoding: ANSI_X3.4-1968
OS name: "linux", version: "...", arch: "amd64", family: "unix"
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
| 3.5+ | 7 | 11 |

Esta imagen usa Maven 3.2.5 (la ultima 3.2.x), la **ultima version
plenamente compatible con Java 6**.

## Compatibilidad con frameworks

| Framework/Plataforma | JDK 6 OK? |
|---|---|
| Apache Ant 1.9.x | ✅ |
| Apache Struts 2.3.x | ✅ (2.5+ requiere 7) |
| Spring Framework 3.2.x | ✅ (4.x requiere 6+) |
| Hibernate 4.x | ✅ |
| Apache CXF 2.7.x | ✅ |
| Play Framework 1.x | ✅ (2.x requiere 6+) |

## Seguridad

**JDK 6 es EOL** desde febrero 2013 (publico). NO recibe parches de
seguridad. Esta imagen es **solo para entornos aislados** donde:

- La certificacion regulatoria requiere JDK 6.
- No hay exposicion a internet.
- Se acepta el riesgo de CVEs conocidos.

**NO usar para** servidores en produccion expuestos a internet o que
procesen datos sensibles.

## Versionado

Tags generados:

- `agent-jdk6-ubi8:1.0.0` (version explicita).
- `agent-jdk6-ubi8:1.0` (minor + patch).
- `agent-jdk6-ubi8:1` (major + minor).
- `agent-jdk6-ubi8:latest` (no usar en produccion).

Esta imagen se considera **finalizada** porque JDK 6 esta EOL y no habra
nuevas versiones de Oracle.

## Troubleshooting

**`Cannot find /binaries/jdk-6u45-linux-x64.bin`**: el binario no esta en
el directorio `binaries/`. Ver `binaries/README.md`.

**`/tmp/jdk-installer.bin: Permission denied`**: el `chmod +x` fallo.
Verificar que el binario se copio correctamente.

**`/tmp/jdk-installer.bin: cannot execute binary file`**: el archivo
descargado esta corrupto o no es el JDK 6 correcto. Verificar el SHA256
contra el publicado por Oracle.

**`java -version` muestra otra version**: hay un JDK preinstalado en el
PATH. El `JAVA_HOME` deberia tener precedencia, pero verificar con
`podman run --rm agent-jdk6-ubi8:1.0.0 env | grep JAVA`.

## Referencias

- Oracle JDK 6 archive: https://www.oracle.com/java/technologies/javase-java-archive-downloads.html
- Maven 3.2.5: https://archive.apache.org/dist/maven/maven-3/3.2.5/
- UBI 8 minimal: https://catalog.redhat.com/software/containers/ubi8/ubi-minimal
- JDK 6 EOL: https://en.wikipedia.org/wiki/Java_version_history
