# Binarios manuales para agent-jdk6-ubi8

Este directorio contiene los binarios de Oracle JDK 6 que **no se pueden
descargar automaticamente** desde una URL publica (Oracle dejo de
distribuir JDK antiguos publicamente en 2023).

## Por que existe este directorio

Las imagenes legacy de Java que usan JDK 6 uan Oracle JDK propietario
que Red Hat no distribuye en UBI. Para construir esta imagen necesitas
descargar los binarios manualmente desde tu cuenta de Oracle (o desde un
repositorio interno de tu empresa que los tenga cacheados).

## Que archivo poner aqui

Coloca el archivo **`jdk-6uXX-linux-x64.bin`** (donde XX es el ultimo
patch level, actualmente 6u45) en este directorio antes de hacer `podman
build`.

### Como conseguir el binario

1. Ir a https://www.oracle.com/java/technologies/javase-java-archive-downloads.html
2. Buscar "Java SE 6uXX".
3. Descargar el `.bin` para Linux x64 (requiere login con cuenta
   Oracle).
4. Copiarlo a este directorio con el nombre exacto:
   ```bash
   cp /path/to/download/jdk-6u45-linux-x64.bin \
      /home/user/Ephemeral-Jenkins-Agents-Architectures/Podman-Host/legacy-images/agent-jdk6-ubi8/binaries/
   ```

### Alternativa: repositorio interno de la empresa

Si tu empresa tiene un mirror interno (Nexus, Artifactory) con binarios
Oracle cacheados:

1. Subir el binario al mirror.
2. Cambiar la URL en el `Containerfile`:
   ```
   COPY binaries/jdk-6u45-linux-x64.bin /tmp/jdk-installer.bin
   ```
   (El Containerfile ya tiene esta instruccion; solo cambia el `COPY` si
   quieres descargar desde otro sitio en lugar de copiar local).

### Que pasa si no pones nada

El `podman build` fallara con un error claro:
```
ERROR: failed to solve: "/binaries/jdk-6u45-linux-x64.bin": not found
```

Esto es intencional: prefieres un fallo explicito a una imagen construida
con un binario incorrecto.

## Licencia

Oracle JDK 6 esta bajo la **Oracle Binary Code License (BCL)**, que es
distinta de la OpenJDK. Usar JDK 6 implica aceptar los terminos de la
BCL. Para entornos donde esto es un problema, considera:

- Compilar el codigo legacy contra JDK 8 con flags de retrocompatibilidad
  (`-source 1.6 -target 1.6`). Esto suele funcionar para el 90% de los
  proyectos.
- Migrar el codigo a una version LTS soportada.

Ver `README.md` del directorio padre para mas detalles.
