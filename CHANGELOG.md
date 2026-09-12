# Changelog

Todos los cambios notables de este proyecto se documentan en este fichero.

El formato sigue [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/),
y este proyecto adhiere a [Semantic Versioning](https://semver.org/lang/es/).

## [Unreleased] — Roadmap de evoluciones

### Correcciones (no breaking)

- **`deploy.sh`: fix post-provisioning de `nsswitch.conf`** para que el DNS
  funcione en AlmaLinux 9 cloud image. Sin este fix, `dnf install` se
  cuelga indefinidamente al intentar descargar paquetes porque
  `getent hosts` no resuelve nombres externos (la base de datos `hosts`
  en `nsswitch.conf` viene con `files` antes que `dns` por defecto).
  Ver [ADR-007](./docs/adr/0007-nsswitch-conf-dns-fix.md) para el
  diagnostico completo. El fix es idempotente y se aplica automaticamente
  a cada VM tras el polling SSH, antes de que Ansible intente instalar
  paquetes.
- **`init.groovy.d/02-create-agent.groovy`: habilitar JNLP port y persistir
  InstallState.** Jenkins 2.568.3 viene con el puerto JNLP deshabilitado
  (`slaveAgentPort = -1`) por defecto, lo que hace que los nodos aparezcan
  offline aunque el agente envie "Connected". Ademas, el setup wizard no se
  marca como completado en disco, asi que reaparece en cada reinicio.
  El script ahora: (1) habilita `slaveAgentPort` en 50000, (2) crea
  manualmente `jenkins.install.InstallState` con contenido `2.0`, y
  (3) crea el nodo. Ver [ADR-008](./docs/adr/0008-jnlp-port-and-installstate-fix.md)
  para el diagnostico completo.
- **`reference-pipeline.groovy` fallaba en el primer build real con
  `No settings.xml file with fileId 'maven-settings-modern' found`.**
  Los Managed Config Files (`maven-settings-modern`, `npmrc-frontend`)
  solo estaban definidos en JCasC (`jenkins.yaml.j2`), que nunca se
  aplica en este laboratorio (Plan B, ver ADR-003). Se anade un cuarto
  script, `init.groovy.d/04-create-config-files.groovy`, que crea esos
  ficheros de forma imperativa usando la API del plugin Config File
  Provider. Ademas se sustituye el mirror ficticio
  `artifactory.mi-empresa.local` (heredado de la guia original, no
  resuelve) por Maven Central y el registry publico de npm. Ver
  [ADR-009](./docs/adr/0009-reference-app-sin-scm.md).
- **Los stages de empaquetado del pipeline (`Empaquetar Imagen Backend/Frontend`)
  fallaban con `Error: statfs /run/user/1100/podman/podman.sock: no
  such file or directory`.** El rol `podman_host` habilitaba el linger
  del usuario `jenkins` y desplegaba `jenkins-agent.service`, pero
  nunca arrancaba la unidad vendor `podman.socket` (el socket rootless
  de Podman viene deshabilitado por defecto tras instalar el paquete).
  Sin ese socket, el `-v .../podman.sock:...` que monta cada agente
  efimero de Podman no tiene nada que montar. Se anade la tarea que
  habilita y arranca `podman.socket` como servicio `--user`, mas una
  verificacion explicita (`stat` + `fail`) para que un fallo similar se
  detecte durante el `ansible-playbook`, no dentro de un build de
  Jenkins. Ver [ADR-010](./docs/adr/0010-habilitar-podman-socket.md).
- **Con el socket ya presente, los mismos stages fallaban con
  `permission denied` al conectar, a pesar de que los permisos POSIX
  del socket eran correctos.** Causa: el volumen se montaba con el
  sufijo `:z`, que relabela el socket a un contexto SELinux generico
  de "contenido compartido", sobrescribiendo el label especifico que
  la politica de `container-selinux` ya tenia autorizado para que
  contenedores hagan `connectto` sobre el socket de Podman
  ("Podman-outside-of-Podman"). Se sustituye `:z` por
  `--security-opt label=disable` en los dos stages de empaquetado,
  siguiendo la recomendacion oficial de la documentacion de
  troubleshooting de Podman para "contenido de sistema". Ver
  [ADR-011](./docs/adr/0011-security-opt-label-disable-podman-socket.md).
- **`podman compose -f podman-compose.yml up -d` fallaba en el
  podman-host con `looking up compose provider failed`.** AlmaLinux 9
  no trae instalado ningun proveedor de Compose (`docker-compose`,
  plugin `docker compose` v2, ni `podman-compose`) junto con el
  paquete `podman`. Se anade la instalacion de `podman-compose` desde
  EPEL 9 al rol `podman_host`. Ver
  [ADR-012](./docs/adr/0012-instalar-podman-compose.md).
- **`podman_secrets_tooling` nunca creaba ningun secret al pasar
  `enabled_drivers` como lista YAML nativa** (solo funcionaba con el
  formato antiguo `-e "enabled_drivers=[file]"` como string). La
  conversion a lista interna corrompia los elementos con comillas
  sueltas (`"'file'"` en vez de `"file"`), haciendo que ningun
  `when: "'file' in enabled_drivers_list"` coincidiera nunca, sin dar
  ningun error visible. Ademas, varias rutas de idempotencia
  (`creates: /home/jenkins/.gnupg/...`, `.password-store`, la clave
  `age`) no coincidian con donde realmente se escriben esos ficheros
  (las tareas corren como root, no como jenkins, ver ADR-005), lo que
  regeneraba claves GPG/age nuevas en cada ejecucion. Ambos bugs se
  corrigen como parte de la integracion en `site.yml`. Ver
  [ADR-013](./docs/adr/0013-integrar-secrets-tooling-en-site-yml.md).
- **El playbook se quedaba colgado indefinidamente, sin ningun error,
  en `podman_secrets_tooling | DRIVER PASS | Generar clave GPG sin
  passphrase`.** Causa: las VMs se crean con `virt-install` sin
  dispositivo `virtio-rng` (`--rng`), asi que el pool de entropia del
  kernel invitado tarda mucho (o no llega nunca) en tener suficiente
  entropia de calidad para que GnuPG genere una clave RSA. Se anaden
  tres capas de mitigacion: (1) `--rng /dev/urandom` en `virt-install`
  (causa raiz), (2) el paquete `haveged` instalado y arrancado antes
  de cualquier operacion GPG (genera entropia de jitter de CPU, sin
  depender de hardware), y (3) un timeout de 120s con un mensaje de
  fallo explicito si aun asi no hay suficiente entropia, para que el
  playbook nunca vuelva a colgarse en silencio. Ver
  [ADR-014](./docs/adr/0014-entropia-vms-gpg.md).
- **El driver `pass` de Podman Secrets fallaba con "No public key"
  (documentado antes como "requiere TTY interactivo", diagnostico
  incorrecto).** Causa real: la extraccion del fingerprint GPG usaba
  una regex que asumia que el fingerprint era el campo 2 del formato
  `gpg --with-colons`, cuando en realidad es el campo 10. La regex
  nunca hacia match y devolvia la linea original sin modificar,
  guardando un ID invalido en el almacen `pass`. Se corrige extrayendo
  el campo correcto por indice (`split(':')[9]`), validado end-to-end
  (cifrar, registrar el secret, usarlo en un contenedor real). Ver
  [ADR-015](./docs/adr/0015-fix-crypta-y-pass-driver.md).
- **`crypta` (driver `shell`) se elimina: todas sus versiones
  publicadas requieren glibc 2.39+, incompatible con AlmaLinux 9.**
  Ademas, la sintaxis `--opt path=...,arg1=...,arg2=...` de la guia
  original ya no existe en Podman moderno (5.x): el driver `shell`
  real exige 4 comandos (`lookup`/`store`/`list`/`delete`) via
  `--driver-opts`. Se sustituye por un script propio
  (`sops-shell-driver.sh.j2`) que implementa esas 4 acciones llamando
  directamente a `sops`+`age` (sin `crypta`), registrado con el modulo
  `containers.podman.podman_secret` (`driver_opts` + `skip_existing`).
  Validado end-to-end de la misma forma. Ver
  [ADR-015](./docs/adr/0015-fix-crypta-y-pass-driver.md).
- **Los mensajes multi-linea de `debug:` (avisos de `podman_secrets_tooling`)
  se mostraban con `\n` literales en vez de saltos de linea reales.**
  Causa: el callback `default` de Ansible usa formato JSON por defecto
  para los resultados de tareas. Se anade `callback_result_format = yaml`
  a `ansible.cfg`, que muestra los mensajes multi-linea de forma legible.
- **La generacion de la clave `age` fallaba con
  `[Errno 2] No such file or directory: b'age-keygen'`.** `age-keygen`
  se instala en `/usr/local/bin`, que NO esta en el `secure_path` por
  defecto de sudoers en AlmaLinux/RHEL
  (`/usr/sbin:/usr/bin:/sbin:/bin`). Como el task se ejecuta con
  `become` (sudo), el PATH no incluye `/usr/local/bin` y el binario no
  se encontraba aunque estuviera instalado. Se invoca con ruta absoluta
  (`{{ age_install_dir }}/age-keygen`), igual que ya se hacia con `sops`
  dentro del script del driver `shell`.
- **El secret del driver `pass` no se creaba (se saltaba en silencio).**
  La tarea que genera la clave GPG usaba
  `creates: /root/.gnupg/private-keys-v1.d` como guarda de idempotencia.
  Ese directorio puede existir **sin** contener una clave valida (por
  ejemplo, si una generacion anterior se interrumpio a mitad: gpg lo
  crea antes de terminar), asi que el rol se saltaba la generacion para
  siempre y `pass` no tenia clave con la que cifrar. Ademas, la tarea
  de crear el secret tenia `failed_when: false`, que ocultaba el fallo
  (y hacia que el aviso de diagnostico nunca se mostrara, porque
  `is failed` siempre daba `false`). Se corrige: (1) la guarda ahora
  comprueba si existe una clave secreta real
  (`gpg --list-secret-keys`), no el directorio; (2) `pass init` se
  re-ejecuta siempre para apuntar a la clave vigente; (3) se sustituye
  `failed_when: false` por `ignore_errors: true`, que conserva
  `failed: true` en el resultado y hace que el aviso SI se muestre si
  algo va mal. Ver el addendum de
  [ADR-015](./docs/adr/0015-fix-crypta-y-pass-driver.md).

### Cambiado

- **Reestructuracion del repositorio en tres laboratorios por modelo.** El
  repositorio pasa a llamarse **`Ephemeral-Jenkins-Agents-Architectures`** y
  organiza un laboratorio autocontenido por modelo de agentes efimeros:
  - `Podman-Host/` — el laboratorio anterior (Jenkins + Podman Host), movido
    tal cual (deploy/destroy, ansible, jenkins-config, legacy-images).
  - `Podman-Cloud/` y `Jenkins-Kubernetes/` — nuevos directorios con su
    `README.md` y roadmap (lab pendiente de implementar).
  - La **documentacion es comun** en `docs/`: `docs/adr/` (ADRs) y
    `docs/guides/` (antes `docs/Ephemeral-Jenkins-Agents-Architectures/`).
  - `.gitignore` adaptado a las nuevas rutas (`*/aux-files/*`, `*/.env`,
    `*/ansible/group_vars/all/vault.yml`).

- **`podman_secrets_tooling` deja de ser un playbook opcional
  (`ansible/secrets-tooling.yml` + grupo `[podman_secret_hosts]` en
  `hosts.ini`).** Ahora es la Fase 5 de `ansible/site.yml`, se aplica
  siempre sobre `podman_hosts`, con los tres drivers (`file`, `pass`,
  `shell`) y `create_example_secrets: true` activados por defecto.
  `deploy.sh` ejecuta `ansible-playbook site.yml` automaticamente al
  final del provisioning de las VMs, asi que `./deploy.sh` deja el
  laboratorio completo (Jenkins + Podman + Secrets) funcional sin
  ningun paso manual adicional. Ver
  [ADR-013](./docs/adr/0013-integrar-secrets-tooling-en-site-yml.md).
- **Portabilidad del repositorio (para publicarlo): se eliminan todas
  las referencias al usuario/rutas de una persona concreta.**
  - La documentacion (`README.md`, ADRs) usa `<nombre-usuario>` en lugar
    del usuario del autor.
  - `deploy.sh` deduce su directorio raiz de su propia ubicacion (ya no
    hardcodea `/home/<usuario>/...`), y crea en las VMs el mismo usuario
    que ejecuta el script (`VM_USER`, por defecto `$USER`). La clave SSH
    a inyectar es configurable (`SSH_KEY_FILE`).
  - `ansible/hosts.ini` ya no fija `ansible_user`: se deriva de
    `vm_user` en `group_vars/all/vars.yml` (que usa `$VM_USER`/`$USER`).
  - El comando de privilegios del host es configurable
    (`SUDO="${SUDO:-sudo}"` en `deploy.sh` y `destroy.sh`), en vez de
    asumir `sudo-rs` (que no existe en la mayoria de sistemas). El
    `sudo` clasico se sigue usando para lo que se ejecuta dentro de las
    VMs via SSH.
  - Se anade `.gitignore` que excluye `aux-files/` (cloud images, discos
    qcow2, ISOs y `user-data` con la clave SSH publica y datos de la
    maquina) mas caches de Ansible/Python. `deploy.sh` crea `aux-files/`
    si no existe (un clon limpio no lo trae).
  - **El repositorio no contiene ninguna password.** `jenkins_admin_password`
    y `VM_PASSWORD` se definen solo en ficheros locales no versionados
    (`.env` y/o `ansible/group_vars/all/vault.yml`); si faltan, `deploy.sh`
    y el playbook se detienen con un mensaje claro. Se elimina tambien el
    fallback hardcodeado de `create-admin.groovy`.
  - **Secretos externalizados a ficheros no versionados**: `.env`
    (plantilla `.env.example`) para `deploy.sh` (usuario/password de las
    VMs, `SUDO`, rutas, etc.) y `ansible/group_vars/all/vault.yml`
    (plantilla `vault.yml.example`) para Ansible, con prioridad sobre
    `vars.yml`. Ambos estan en `.gitignore`.
  - La password del usuario de las VMs ya no tiene un hash hardcodeado:
    `deploy.sh` lo calcula en tiempo de ejecucion con `openssl passwd -6`
    a partir de `VM_PASSWORD`.
  - `group_vars/all.yml` pasa a `group_vars/all/vars.yml` (estructura de
    directorio, necesaria para el overlay `vault.yml`).
  - `STORAGE_DIR` y `VM_DISK_OWNER` configurables (por defecto
    `/mnt/recursos/vms-storage` y `libvirt-qemu:kvm`; en RHEL/Fedora el
    propietario suele ser `qemu:qemu`).
  - Corregida la documentacion: ya no se exige un storage pool de libvirt
    (`hdd-vms`); los scripts escriben directamente en `STORAGE_DIR`.
  - Eliminados los ficheros `network-config` residuales en `aux-files/`.
  - Anadidos `LICENSE` y `CONTRIBUTING.md`.
  - **Licencia del codigo cambiada de MIT a Apache 2.0.** Se reemplaza el
    `LICENSE` de la raiz por el texto completo de Apache License 2.0 y se
    anade un fichero `NOTICE` con la atribucion. Aporta concesion explicita
    de patentes y terminos de contribucion mas claros. Las guias de
    `docs/guides/` siguen bajo CC BY 4.0.
  - **Atribucion a la marca Cloudsdoers y cabeceras SPDX.** El copyright
    (licencias y `NOTICE`) y los pies de las guias citan a "Cloudsdoers
    (Luis Alberto Calvo Muniz)"; se anade un aviso de marcas en el `NOTICE`
    y el README. Se agregan cabeceras SPDX (`Apache-2.0`) a `deploy.sh`,
    `destroy.sh`, los YAML/plantillas de Ansible y el helper bcrypt.

### Añadido

- **Guias de arquitecturas de agentes efimeros de Jenkins**
  (`docs/guides/`): los tres modelos
  posibles (`Podman-Host` con `docker-workflow`, `Podman-Cloud` con
  `docker-plugin` y `Jenkins-Kubernetes` con `kubernetes-plugin`), una
  comparativa de los tres y un documento unificado. Incluye como se
  gestionan workspace, caches y seleccion de agente en cada modelo.
  Los ficheros llevan prefijo `N_` con el **orden de lectura**
  recomendado (1 comparativa, 2 Podman-Host, 3 Podman-Cloud,
  4 Jenkins-Kubernetes, 5 documento unificado); hay un `README.md` en la
  carpeta a modo de indice.

- **Licencia CC BY 4.0 para las guias** de
  `docs/guides/`: se anade un fichero
  `LICENSE` con el texto legal completo de Creative Commons Atribucion
  4.0 Internacional y un pie de licencia en cada documento. El codigo del
  laboratorio usa aparte una licencia OSI (Apache 2.0; ver mas arriba).

- **Aplicación de referencia funcional** (`jenkins-config/samples/reference-app/`)
  para poder probar el `reference-pipeline` de extremo a extremo:
  backend Java 17 + Spring Boot 3.5.9 + Maven (`GET /api/hello`,
  `GET /api/version`, sin base de datos) y frontend Angular 20
  (standalone components) que consume el backend desde el navegador.
  Ansible copia este codigo al workspace real del job en cada
  `deploy.sh` (no se usa SCM, ver ADR-009), y el propio
  `reference-pipeline.groovy` los compila, los empaqueta en imagenes
  Podman (`reference-backend:latest`, `reference-frontend:latest`, ademas
  del tag con numero de build) y deja un `podman-compose.yml` para
  levantar y probar ambos servicios manualmente sin pasar por Jenkins.



Esta seccion lista las evoluciones posibles del laboratorio, organizadas por
horizonte temporal y prioridad. Ninguna esta implementada todavia; cada
entrada incluye una estimacion de esfuerzo y los prerrequisitos para
empezar.

### Corto plazo (1-2 semanas)

#### Resolver las 3 limitaciones documentadas

- **[ADR-003] Migrar de `init.groovy.d/` a JCasC declarativo**
  - **Estado:** Bloqueado por bug upstream.
  - **Prerrequisito:** Fix en JCasC plugin o en Jenkins core que arregle el
    orden de carga de plugins respecto a `ConfigurationAsCode.init()`.
  - **Opciones de avance mientras tanto:**
    1. **Esperar y revisar periodicamente** (recomendado para este lab).
       Comprobar releases de `configuration-as-code-plugin` cada mes.
    2. **Probar con versiones nightly/edge de JCasC** cuando salgan releases
       nuevas. Bajo coste, bajo riesgo.
    3. **Workaround local**: parchear el plugin localmente con un delay
       en `init()`. Alto coste, anti-patron para lab reproducible.
  - **Plan de migracion cuando se arregle:**
    1. Instalar plugin `configuration-as-code` (ya esta en `plugins.yaml`).
    2. Mover los 3 scripts Groovy a un unico `jenkins.yaml` declarativo.
    3. Cambiar el `override.conf` para apuntar a `-Dcasc.jenkins.config=...`.
    4. Provision pasa de "ejecutar Groovy en primer arranque" a "aplicar
       YAML en cada arranque".
  - **Esfuerzo:** 4-8 horas cuando se desbloquee.

- **[ADR-005] Driver `pass` automatizado sin TTY**
  - **Estado:** Implementacion parcial (binarios OK, clave GPG OK,
    creacion automatica del secret falla).
  - **Trabajo concreto:**
    - Usar `gpg --batch --pinentry-mode loopback --passphrase ''` con
      wrapper que exporte `GPG_TTY=dummy` (valor fijo, no requiere tty).
    - Confirmar que `pass insert -e <name>` funciona en ese contexto.
    - Reemplazar el task actual de `podman secret create --driver pass`
      por una llamada directa a `pass insert` + enlace.
  - **Esfuerzo:** 2-3 horas.

- **[ADR-005] Sustituir `crypta` o compilarlo localmente**
  - **Estado:** `crypta` falla por requerir glibc 2.39 (AlmaLinux 9 trae 2.34).
  - **Opciones:**
    1. **Script bash equivalente** que use `sops --age` directamente
       (~30 lineas de bash).
    2. **Compilar crypta desde fuentes** en una distro compatible (o
       con un build condicional que use glibc 2.34-compatible).
  - **Recomendado:** opcion 1. Codigo simple, sin dependencias extra,
    suficiente para el caso de uso.
  - **Esfuerzo:** 2-4 horas.

#### Mejoras de robustez del lab

- **Smoke-test automatizado end-to-end**
  - **Estado:** No existe. Las verificaciones del README son manuales.
  - **Trabajo concreto:**
    - Script `scripts/smoke-test.sh` que ejecuta los 6 curl/SSH de
      verificacion del README contra un lab levantado y devuelve
      exit 0/1.
    - Comando unico de extremo a extremo:
      ```bash
      ./deploy.sh && \
      (cd ansible && ansible-playbook -i hosts.ini site.yml) && \
      ./scripts/smoke-test.sh && \
      echo "Lab OK"
      ```
  - **Esfuerzo:** 3-4 horas.

- **Tests de idempotencia y convergencia del playbook**
  - **Estado:** No existe.
  - **Tests propuestos:**
    - `assert-idempotent.sh`: re-aplica `ansible-playbook` dos veces y
      comprueba que la segunda ejecucion no genera cambios
      (`changed=0` en todos los tasks).
    - `assert-converges.sh`: destruye el lab, lo recrea, comprueba
      que el hash final del Jenkins es estable (mismo admin, mismo nodo,
      mismo job).
    - `assert-cleanup.sh`: ejecuta `destroy.sh` y comprueba que no quedan
      VMs, ISOs, ni reservas DHCP residuales.
  - **Esfuerzo:** 4-6 horas.

- **Tag `upgrade_jcasc` opt-in en el rol `jenkins_controller`**
  - **Estado:** No existe.
  - **Trabajo concreto:** anadir dos tasks opcionales bajo
    `tags: [upgrade_jcasc]` que permitan probar versiones nuevas de
    JCasC sin tocar el flujo normal del playbook.
    Ver seccion "Corto plazo / migrar JCasC" arriba para el detalle.
  - **Esfuerzo:** 1 hora.

### Medio plazo (1-2 meses)

#### Imagenes custom para proyectos legacy

- **Contexto:** La guia original menciona compilar imagenes con Java 6/7/8
  y Node 8/10/12 que no existen en registries publicos. Hoy el pipeline
  de ejemplo no las usa, pero seria un valor anadido real.

- **Trabajo concreto:**
  - Rol nuevo `legacy_images` que use `podman build` (no Ansible puro,
    porque la construccion es inherentemente imperativa) con cache en
    `/var/lib/jenkins/images/`.
  - Imagenes basadas en `ubi8/ubi-minimal` (compatibles con glibc 2.28+)
    que descargan binarios antiguos de Oracle/Node y los instalan.
  - Publicarlas en un registry interno (`registry.home.lab:5000`)
    levantado con `registry:2` como container en el Controller.
  - Extender `reference-pipeline.groovy` con un stage semanal
    "build legacy images" que las pre-compila.

- **Esfuerzo:** 16-24 horas (incluye investigar binarios legacy disponibles).

#### Backup automatizado del estado de Jenkins

- **Contexto:** El Controller tiene 93 plugins, configs de admin,
  credenciales, el secret del agente y los init scripts. Si se rompe
  el qcow2 del Controller, todo eso se pierde.

- **Opciones:**
  1. **Snapshot LVM/qcow2** con `virsh snapshot-create-as` corriendo
     cada noche desde un cron del host. Rapido, captura todo el estado.
  2. **Exportar `$JENKINS_HOME`** con `restic` o `borg` a un volumen NFS
     o SMB del host. Sobrevive a que la VM muera, versionado.
  3. **Ambas** combinadas: snapshots cada noche, export restic cada
     semana a un destino externo.
- **Recomendado:** opcion 3. Cubre tanto fallos de corrupcion de qcow2
  como perdida del host fisico.

- **Esfuerzo:** 6-10 horas (incluye configurar restic con backend).

#### Monitoring basico

- **Contexto:** Hoy no hay observabilidad del lab. Si el agente se
  desconecta, no nos enteramos hasta que alguien intente usar el
  pipeline y falle.

- **Stack propuesto:**
  - **Prometheus + node_exporter** en ambas VMs (consumo CPU/RAM/disk).
    Ligero (~30 MB RAM por instancia).
  - **Alertmanager** con una regla critica: "agente Jenkins offline > 5 min".
  - **Grafana** con un dashboard preconfigurado (opcional, se puede
    usar Prometheus UI directamente para empezar).

- **Ubicacion del stack:** container en el host fisico (no en las VMs).
  Asi sobrevive a que cualquier VM muera.

- **Esfuerzo:** 8-12 horas.

### Largo plazo (3-6 meses)

#### HA para el Jenkins Controller

- **Contexto:** Un solo Controller es un SPOF. Si muere, el lab
  entero cae.

- **Arquitectura propuesta:**
  - 3 VMs en lugar de 2: `controller-A` (activo), `controller-B`
    (pasivo, standby), `podman-host` (sin cambios).
  - `$JENKINS_HOME` compartido via NFS o GlusterFS entre A y B.
  - Heartbeat con `keepalived` o `corosync` para failover automatico.
  - Ansible se extiende trivialmente: dos nuevos hosts en el inventario,
    dos roles nuevos (`jenkins_controller_active`,
    `jenkins_controller_standby`) que comparten el grueso del rol
    `jenkins_controller` actual.

- **Esfuerzo:** 24-40 horas (incluye configurar NFS/GlusterFS y los
  tests de failover).

#### Multiples podman-hosts detras de un balanceador

- **Contexto:** Un solo podman-host tambien es SPOF. Ademas, un host
  con muchos jobs en paralelo se satura.

- **Arquitectura propuesta:**
  - N VMs `podman-host-N` con el mismo provision que el actual.
  - Todos declarados en Jenkins con la misma label `podman-node`.
  - Jenkins reparte jobs automaticamente segun disponibilidad.

- **Trabajo concreto:**
  - Modificar `deploy.sh` para iterar sobre un array de N nodos en
    lugar de uno solo.
  - Modificar `create-agent.groovy` para crear N nodos con la misma
    label.
  - Modificar `hosts.ini` para tener N entries en `[podman_hosts]`.
  - Ajustar el playbook para propagar el secret a cada uno de ellos.

- **Esfuerzo:** 4-6 horas (cambios pequenos gracias a la estructura
  actual basada en roles).

#### Integracion con GitOps

- **Contexto:** Hoy el pipeline se invoca con "Build Now" manual.
  Evolucionarlo hacia un flujo real de CI/CD significa disparar
  builds automaticamente desde pushes de codigo.

- **Stack propuesto:**
  - **Webhook GitHub/GitLab** → Jenkins (plugins ya instalados:
    `git`, `github`).
  - **Jenkinsfiles declarativos** en cada repo (`pipeline { agent
    any; stages { ... } }`).
  - **Shared library** en un repo interno con funciones reutilizables
    (e.g., `notifySlack`, `deployToNexus`, `publishImage`).
  - **Multibranch pipeline** para gestionar automaticamente ramas
    feature/PR/main con distintas politicas de build.

- **Esfuerzo:** 16-24 horas (depende del estado del codigo de los
  proyectos que se integrarian).

#### Migracion selectiva a Kubernetes

- **Contexto:** El ADR original menciona Kubernetes como destino final.
  Tenemos un cluster k3s en `../k8s-home-lab/` que podria servir.

- **Arquitectura propuesta:**
  - Mantener Jenkins como orquestador.
  - Reemplazar el podman-host por el cluster k3s usando
    **Jenkins Kubernetes Plugin**.
  - El pipeline de 4 stages se simplifica a un `podTemplate` que pide
    agentes efimeros a k8s (via `kubernetes-plugin`) en lugar de a
    Podman.

- **Trade-offs:**
  - **Pro:** Mas resiliente (k8s recrea pods fallidos), mas
    paralelizable (k8s escala automaticamente), ecosistema maduro.
  - **Contra:** Requiere entender k8s, mayor consumo de recursos del
    cluster, overhead operacional (etcd, networking, RBAC).

- **Esfuerzo:** 24-40 horas (incluye la curva de aprendizaje de
  k8s-plugin).

---

## [0.2.0] - 2026-09-09

### Imagenes legacy para agentes Jenkins

#### Anadido

- **Directorio `legacy-images/`** con 7 subdirectorios, uno por imagen:
  - `agent-jdk8-ubi8/` - Temurin JDK 8 puro (319 MB, validada).
  - `agent-jdk8-maven-ubi8/` - Temurin JDK 8 + Apache Maven 3.8.7 (329 MB, validada).
  - `agent-node8-ubi8/` - Node.js 8.17.0 (177 MB, validada).
  - `agent-node10-ubi8/` - Node.js 10.24.1 (184 MB, validada).
  - `agent-node12-ubi8/` - Node.js 12.22.12 (192 MB, validada).
  - `agent-jdk6-ubi8/` - Oracle JDK 6u45 + Maven 3.2.5 (requiere binario manual).
  - `agent-jdk7-ubi8/` - Oracle JDK 7u80 + Maven 3.5.4 (requiere binario manual).
- Cada directorio contiene `Containerfile` (compatible con Docker) y
  `README.md` detallado con: por que existe la imagen, que incluye y que
  no, como construir, como subir a un registro, como usarla desde Jenkins,
  consideraciones de seguridad y troubleshooting.
- Los directorios `agent-jdk6-ubi8/binaries/` y `agent-jdk7-ubi8/binaries/`
  incluyen un README explicando como descargar los binarios de Oracle
  manualmente (Oracle no distribuye JDK antiguos via URL publica).
- `legacy-images/README.md` maestro con indice de las 7 imagenes,
  ejemplos de uso, recomendaciones de versionado, automatizacion del
  build, registro interno recomendado y consideraciones de seguridad.

#### Decisiones tecnicas documentadas

- **Temurin 8 (no Oracle JDK 8)**: Oracle dejo de distribuir JDK
  antiguos publicamente en 2023. Temurin (Eclipse Adoptium) es la unica
  distribucion publica mantenida de OpenJDK 8.
- **Maven 3.8.7 para JDK 8**: es la ultima version con soporte
  oficial Java 8 (Maven 3.9.x recomienda Java 11+).
- **Maven 3.2.5 para JDK 6 y 3.5.4 para JDK 7**: ultimas compatibles
  con cada JDK. Maven 3.6.x oficialmente recomienda Java 8.
- **Sin verificacion SHA256 automatica en imagenes Node**: UBI 8 minimal
  no trae `file`, y la combinacion `curl -o` + `sha256sum -c` con paths
  absolutos en BuildKit presenta problemas sutiles de propagacion de CWD
  en subshells. Se confia en la firma PGP de nodejs.org.
- **Oracle JDK 6/7 requiere descarga manual**: Oracle ya no distribuye
  via URL publica. El build falla explicitamente si el binario no esta
  presente, en lugar de intentar descargarlo automaticamente.

#### Limitaciones conocidas

- Las 5 imagenes con descarga automatica fueron **construidas y validadas**
  en el host de desarrollo (AlmaLinux 9, Podman 5.8.2). Las imagenes
  JDK 6 y JDK 7 **no pudieron validarse** porque requieren descarga manual
  del binario de Oracle.
- Las imagenes de versiones EOL (6, 7, Node 8/10/12) **no reciben
  actualizaciones de seguridad** y solo son validas para builds legacy
  aislados.

---

## [0.1.0] - 2026-09-09

### Primera entrega funcional del laboratorio

#### Anadido

- **Provisioning de VMs KVM/libvirt:**
  - `deploy.sh` con descarga de AlmaLinux 9 cloud image, generacion de
    cloud-init NoCloud, reservas DHCP estatico en libvirt.
  - `destroy.sh` con limpieza completa de VMs, ISOs y reservas DHCP.
  - Soporte para `MEMORY_MB`, `VCPUS`, `DISK_ADD_GB` via variables
    de entorno.

- **Provisioning Ansible completo:**
  - 5 roles: `common`, `jenkins_controller`, `podman_host`,
    `agent_registration`, `podman_secrets_tooling`.
  - Playbook principal `site.yml` con 4 fases (common, jenkins,
    podman host, agent registration).
  - Playbook auxiliar `secrets-tooling.yml` para Podman Secrets
    opt-in.

- **Jenkins Controller:**
  - Jenkins LTS 2.568.3 instalado desde RPM oficial.
  - Java 21 desde OpenJDK.
  - 93 plugins pre-instalados via Plugin Installation Manager Tool
    (Docker Workflow, Configuration as Code, Config File Provider,
    Pipeline, Git, GitHub, etc.) con versiones fijadas del update
    center estable de LTS 2.568.2.
  - Admin local con password hasheado con bcrypt.
  - Provision via `init.groovy.d/` (Plan B por incompatibilidad de
    JCasC con el orden de carga de plugins en Jenkins 2.568).
  - Drop-in de systemd con JAVA_OPTS y variables de entorno para
    init.groovy.d/.

- **Podman Host:**
  - Podman 5.8.2 + podman-docker + dependencias rootless.
  - Java 21.
  - Servicio `jenkins-agent.service` (user unit) que conecta al
    Controller via WebSocket.
  - Loginctl enable-linger para que el socket rootless persista.

- **Agent Registration:**
  - Lee el secret del agente desde la API REST de Jenkins
    (`/computer/<nodo>/jenkins-agent.jnlp`).
  - Propaga el secret, descarga `agent.jar`, arranca el servicio.
  - Verifica que el agente este `offline: false` al final.

- **Podman Secrets Tooling (rol opt-in):**
  - Instalacion de age v1.3.2, sops v3.13.3, crypta v0.2.2.
  - Driver `file` funcional con secret de ejemplo.
  - Driver `pass` (GPG): instalacion OK, generacion de clave OK,
    creacion automatica del secret falla por requerir TTY.
  - Driver `shell` con crypta: instalacion OK, ejecucion falla por
    requerir glibc 2.39 (AlmaLinux 9 trae 2.34).

- **Configuracion inmutable de Jenkins:**
  - `plugins.yaml` declarativo con versiones fijadas.
  - `casc/jenkins.yaml` de referencia (NO se usa actualmente,
    documenta la arquitectura objetivo).
  - `groovy/` con scripts para admin, agente y pipeline.
  - `jobs/reference-pipeline.groovy` con el pipeline de ejemplo
    inspirado en la guia original.

- **Documentacion:**
  - `README.md` principal (573 lineas) con arquitectura, despliegue,
    uso, troubleshooting y limitaciones.
  - `docs/adr/` con 6 Architecture Decision Records:
    - ADR-001: Elegir AlmaLinux 9 vs RHEL.
    - ADR-002: DHCP estatico vs network-config en cloud-init.
    - ADR-003: Plan B init.groovy.d vs JCasC declarativo.
    - ADR-004: Leer el secret del agente desde la API REST de Jenkins.
    - ADR-005: Podman Secrets a nivel de sistema, no rootless.
    - ADR-006: Plugin Installation Manager Tool con versiones del
      update center estable.
    - ADR-007: Fix post-provisioning de nsswitch.conf para que DNS
      funcione en AlmaLinux 9 (deploy.sh aplica el fix automaticamente).

#### Limitaciones conocidas

- JCasC no funciona con Jenkins 2.568 por bug de orden de carga
  (sustituido por `init.groovy.d/`).
- Driver `pass` requiere TTY interactivo para crear secrets.
- `crypta` requiere glibc 2.39 (no funciona en AlmaLinux 9).
- Pipeline de ejemplo falla sin codigo fuente en el workspace
  (esperado, es una demo de arquitectura).
- Podman Secrets a nivel de sistema (no rootless del usuario jenkins).
- No se integra con Kubernetes (queda fuera del alcance).

#### Configuracion por defecto

- **Usuario jenkins:** UID 1100, GID 1100.
- **Distro:** AlmaLinux 9.8 (Olive Jaguar) cloud image.
- **Jenkins version:** 2.568.3 LTS.
- **Java version:** 21 (OpenJDK).
- **IPs del laboratorio:** jenkins-controller = 192.168.122.20,
  podman-host = 192.168.122.21.
- **Credenciales admin Jenkins:** admin / definidas localmente por el operador (no versionadas).
- **MACs prefijadas:** jenkins-controller = 52:54:00:4a:c8:01,
  podman-host = 52:54:00:4a:c8:02.
- **Recursos VM por defecto:** 4 GB RAM, 2 vCPU, +16 GB disco.
- **Nombre del nodo Jenkins:** podman-host-almalinux9.
- **Etiqueta del nodo:** podman-node.
- **Secret storage del agente:** /datos/jenkins/agent/secret-file
  (jenkins:jenkins, mode 0400).

[Unreleased]: https://github.com/lacalvom/Ephemeral-Jenkins-Agents-Architectures/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/lacalvom/Ephemeral-Jenkins-Agents-Architectures/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/lacalvom/Ephemeral-Jenkins-Agents-Architectures/releases/tag/v0.1.0
