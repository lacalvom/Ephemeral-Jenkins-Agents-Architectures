# Binarios manuales para agent-jdk7-ubi8

Este directorio contiene los binarios de Oracle JDK 7 que **no se pueden
descargar automaticamente** desde una URL publica (Oracle dejo de
distribuir JDK antiguos publicamente en 2023).

## Por que existe este directorio

JDK 7 es la version minima para muchas herramientas enterprise antiguas
(algunos ESBs, ciertos frameworks como Play 2.x, etc.). Oracle ya no lo
distribuye publicamente, asi que los binarios deben venir de:

- Una cuenta Oracle con licencia BCL activa.
- Un mirror interno de la empresa (Nexus, Artifactory, etc.).
- Un backup local que tenga el binario.

## Que archivo poner aqui

Coloca el archivo **`jdk-7uXX-linux-x64.tar.gz`** (donde XX es el ultimo
patch level, actualmente 7u80) en este directorio antes de hacer `podman
build`.

### Como conseguir el binario

1. Ir a https://www.oracle.com/java/technologies/javase-java-archive-downloads.html
2. Buscar "Java SE 7uXX".
3. Descargar el `.tar.gz` para Linux x64 (requiere login con cuenta
   Oracle con derecho a downloads legacy).
4. Copiarlo a este directorio con el nombre exacto:
   ```bash
   cp /path/to/download/jdk-7u80-linux-x64.tar.gz \
      /home/user/jenkins-podman-lab/legacy-images/agent-jdk7-ubi8/binaries/
   ```

### Alternativa: repositorio interno de la empresa

Si tu empresa tiene un mirror interno (Nexus, Artifactory) con binarios
Oracle cacheados:

1. Subir el binario al mirror.
2. El `Containerfile` ya tiene la instruccion:
   ```
   COPY binaries/jdk-7u80-linux-x64.tar.gz /tmp/jdk-installer.tar.gz
   ```
3. Si el mirror tiene el binario pero no queremos copiarlo en git, se
   puede cambiar a descarga con autenticacion HTTP basic.

### Que pasa si no pones nada

El `podman build` fallara con un error claro:
```
ERROR: failed to solve: "/binaries/jdk-7u80-linux-x64.tar.gz": not found
```

## Licencia

Oracle JDK 7 esta bajo la **Oracle Binary Code License (BCL)**, que es
distinta de la OpenJDK. Usar JDK 7 implica aceptar los terminos de la
BCL.

JDK 7 es EOL desde julio 2019 (publico) / julio 2022 (comercial).
Para entornos donde esto es un problema, considera migrar a JDK 8+ o
incluso a JDK 11 LTS.

Ver `README.md` del directorio padre para mas detalles.
