# ADR-009: Aplicación de referencia (Java/Maven + Angular/npm) sin SCM

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisor:** DevOps Team

## Contexto

El `reference-pipeline` necesitaba código real para poder probarse
end-to-end: hasta ahora solo comprobaba `if [ -f backend/pom.xml ]`
y similares, saltándose los stages si no encontraba nada. Sin código,
no se podía validar que el flujo completo (compilar → empaquetar en
imagen de contenedor) funcionase.

Además, al revisar el pipeline en detalle se encontraron dos bugs
latentes que nunca se habían manifestado porque el pipeline nunca se
había ejecutado con código real:

1. **Los Managed Config Files no existen.** El pipeline usa
   `configFileProvider([configFile(fileId: 'maven-settings-modern', ...)])`
   y `configFile(fileId: 'npmrc-frontend', ...)`. Esos ficheros se
   definían solo en JCasC (`jenkins-config/casc/jenkins.yaml` y su
   plantilla `jenkins.yaml.j2`), pero JCasC **no se aplica nunca** en
   el playbook actual (ver ADR-003: Plan B sin JCasC). Sin un fix,
   el primer build real habría fallado con
   `No settings.xml file with fileId 'maven-settings-modern' found`.
2. **El mirror de Maven/npm en esos ficheros era un dominio ficticio**
   (`artifactory.mi-empresa.local`), heredado de la guía original. Aun
   arreglando (1), el build habría fallado por no poder resolver ese
   host.

## Decisión

### Aplicación de referencia

- **Backend:** Java 17 + Spring Boot 3.5.9 + Maven. Expone
  `GET /api/hello` y `GET /api/version`. Sin base de datos: el
  objetivo es validar el pipeline, no modelar una arquitectura real.
- **Frontend:** Angular 20 (standalone components + signals) + npm.
  Consume el backend en `http://localhost:8080` desde el navegador
  (no desde el contenedor), decisión que simplifica las pruebas
  manuales con `podman-compose.yml` a costa de acoplar la URL del
  backend al puerto publicado en el host.
- **Sin SCM:** el código vive en `jenkins-config/samples/reference-app/`
  dentro del propio repo del laboratorio. Ansible lo copia al workspace
  real del job (`{{ jenkins_workspace_dir }}/workspace/reference-pipeline/`)
  en cada `deploy.sh`, vía una tarea nueva en
  `ansible/roles/podman_host/tasks/main.yml`. Evita depender de un
  servidor Git adicional solo para este laboratorio.

### Managed Config Files sin JCasC

Se crea un cuarto script, **`04-create-config-files.groovy`**, en la
misma línea que ADR-003: usa la API Java del plugin Config File
Provider (`GlobalConfigFiles.get().save(new CustomConfig(...))`)
para crear `maven-settings-modern` y `npmrc-frontend` de forma
imperativa, idempotente, en el arranque de Jenkins.

### Mirrors reales en vez del placeholder ficticio

Se sustituye `artifactory.mi-empresa.local` por:
- Maven: `https://repo.maven.apache.org/maven2/` (Maven Central).
- npm: `https://registry.npmjs.org/` (registry público de npm).

Esto se corrige en **tres sitios** para mantenerlos consistentes:
`jenkins-config/groovy/create-config-files.groovy` (la fuente real
que se ejecuta), `ansible/roles/jenkins_controller/templates/jenkins.yaml.j2`
y `jenkins-config/casc/jenkins.yaml` (ambos quedan como documentación
de lo que se aplicaría si JCasC funcionase, ahora con cabeceras que
dejan claro que no se usan).

El tag de las imágenes de contenedor (`artifactory.mi-empresa.local/backend-app:${BUILD_NUMBER}`)
**se mantiene igual** en `reference-pipeline.groovy`: no requiere red
(es solo un nombre local en el almacén de Podman) y documenta dónde se
haría el `podman push` en un entorno real. Se añade un segundo tag,
`reference-backend:latest` / `reference-frontend:latest`, que es el
que consume `podman-compose.yml` para no depender del número de build.

## Consecuencias

### Positivas

- El pipeline ahora se puede ejecutar de extremo a extremo con código
  real, sin pasos manuales adicionales tras `deploy.sh`.
- El bug de los Managed Config Files queda corregido en el código,
  no como parche puntual: cualquier `destroy.sh && deploy.sh` futuro
  funciona solo.
- `podman-compose.yml` permite validar manualmente que backend y
  frontend funcionan juntos, sin depender de Jenkins.

### Negativas

- El código de la reference-app vive en el repo del laboratorio, no en
  un repositorio de aplicación separado; si el laboratorio creciera a
  probar más apps, esto no escala (habría que introducir un SCM real).
- La URL fija `localhost:8080` en el frontend es una simplificación
  deliberada, no una arquitectura de producción.

### Neutras / trade-offs

- Cambiar el mirror de Maven Central/npm público implica que el
  podman-host necesita salida a internet para que el pipeline
  funcione (ya la necesitaba para descargar los plugins de Jenkins y
  las imágenes UBI9, así que no es un requisito nuevo).

## Alternativas consideradas

- **Levantar un servidor Git local (Gitea/Gogs) solo para este lab:**
  Descartada por sobreingeniería; el objetivo es probar el pipeline,
  no gestionar código fuente.
- **Usar JCasC solo para `configFileProvider` y Plan B para el resto:**
  Descartada por inconsistencia: mezclar dos mecanismos de
  configuración distintos para el mismo Jenkins es más difícil de
  mantener que un enfoque único (Plan B para todo).
- **Dejar el mirror ficticio y documentar que "hay que cambiarlo":**
  Descartada porque contradice la regla del laboratorio de que un
  `deploy.sh` limpio debe funcionar sin intervención manual.

## Referencias

- `docs/adr/0003-init-groovy-vs-jcasc.md`
- `jenkins-config/samples/reference-app/README.md`
- https://github.com/jenkinsci/config-file-provider-plugin
