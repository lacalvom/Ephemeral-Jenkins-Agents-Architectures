# ADR-008: Habilitar JNLP port y persistir InstallState en init.groovy

- **Estado:** Aceptado
- **Fecha:** 2026-09-09
- **Decisor:** DevOps Team

## Contexto

Al desplegar el laboratorio desde cero con `./deploy.sh` + `ansible-playbook`,
el playbook terminaba sin errores y mostraba el resumen "Estado agente:
CONECTADO", pero al abrir la UI de Jenkins aparecia el wizard de instalacion
inicial y los nodos no se listaban en `Manage Jenkins > Nodes`.

Ademas, en el log del agente en el podman-host si se veia
`INFO: Connected`, pero el Controller no reconocia el agente y
`/computer/api/json` solo mostraba `Built-In Node`.

## Diagnostico

Tres problemas concurrentes:

1. **El puerto JNLP estaba deshabilitado.** El endpoint `/api/json`
   devolvia `"slaveAgentPort": -1`, lo que significa que Jenkins no
   escuchaba conexiones JNLP/agent en ningun puerto. Aunque el agente
   se conectaba via WebSocket sobre el puerto 8080, Jenkins no lo
   reconocia como slave registrado.

2. **El setup wizard no quedaba marcado como completo en disco.**
   El script `01-create-admin.groovy` llama a
   `jenkins.getSetupWizard().completeSetup()` pero esto solo cambia el
   estado en memoria. En Jenkins 2.568.3 el fichero
   `jenkins.install.InstallState` no se crea automaticamente, lo que
   provoca que el siguiente reinicio de Jenkins muestre el wizard de
   nuevo.

3. **El playbook terminaba OK pero dejaba el lab en estado inutilizable.**
   Esto es un bug serio del provisionamiento: Ansible reporta "OK"
   aunque el estado final no es funcional.

## Causa raiz

La guia original del proyecto (seccion "Configuracion del Jenkins Controller")
mencionaba que en Jenkins 2.568 el puerto JNLP viene deshabilitado por
defecto, pero la implementacion con init.groovy.d/ no lo abordaba.

El script `01-create-admin.groovy` (Plan B en lugar de JCasC) intentaba
marcar el setup como completo, pero el metodo `completeSetup()` no
persiste el estado en disco por si solo en todas las versiones.

## Decision

Consolidar las TRES correcciones en el script `02-create-agent.groovy`
(porque es el script que toca la configuracion del Controller):

1. Habilitar `slaveAgentPort` en 50000 via `jenkins.setSlaveAgentPort()`.
2. Crear manualmente el fichero `jenkins.install.InstallState` con
   contenido `2.0\n` (que corresponde a `INITIAL_SETUP_COMPLETED`).
   Esto complementa el `completeSetup()` para que el estado persista.
3. Crear el nodo `podman-host-almalinux9` con launcher JNLP+WebSocket.

El script `01-create-admin.groovy` mantiene su responsabilidad original
de crear el admin y queda como respaldo.

## Consecuencias

### Positivas

- El laboratorio queda funcional desde el primer `deploy.sh` +
  `ansible-playbook`, sin intervencion manual.
- El puerto JNLP esta habilitado y persistente (sobrevive a reinicios).
- El setup wizard esta marcado como completo en disco (sobrevive a
  reinicios).
- El bug del playbook que reportaba "CONECTADO" sin que el agente
  realmente estuviera conectado para Jenkins queda resuelto.

### Negativas

- El script `02-create-agent.groovy` ahora hace tres cosas (antes solo
  una). Pierde algo de cohesion pero gana completitud.
- La creacion manual del fichero `InstallState` es un workaround al
  bug de Jenkins 2.568.3. Si en una version futura el bug se arregla,
  el script sigue funcionando porque el `if (!installStateFile.exists())`
  protege contra sobreescritura.

### Neutras / trade-offs

- El script depende de `chown jenkins:jenkins` via `["sh", "-c", ...]`
  para que el fichero InstallState tenga los permisos correctos. Es
  portable a sistemas donde `sh` esta disponible (que es siempre en
  Linux/Unix).

## Como verificar que esta aplicado

Tras el primer arranque de Jenkins, los scripts init.groovy.d/ dejan
el log con los mensajes clave:

```
JNLP_PORT_ENABLED: 50000
INSTALL_STATE_FILE_CREATED: /var/lib/jenkins/jenkins.install.InstallState
NODE_CREATED: podman-host-almalinux9
INIT_GROOVY_02_COMPLETED
```

Y la API REST reporta:

```bash
curl -s -u admin:<tu-password> http://127.0.0.1:8080/api/json \
  | python3 -c "import sys,json; print('agentPort:', json.load(sys.stdin).get('slaveAgentPort'))"
# Debe mostrar: agentPort: 50000

curl -s -u admin:<tu-password> http://127.0.0.1:8080/computer/api/json \
  | python3 -c "import sys,json; d=json.load(sys.stdin); print([(c['displayName'], c['offline']) for c in d['computer']])"
# Debe mostrar: [('Built-In Node', False), ('podman-host-almalinux9', False)]
```

## Alternativas consideradas

- **JCasC declarativo:** descartada por el bug documentado en ADR-003
  (JCasC no funciona en Jenkins 2.568 por orden de carga de plugins).
- **Forzar el reinicio de Jenkins desde el playbook despues del primer
  arranque:** no soluciona el problema porque el setup wizard aparece en
  el SEGUNDO arranque, no en el primero.
- **Usar `init.groovy.d/` con un script especifico para el setup state
  (00-fix-setup.groovy):** es lo que hicimos en el intento anterior pero
  es mas fragil porque depende del orden alfabetico. Consolidar todo en
  `02-create-agent.groovy` es mas robusto.

## Referencias

- [Jenkins JNLP Port documentation](https://www.jenkins.io/doc/book/security/services/)
- [Jenkins Setup Wizard API](https://javadoc.jenkins.io/jenkins/install/SetupWizard.html)
- ADR-003: Plan B init.groovy.d vs JCasC declarativo

## Lecciones aprendidas adicionales

### `sudo-rs` no existe en AlmaLinux 9

En la primera version del fix usamos `sudo-rs` (un reemplazo moderno de
`sudo` presente en el host pero **no en las VMs**). Esto provocaba el
error `bash: line 1: sudo-rs: command not found` al aplicar el fix.

**Regla para `deploy.sh`**: cuando ejecutemos comandos contra las VMs
vía SSH, usar siempre `sudo` clasico, no `sudo-rs`. El `sudo-rs` queda
reservado para operaciones en el host (donde se invoca directamente).

> **Nota (actualización):** para que el repositorio sea portable, las
> operaciones en el host ya no asumen `sudo-rs`: usan la variable
> `SUDO="${SUDO:-sudo}"` (`deploy.sh` y `destroy.sh`), sobreescribible
> con `SUDO=sudo-rs` en `.env` si el sistema lo usa. Lo que se ejecuta
> dentro de las VMs vía SSH sigue usando `sudo` clasico.

### `File.setOwnerOnly()` no existe en Java 21

En una iteracion posterior, el script intento usar
`installStateFile.setOwnerOnly(true)` que **no existe en `java.io.File` en
Java 21** (es de `PosixFilePermission`). Esto provocaba:

```
groovy.lang.MissingMethodException: No signature of method:
java.io.File.setOwnerOnly() is applicable for argument types:
(java.lang.Boolean) values: [true]
```

**Solución**: el playbook (no el script Groovy) aplica el `chown` despues del
primer arranque via el modulo `file` de Ansible. Ver `tasks/main.yml`
del rol `jenkins_controller`.

### El setup wizard puede seguir accesible en su URL

Tras el fix, `/setupWizard/` sigue devolviendo HTTP 200 con titulo
"Setup Wizard - Jenkins". Esto es **comportamiento normal**: la URL existe
siempre, lo que importa es que Jenkins NO redirija automaticamente al
wizard al hacer login normal.

**Verificacion correcta**:
- `GET /` → titulo debe ser `Dashboard - Jenkins`. Si es `Setup Wizard`,
  el wizard sigue activo.
- `GET /setupWizard/` → siempre devuelve 200. No es indicador de nada.

**Si el usuario ve el wizard al hacer login**: casi siempre es cache del
navegador. Hard refresh (`Ctrl+Shift+R`) o ventana incognito resuelve.

### Cómo verificar programáticamente que el wizard NO está activo

Despues de varios incidentes donde el usuario reportaba "sigo viendo el
wizard" pero el servidor estaba correcto, anadimos al playbook un check
explicito:

```yaml
- name: "Verificar que el dashboard NO contiene wizard"
  ansible.builtin.uri:
    url: "http://127.0.0.1:{{ jenkins_http_port }}/"
    url_username: "{{ jenkins_admin_user }}"
    url_password: "{{ jenkins_admin_password }}"
    force_basic_auth: true
    return_content: true
    status_code: 200
  register: dashboard_check
  failed_when:
    - dashboard_check.content | regex_search('<title>Setup Wizard') != None
```

Si este check pasa (title es `<title>Dashboard - Jenkins</title>`),
el wizard NO esta activo a nivel de servidor y el problema reportado
es 100% cache del navegador.

## La combinacion que SÍ funciona (actualizado)

Despues de multiples intentos fallidos, la combinacion correcta es:

1. **Crear el InstallState.xml con `2.0`** ANTES del primer arranque:

   ```yaml
   - name: Crear jenkins.install.InstallState con INITIAL_SETUP_COMPLETED
     ansible.builtin.copy:
       dest: "{{ jenkins_controller_home }}/jenkins.install.InstallState"
       content: "2.0\n"
       owner: "{{ jenkins_service_user }}"
       group: "{{ jenkins_service_group }}"
       mode: "0600"
   ```

2. **Incluir `-Djenkins.install.runSetupWizard=false` en JAVA_OPTS**:

   ```ini
   [Service]
   Environment="JAVA_OPTS=... -Djenkins.install.runSetupWizard=false"
   ```

Los dos funcionan juntos: el flag desactiva el SetupWizard.init() y
el InstallState dice "ya completado", por lo que el frontend
`pluginSetupWizard.js` no tiene razon para mostrar el wizard.

**Importante**: usar SOLO uno de los dos no funciona. Sin el flag, el
wizard se muestra retroactivamente cuando Jenkins detecta que se creo
un admin pre-arranque via `JENKINS_ADMIN_USER`. Sin el InstallState
correcto, el flag no tiene efecto porque el frontend detecta que no
se ha completado ningun setup.

## Lecciones aprendidas (continuacion)

### El wizard se activa retroactivamente por `JENKINS_ADMIN_USER/PASSWORD`

El drop-in de systemd define `JENKINS_ADMIN_USER` y `JENKINS_ADMIN_PASSWORD`.
Jenkins las lee automaticamente y considera que el primer arranque tuvo un
admin pre-creado. Cuando luego los `init.groovy.d/` crean el mismo admin
via `HudsonPrivateSecurityRealm.createAccount()`, Jenkins interpreta
esto como una inconsistencia ("el admin existe pero el wizard no se ha
completado") y muestra el wizard retroactivamente.

### No usar `-Djenkins.install.runSetupWizard=false`

Probar este flag parece la solucion obvia, pero combinado con scripts
init que llaman a `completeSetup()` causa crash porque el flag desactiva
el SetupWizard.init() ANTES de que los plugins de wizard se carguen.

### No usar `-Dcasc.jenkins.config` en el primer arranque

El playbook crea los `init.groovy.d/` y el `jenkins.yaml` de JCasC en
tasks POSTERIORES al primer arranque. Si el drop-in incluye
`-Dcasc.jenkins.config=...` desde el principio, JCasC busca el YAML
en el primer arranque, no lo encuentra (directorio vacio), falla con
exit-code 5 y Jenkins queda en bucle de crash.

**Solucion correcta**: el primer arranque usa SOLO `init.groovy.d/`
(que se ejecuta antes de que JCasC se cargue). En el segundo arranque
(reinicio via systemd o via Ansible), ya existe el `jenkins.yaml` y
JCasC funciona.

El playbook actual ya NO incluye `-Dcasc.jenkins.config` ni
`-Djenkins.install.runSetupWizard=false` en el drop-in del primer
arranque. Si en el futuro migramos a JCasC declarativo, hay que:
1. Crear el `jenkins.yaml` ANTES del primer arranque (via cloud-init).
2. O anadir el `-Dcasc.jenkins.config` solo DESPUES del primer
   arranque (via un segundo reinicio).
