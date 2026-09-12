# ADR-004: Leer el secret del agente desde la API REST de Jenkins

- **Estado:** Aceptado
- **Fecha:** 2026-09-09
- **Decisor:** DevOps Team

## Contexto

El handshake WebSocket entre un agente JNLP y el Jenkins Controller requiere que ambos extremos compartan un **secret**. La secuencia esperada:

1. Jenkins genera el secret al crear el nodo permanente (almacenado cifrado en disco).
2. Jenkins muestra el secret al usuario via la UI, tipicamente embebido en el comando `java -jar agent.jar` que el usuario debe ejecutar en la maquina remota.
3. El agente se conecta al Controller enviando el secret.
4. El Controller valida que coincide con el almacenado y acepta la conexion.

En la implementacion inicial de este laboratorio, el rol `agent_registration` de Ansible:

1. Generaba un secret aleatorio (`secrets.token_hex(32)`).
2. Lo escribia en `/datos/jenkins/agent/secret-file` del Podman Host.
3. La unidad `jenkins-agent.service` arrancaba con `-secret @/datos/jenkins/agent/secret-file`.

Esto producia **siempre** el siguiente error en `journalctl` del Controller:

```
WARNING jenkins.agents.WebSocketAgents#doIndex: incorrect secret for podman-host-almalinux9
```

Y en el log del agente:

```
WARNING: Did not receive X-Remoting-Capability header
INFO: Failed to connect: Handshake error.
```

## Causa raiz

El secret almacenado en Jenkins para ese nodo (en `/var/lib/jenkins/secrets/jenkins.slaves.JnlpSlaveAgentProtocol.secret`) **no era** el mismo que el que Ansible generaba aleatoriamente. Por tanto, el Controller rechazaba cada intento de conexion con "incorrect secret".

Este problema es **invisible si Ansible nunca intenta conectar el agente** (lo cual era el caso en mis primeros intentos: yo paraba el playbook en el error de JCasC sin llegar a levantar el agente). Solo aparece cuando se intenta el handshake real.

## Decision

Adoptamos como unica fuente de verdad del secret al **Controller**. El flujo correcto es:

1. **No generar ningun secret en Ansible.**
2. Tras crear el nodo (por el script `02-create-agent.groovy` que corre en `init.groovy.d/`), Jenkins ya tiene su secret interno.
3. Ansible lo lee desde el endpoint REST **`/computer/<nombre>/jenkins-agent.jnlp`** con basic auth.
4. Ese endpoint devuelve un XML JNLP cuyo primer `<argument>` es el secret en claro.
5. Ansible parsea ese XML, extrae el secret y lo escribe en `/datos/jenkins/agent/secret-file` del Podman Host.
6. La unidad `jenkins-agent.service` arranca con `-secret @secret-file` y el handshake funciona.

El task Ansible queda asi:

```yaml
- name: Leer el secret REAL del nodo desde Jenkins (JNLP endpoint)
  ansible.builtin.uri:
    url: "http://127.0.0.1:{{ jenkins_http_port }}/computer/{{ jenkins_agent_node_name }}/jenkins-agent.jnlp"
    url_username: "{{ jenkins_admin_user }}"
    url_password: "{{ jenkins_admin_password }}"
    force_basic_auth: true
    return_content: true
  register: jnlp_xml

- name: Extraer el secret del XML JNLP
  ansible.builtin.set_fact:
    podman_agent_secret: "{{ (jnlp_xml.content | regex_search('<argument>([a-f0-9]{64,})</argument>', '\\1') | first) }}"
```

## Consecuencias

### Positivas

- **El secreto correcto se propaga siempre**, porque Ansible lo lee del Controller en tiempo de provision. No hay drift entre lo que cree Jenkins y lo que recibe el agente.
- **El secreto sigue siendo un secreto real de 256 bits** (Jenkins genera `secrets.token_hex(32)` en el primer arranque). No usamos un secreto debil o predecible.
- **El proceso es reproducible**: si Jenkins regenera el secreto, Ansible lo relleera en el siguiente run.
- **Funciona con cualquier nodo creado por la UI, la API o scripts Groovy**, no solo con JCasC.

### Negativas

- **Acoplamos Ansible a una API REST especifica** (`/jenkins-agent.jnlp`). Si Jenkins cambia el formato del XML o el endpoint, hay que actualizar el regex.
- **El secreto se almacena en texto plano en `/datos/jenkins/agent/secret-file`** (con `mode: 0400` propiedad de `jenkins:jenkins`). Esto es aceptable en este laboratorio, pero en produccion habria que usar un Credential Store.

### Neutras / trade-offs

- Se mantiene la propagacion via `copy` y `delegate_to`. Esto es robusto y verificable, aunque menos elegante que un ssh-forwarding directo.

## Como crear un nodo manualmente para verificar

El usuario puede reproducir el alta manual de un agente para entender el flujo:

1. En la UI de Jenkins: `Manage Jenkins > Nodes > New Node`.
2. Nombre: `test-node`, tipo: `Permanent Agent`.
3. Marcar `Launch agent via Java Web Start` o `Launch agent via JNLP` con `webSocket: true`.
4. Guardar.
5. En la pagina del nodo, copiar el comando `java -jar agent.jar ...` que Jenkins muestra.
6. Ejecutarlo en cualquier maquina con JRE. El handshake debe funcionar al primer intento.

## Alternativas consideradas

- **Generar el secret en Ansible y registrarlo en Jenkins via API:** Descartada. Jenkins genera su propio secret al crear el nodo y no hay API documentada para "pre-registrar" un secret antes de la creacion del nodo. Ademas, hacerlo requeriria parchear JCasC o init.groovy, anadiendo complejidad.
- **Leer el secreto del fichero cifrado en disco** (`/var/lib/jenkins/secrets/jenkins.slaves.JnlpSlaveAgentProtocol.secret`): Descartada. El fichero esta cifrado con la clave maestra (`secret.key.not-so-secret`) y desencriptarlo requiere ejecutar codigo dentro del JVM de Jenkins o portar el algoritmo a Python. Es mucho mas complicado que hacer una llamada REST.
- **Usar `-secret @/etc/jenkins/secrets/...` en el agente:** Descartada. El fichero esta cifrado, no se puede usar directamente.

## Referencias

- https://javadoc.jenkins.io/agent/agent/WebSocketAgents.html
- https://github.com/jenkinsci/remoting/blob/master/docs/agent-protocol.md
