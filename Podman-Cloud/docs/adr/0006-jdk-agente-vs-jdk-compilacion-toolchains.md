# ADR-0006: JDK del agente vs JDK de compilación (Maven Toolchains)

- **Estado:** Aceptado
- **Fecha:** 2026-09-12
- **Decisor:** DevOps Team

## Contexto

El JDK con el que corre el **agente** (runtime del agente Jenkins) es
independiente del JDK con el que se **compila** la aplicación:

- El controller de este laboratorio corre sobre **Java 21**, así que lo natural
  es que el agente use también **JDK 21** (misma familia, sin sorpresas).
- La aplicación de referencia (Spring Boot 3) declara `java.version=17` y debe
  compilarse con **JDK 17** (su toolchain "acorde").

Un solo JDK no cubre bien ambos papeles si queremos fijar la versión de
compilación sin atar el runtime del agente. Si además el día de mañana otro
proyecto pide JDK 8, 11 o 21, no queremos una imagen de agente distinta por
cada versión de compilación.

## Decisión

1. **Todas las imágenes-agente parten de la misma base**
   `docker.io/jenkins/inbound-agent:latest-rhel-ubi9-jdk21` (UBI 9 + JDK 21).
   El **runtime del agente** queda así alineado con el controller.

2. **El JDK de compilación se aporta como _toolchain_ de Maven** en la imagen
   que lo necesita:
   - `agent-maven-jdk17` instala `java-17-openjdk-devel` y publica
     `/opt/toolchains/toolchains.xml` declarando el JDK 17.
   - El `pom.xml` activa `maven-toolchains-plugin` pidiendo la versión 17.
   - El pipeline invoca Maven con `-t /opt/toolchains/toolchains.xml`.

   Con esto, Maven y el agente corren sobre JDK 21, pero `javac` es el del
   toolchain JDK 17. Maven es agnóstico al JDK: la toolchain decide el compilador.

3. `toolchains.xml` (en la imagen) tiene esta forma, con `jdkHome` resuelto en
   build-time al path real del paquete:

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

4. En el pipeline:

   ```groovy
   sh '''
     cd backend
     mvn -t /opt/toolchains/toolchains.xml \
         -Dmaven.repo.local=/cache/.m2/repository \
         -B -ntp clean package
   '''
   ```

## Consecuencias

### Positivas

- **Una sola base de agente** (UBI 9 + JDK 21) para todo el laboratorio: runtime
  coherente con el controller y menos imágenes que mantener.
- La **versión de compilación es explícita y versionada** (`toolchains.xml` +
  `maven-toolchains-plugin`), no un efecto colateral del JDK del agente.
- Añadir otro JDK de compilación (11, 21…) es instalar el paquete y ampliar
  `toolchains.xml`; no obliga a cambiar la base del agente.

### Negativas

- El `pom.xml` queda **acoplado a que exista un toolchains.xml** válido: si se
  ejecuta `mvn` sin `-t`, el build falla con
  `Cannot find matching toolchain definitions`. Es intencionado (falla rápido
  y claro), pero hay que documentarlo (ver `reference-app/README.md`).
- La imagen `agent-maven-jdk17` pesa más (JDK 21 + JDK 17 + Maven).

### Neutras / trade-offs

- Si se prefiere no usar toolchains, la alternativa es compilar con
  `--release 17` sobre el JDK 21 (bytecode/API de 17). Se descarta aquí porque
  el objetivo del laboratorio es **demostrar el uso de toolchains**.

## Alternativas consideradas

- **Imagen por cada JDK de compilación** (base `...-jdk17`): más simple en el
  `pom`, pero multiplica imágenes y ata el runtime del agente a la versión de
  compilación.
- **Fijar `JAVA_HOME` al JDK 17 en el stage**: funciona, pero afecta a todo el
  proceso Maven (no solo a la compilación) y no es declarativo en el `pom`.
- **`maven.compiler.release=17`**: válido y recomendable en general, pero no
  ejemplifica el mecanismo de toolchains que se quiere enseñar.

## Referencias

- `agent-images/agent-maven-jdk17/Containerfile` y `toolchains.xml`
- `jenkins-config/samples/reference-app/backend/pom.xml`
- `jenkins-config/jobs/reference-pipeline.groovy`
- `docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md` (sección 5.3)
- Guía de Maven Toolchains: https://maven.apache.org/guides/mini/guide-using-toolchains.html
