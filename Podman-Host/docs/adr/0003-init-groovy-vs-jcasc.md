# ADR-003: Plan B — scripts init.groovy.d en lugar de JCasC declarativo

- **Estado:** Aceptado (con JCasC como objetivo futuro)
- **Fecha:** 2026-09-09
- **Decisor:** DevOps Team

## Contexto

La guia original del proyecto pedia usar **Jenkins Configuration as Code (JCasC)** para definir toda la configuracion del Controller de Jenkins (admin local, nodos, plugins, etc.) en un fichero `jenkins.yaml` declarativo.

JCasC se activa al arrancar Jenkins pasando `-Dcasc.jenkins.config=<path>` como JAVA_OPTS. El plugin lee el YAML y configura Jenkins durante la inicializacion, ANTES de que la UI este disponible.

En este laboratorio se intento aplicar JCasC declarativo y fallo repetidamente con el error:

```
io.jenkins.plugins.casc.UnknownConfiguratorException: No configurator found for the
following root elements: timestamper, configuration-as-code-groovy, configFileProvider,
buildTimeoutWrapper, jcasc, docker.
```

Los plugins estaban correctamente descargados en `/var/lib/jenkins/plugins/*.jpi` y los logs de Jenkins mostraban `Started all plugins`. Pero JCasC decia "no encuentro los configuradores".

## Diagnostico

El problema es un **bug de orden de carga** entre JCasC y los plugins en Jenkins 2.568.3 LTS:

1. JCasC ejecuta su `init()` durante la fase `INIT` del reactor de Jenkins, antes de que los plugins extiendan el sistema de `Configurator`.
2. Aunque los `.jpi` estan en disco, sus clases `Configurator` no estan registradas en el momento en que JCasC itera la lista de configuradores.
3. Jenkins 2.568 introdujo cambios en el ciclo de vida de plugins que rompieron la suposicion original de JCasC ("los plugins ya estan listos cuando init() corre").

Es un bug conocido: https://github.com/jenkinsci/configuration-as-code-plugin/issues/2895

## Decision

Adoptamos el **Plan B: scripts Groovy nativos en `$JENKINS_HOME/init.groovy.d/`**.

Jenkins ejecuta automaticamente todos los `*.groovy` en ese directorio al arrancar, **en orden alfabetico**. Lo que hacemos:

1. **`01-create-admin.groovy`** — Crea el usuario `admin` con password y configura el realm de seguridad local.
2. **`02-create-agent.groovy`** — Crea el nodo permanente `podman-host-almalinux9` con launcher JNLP+WebSocket.
3. **`03-create-pipeline.groovy`** — Crea el job `reference-pipeline` leyendo el script desde `jobs/reference-pipeline.groovy`.
4. **`04-create-config-files.groovy`** — Crea los "Managed Config Files" del plugin Config File Provider (`maven-settings-modern`, `npmrc-frontend`) que el `reference-pipeline` consume via `configFileProvider([configFile(...)])`. Sin JCasC, el bloque `configFileProvider:` de `jenkins.yaml.j2` nunca se aplica, asi que estos ficheros tambien hay que crearlos de forma imperativa (ver ADR-009).

Las variables (nombre del nodo, workdir, label) se pasan al Container via variables de entorno, definidas en el drop-in de systemd `/etc/systemd/system/jenkins.service.d/override.conf` y leidas con `System.getenv()` en cada Groovy.

El resto de la configuracion (plugins, init script, drop-in) **se mantiene identico al plan original**. Solo cambia el mecanismo de provision.

## Consecuencias

### Positivas

- **Funciona siempre.** `init.groovy.d/` es un mecanismo nativo de Jenkins que lleva ahi desde la version 1.x. No depende de plugins externos ni de orden de carga.
- **El orden es determinista** (alfabetico por nombre de fichero), lo que da un comportamiento repetible.
- **No hay versionado de plugins acoplado.** Cualquier plugin funciona con cualquier configuracion.
- **Debug facil:** los Groovy scripts son codigo fuente que se imprime en `journalctl`.

### Negativas

- **No es declarativo como JCasC.** Cambiar un valor obliga a re-aplicar el playbook completo. Con JCasC, basta con editar el YAML.
- **No hay dry-run / diff.** JCasC soporta "reload" en caliente. Plan B requiere restart completo de Jenkins.
- **Los secrets se pasan por variables de entorno**, lo que los expone a procesos hijos. No es problema en este laboratorio (las VMs son dedicadas) pero en produccion habria que usar Jenkins Credentials Store.
- **Si la guia oficial dice "usa JCasC", divergimos.** Documentamos esto en el README para que quede claro que es una decision deliberada, no un olvido.

### Neutras / trade-offs

- Toda la logica de provision esta duplicada: las plantillas Groovy en `jenkins-config/groovy/` (referencia) y los scripts en el repo (fuente). Hay que mantener ambos sincronizados.

## Migracion futura a JCasC

Cuando el bug de JCasC este resuelto (o cuando actualicemos a una version de Jenkins donde funcione correctamente), el plan de migracion es:

1. Instalar el plugin `configuration-as-code` (ya esta en `plugins.yaml`).
2. Mover los tres scripts Groovy a un unico `jenkins.yaml` declarativo.
3. Cambiar el `override.conf` para que apunte a `-Dcasc.jenkins.config=...`.
4. El plan de provision pasaria de "ejecutar Groovy en primer arranque" a "aplicar YAML en cada arranque".

Los scripts Groovy actuales son la **fuente de verdad** de la configuracion y pueden migrarse 1-a-1 a JCasC sin perder funcionalidad.

## Alternativas consideradas

- **JCasC con versiones antiguas de Jenkins (< 2.500):** Descartada por quedar fuera de soporte y por usar versiones con bugs de seguridad conocidos.
- **JCasC con plugin `configuration-as-code-groovy` para mezclar ambos:** Descartada por no resolver el bug de orden de carga.
- **Terraform + Jenkins Provider:** Descartada por enorme sobreingenieria para un laboratorio local.
- **Setup wizard manual + post-install scripts:** Descartada por ser no reproducible. El operador tendria que hacer clic en 20 pantallas cada vez que destruye el lab.

## Referencias

- https://github.com/jenkinsci/configuration-as-code-plugin
- https://www.jenkins.io/doc/book/managing/init-groovy/
- https://issues.jenkins.io/browse/JENKINS-73287 (bug tracking de Jenkins, no directamente nuestro bug pero contexto)
