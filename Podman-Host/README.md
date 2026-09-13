# Podman-Host — laboratorio Jenkins + Podman

> Modelo **Podman-Host** de
> [`Ephemeral-Jenkins-Agents-Architectures`](../README.md): agentes efímeros
> con `docker-workflow` (`agent { docker { ... } }`), un contenedor por stage,
> sobre un Podman Host. Ver la
> [guía del modelo](docs/Ephemeral-Jenkins-Agents-Podman-host.md).

Home-lab reproducible que despliega una arquitectura completa de **Jenkins
Controller + Podman Host** sobre dos VMs AlmaLinux 9, con agentes Jenkins
conectados por WebSocket y un pipeline de ejemplo inspirado en la guía
original *"Arquitectura de Agentes Efímeros con Podman y Jenkins"*.

El resultado es un Jenkins listo para ejecutar pipelines que lanzan
contenedores efímeros (UBI 9) sobre un Podman rootless del agente.

---

## TL;DR

```bash
# Levantar todo el laboratorio
./deploy.sh

# Provisionar Jenkins Controller + Podman Host + agente
cd ansible && ansible-playbook -i hosts.ini site.yml

# Acceder a Jenkins
# URL:      http://192.168.122.20:8080
# Usuario:  admin
# Password: la definida en .env / ansible/group_vars/all/vault.yml

# Destruir el laboratorio por completo
./destroy.sh
```

Una vez levantado:

- El **Jenkins Controller** corre en `192.168.122.20:8080` con todos los
  plugins pre-instalados y un job de ejemplo llamado `reference-pipeline`.
- El **Podman Host** corre en `192.168.122.21` con el agente JNLP conectado
  por WebSocket y el socket de Podman listo para ejecutar contenedores
  efímeros.

---

## Tabla de contenidos

1. [Arquitectura](#arquitectura)
2. [Requisitos previos](#requisitos-previos)
3. [Estructura del repositorio](#estructura-del-repositorio)
4. [Despliegue paso a paso](#despliegue-paso-a-paso)
5. [Uso del laboratorio](#uso-del-laboratorio)
6. [Uso de Podman Secrets](#uso-de-podman-secrets)
7. [Verificación y smoke-tests](#verificacion-y-smoke-tests)
8. [Personalización](#personalizacion)
9. [Resolución de problemas](#resolucion-de-problemas)
10. [Limitaciones conocidas](#limitaciones-conocidas)
11. [Decisiones arquitectónicas (ADRs)](#decisiones-arquitectonicas-adrs)
12. [Créditos y referencias](#creditos-y-referencias)

---

## Arquitectura

```
                  +-----------------------------------+
                  |     Host físico (KVM/libvirt)    |
                  |     AlmaLinux / Ubuntu / Fedora   |
                  +-----------------+-----------------+
                                    |
                +-------------------+------------------+
                |                                      |
       +--------v---------+                  +---------v---------+
       | jenkins-controller|                  |   podman-host     |
       | AlmaLinux 9       |    WebSocket     |  AlmaLinux 9      |
       | IP: .20           |    (TLS + JNLP)   |  IP: .21          |
       |                   |  <------------->  |                    |
       | Jenkins 2.568.3   |                  | agent.jar + podman|
       | Java 21           |                  | Java 21 + Podman  |
       +-------------------+                  +--------------------+
                |                                      |
                |                                      |
       +--------v---------+                  +---------v---------+
       | Plugins JCasC    |                  | Pipeline Maestro   |
       | Docker Workflow  |                  | (4 stages)         |
       | Config File Prov |                  |  - Backend Java    |
       | + 90 plugins más  |                  |  - Frontend Node   |
       +-------------------+                  |  - Podman build    |
                                              +--------------------+
```

Componentes principales:

| Componente | Tecnología | Versión | IP |
|---|---|---|---|
| Host físico | KVM/libvirt + qemu | n/a | n/a |
| VM Controller | AlmaLinux 9.8 (Olive Jaguar) | cloud image oficial | 192.168.122.20 |
| VM Agent | AlmaLinux 9.8 (Olive Jaguar) | cloud image oficial | 192.168.122.21 |
| Jenkins | Jenkins LTS | 2.568.3 | en Controller |
| Java | OpenJDK | 21 | en ambas VMs |
| Podman | podman + podman-docker | 5.8.2 | en Agent |
| Plugin Manager | jenkins-plugin-manager | 2.15.0 | en Controller |
| Age (cifrado) | age | v1.3.2 | en Agent |
| SOPS (cifrado) | sops | v3.13.3 | en Agent |
| Podman Compose | podman-compose | (EPEL) | en Agent |

Usuarios:

- `<nombre-usuario>` — usuario administrador del host (con sudo NOPASSWD).
- `jenkins` (UID/GID 1100) — usuario de servicio en ambas VMs. Es dueño de
  los procesos Jenkins, del socket rootless de podman y de los paths
  `/datos/jenkins/*`. Tiene sudo NOPASSWD para automatización.

Almacenamiento del host KVM:

- Los discos e imágenes se guardan en `STORAGE_DIR` (por defecto
  `/mnt/recursos/vms-storage`, configurable en `.env`). Los scripts escriben
  **directamente en esa ruta**; **no** se necesita ningún *storage pool* de
  libvirt (se usa `virt-install --disk path=...`).
- Cloud image cacheada en `aux-files/almalinux-9.qcow2` (~590 MB, descarga una
  única vez).
- Discos de VM en formato qcow2 con `+16 GB` adicionales sobre la cloud image
  base.

---

## Requisitos previos

### Software en el host

- `qemu-kvm`, `libvirt` y `virt-install` en ejecución con el demonio
  `libvirtd` activo.
- `genisoimage` (paquete `genisoimage` en Debian/Ubuntu y Fedora) para crear
  las ISOs de cloud-init.
- `qemu-img` (incluido con qemu-kvm).
- `openssl` (para generar el hash de la contraseña del usuario de las VMs).
- `ansible` ≥ 2.14 con las colecciones `community.general` y
  `containers.podman`.
- `python3` ≥ 3.9 con `bcrypt` instalado (para generar el hash del admin
  de Jenkins).
- `ssh` con una clave pública (`~/.ssh/id_ed25519.pub` por defecto;
  configurable con `SSH_KEY_FILE`) que se inyecta en las VMs vía cloud-init.

Verificación rápida:

```bash
which virsh virt-install genisoimage qemu-img openssl ansible-playbook
virsh net-list --all       # debe listar la red 'default'
ansible --version          # >= 2.14
```

### Recursos hardware mínimos

- **CPU:** 4 cores (2 para cada VM).
- **RAM:** 8 GB (4 GB para cada VM con `MEMORY_MB=4096` por defecto).
- **Disco:** 20 GB libres en `STORAGE_DIR` (cloud image ~590 MB + 16 GB
  por VM).
- **Red:** un bridge libvirt (`virbr0` por defecto) con DHCP activo.

### Software en las VMs (instalado automáticamente)

No se requiere nada previo. Ansible instala todo: Java 21, Podman, el RPM de
Jenkins, el Plugin Manager, las dependencias del agente, etc.

---

## Estructura del repositorio

```
Podman-Host/
├── README.md                              # Este documento
├── deploy.sh                              # Crea las VMs en libvirt
├── destroy.sh                             # Destruye las VMs y limpia reservas DHCP
├── .env.example                           # Plantilla de configuración/secretos de deploy.sh
├── aux-files/                             # Imagen cloud cacheada + cachés por VM (generado)
│   ├── almalinux-9.qcow2
│   ├── jenkins-controller/
│   └── podman-host/
├── ansible/                               # Provisionamiento completo
│   ├── ansible.cfg                        # Configuración global de Ansible
│   ├── hosts.ini                          # Inventario de las VMs
│   ├── site.yml                           # Playbook principal (5 fases)
│   ├── group_vars/all/                    # vars.yml + vault.yml.example
│   ├── files/bcrypt-jenkins.py            # Helper para hash bcrypt del admin
│   └── roles/                             # common, jenkins_controller, podman_host,
│                                          #   agent_registration, podman_secrets_tooling
├── jenkins-config/                        # Configuración inmutable de Jenkins
│   ├── plugins.yaml                       # Lista declarativa de plugins con versiones
│   ├── casc/jenkins.yaml                  # Referencia de JCasC (no se aplica actualmente)
│   ├── groovy/                            # Scripts Groovy para init.groovy.d/ (01..04)
│   ├── jobs/reference-pipeline.groovy     # Definición del pipeline de ejemplo
│   └── samples/reference-app/             # App de ejemplo (Java 17 + Angular 20)
├── docs/
│   ├── adr/                               # ADRs del lab Podman-Host
│   └── guides/                            # Guía del modelo Podman-Host
└── legacy-images/                         # Imágenes de agentes con toolchains antiguas
```

> En este laboratorio, los **ADRs** residen en [`docs/adr/`](docs/adr/) y la
> **guía del modelo** directamente en [`docs/`](docs/). Las guías generales
> (comparativa y documento unificado) y los demás modelos están en la raíz
> del repositorio.

---

## Despliegue paso a paso

### Paso 0: Definir los secretos (obligatorio)

El repositorio **no incluye ninguna contraseña**. Antes de desplegar se
definen en ficheros locales (ambos ignorados por git):

```bash
# Contraseña del usuario de las VMs + (opcional) la del admin de Jenkins
cp .env.example .env
# editar .env y definir VM_PASSWORD y JENKINS_ADMIN_PASSWORD

# Alternativa/complemento: secretos de Ansible vía vault
cp ansible/group_vars/all/vault.yml.example ansible/group_vars/all/vault.yml
# editar vault.yml y definir jenkins_admin_password
```

Reglas:

- `VM_PASSWORD` (en `.env`) es **obligatoria**.
- La contraseña del admin de Jenkins debe figurar en **una** de las dos
  opciones: `JENKINS_ADMIN_PASSWORD` en `.env`, o `jenkins_admin_password` en
  `vault.yml`. Si falta, el despliegue se detiene con un mensaje explícito.

### Paso 1: Desplegar todo el laboratorio

Desde el directorio raíz:

```bash
./deploy.sh
```

El proceso, **totalmente desatendido**, realiza lo siguiente:

1. Descarga `AlmaLinux-9-GenericCloud-latest.x86_64.qcow2` la primera vez
   (~590 MB).
2. Para cada VM (`jenkins-controller` y `podman-host`):
   - Genera el bloque `user-data` con hostname, zona horaria, locale,
     paquetes base y sysctls.
   - Genera la ISO NoCloud con `genisoimage` (solo `user-data` + `meta-data`).
   - Copia el qcow2 a `/mnt/recursos/vms-storage/<vm>.qcow2` con `+16 GB`.
   - Añade una reserva DHCP estática en libvirt con MAC prefijada e IP
     fija.
   - Lanza la VM con `virt-install` (4 GB RAM, 2 vCPU).
3. Espera (hasta 5 min) a que ambas VMs respondan a SSH.
4. Aplica el fix de `nsswitch.conf` (DNS) en ambas VMs.
5. Ejecuta automáticamente `ansible-playbook site.yml` (las 5 fases descritas
   más abajo): Jenkins Controller, Podman Host, registro del agente y
   tooling de Podman Secrets con sus 3 drivers.

Al terminar, el laboratorio queda **completamente funcional**: Jenkins
arrancado con el job `reference-pipeline` listo para compilar, el agente
conectado y Podman Secrets configurado. No es necesario ejecutar ningún
`ansible-playbook` de forma manual.

Personalización mediante variables de entorno:

```bash
# Recursos de las VMs
MEMORY_MB=8192 VCPUS=4 ./deploy.sh

# Usuario de las VMs (por defecto, el que ejecuta el script)
VM_USER=mi-usuario ./deploy.sh

# Contraseña del usuario de las VMs (obligatoria; no hay default en el repo)
VM_PASSWORD=MiPasswordSegura ./deploy.sh

# Comando de privilegios en el host (por defecto "sudo"; ej. sudo-rs)
SUDO=sudo-rs ./deploy.sh

# Clave SSH pública a inyectar en las VMs
SSH_KEY_FILE=~/.ssh/id_ed25519.pub ./deploy.sh

# Directorio de discos/imágenes y propietario para libvirt
STORAGE_DIR=/mnt/recursos/vms-storage ./deploy.sh
VM_DISK_OWNER=qemu:qemu ./deploy.sh   # en RHEL/Fedora suele ser qemu:qemu
```

En lugar de pasarlas por línea de comandos, las variables se agrupan en un
fichero `.env` (no versionado): se copia [`.env.example`](./.env.example) a
`.env` y se edita ahí. `deploy.sh` lo carga automáticamente si existe.

El repositorio **no hardcodea el home ni el usuario de nadie**: `deploy.sh`
deduce su directorio raíz a partir de su propia ubicación y crea en las VMs
el mismo usuario que lo ejecuta (`$VM_USER` o `$USER`). Se puede clonar en
cualquier ruta y por cualquier usuario sin editar ficheros.

Las direcciones IP y MAC se definen al principio de `deploy.sh` en los
arrays `NODES_IPS` y `NODES_MACS` (y las MAC también en `destroy.sh`). Para
modificarlas se editan ahí y `ansible/hosts.ini`.

### Las 5 fases de `ansible/site.yml`

`deploy.sh` invoca este playbook automáticamente al final, aunque también se
puede ejecutar de forma independiente (por ejemplo, tras un cambio en
`jenkins-config/` o en los roles, sin recrear las VMs):

```bash
cd ansible
ansible-playbook site.yml
```

1. **Fase 1 · common** — Crea el usuario `jenkins` (UID/GID 1100), sudo
   NOPASSWD, paths `/datos/jenkins/{agent,pipelines-workspace,tmp}`,
   paquetes base y sshd activo. Corre en ambas VMs.
2. **Fase 2 · jenkins_controller** — Instala Java 21, el repositorio de
   Jenkins LTS, el RPM `jenkins-2.568.3`, descarga el Plugin Installation
   Manager Tool, instala los plugins declarados en
   `jenkins-config/plugins.yaml` con versiones fijadas, copia los scripts
   Groovy de provision a `/var/lib/jenkins/init.groovy.d/`, configura el
   drop-in de systemd con JAVA_OPTS, configura la Jenkins URL y arranca
   Jenkins. Espera a que responda HTTP 200/403. Verifica que el nodo y el
   job se crean correctamente.
3. **Fase 3 · podman_host** — Instala `podman`, `podman-docker`,
   `podman-compose` (EPEL) y Java 21, junto con las dependencias rootless.
   Habilita `loginctl enable-linger jenkins`, habilita y arranca el socket
   rootless `podman.socket`, crea la unidad `jenkins-agent.service` en
   `~/.config/systemd/user/` y copia la app de ejemplo (`reference-app/`)
   al workspace del job.
4. **Fase 4 · agent_registration** — Lee el secret real del nodo desde la
   API REST de Jenkins, lo escribe en `/datos/jenkins/agent/secret-file` del
   podman-host, descarga `agent.jar` y arranca `jenkins-agent.service`.
   Espera a que el agente quede `offline: false` en Jenkins.
5. **Fase 5 · podman_secrets_tooling** — Instala `age`, `sops`, `gnupg2` y
   `pass`; configura los tres drivers de Podman Secrets (`file`, `pass`,
   `shell` con un script propio basado en `sops`+`age`) y crea un secret
   de ejemplo con cada uno, de forma totalmente automática. Ver ADR-013,
   ADR-014 y ADR-015.

**Tiempo total esperado:** 10-15 minutos en una máquina moderna.

### Paso 2: Destruir el laboratorio

```bash
./destroy.sh
```

Proceso:

1. `virsh destroy` y `virsh undefine --remove-all-storage --wipe-storage
   --nvram` para cada VM.
2. Elimina las ISOs de cloud-init residuales en `/mnt/recursos/vms-storage/`.
3. Elimina las reservas DHCP estáticas de la red `default` de libvirt.

> La cloud image cacheada en `aux-files/almalinux-9.qcow2` **no** se elimina.
> Para borrarla también: `rm aux-files/almalinux-9.qcow2`.

---

## Uso del laboratorio

### Acceder a Jenkins

- **URL:** http://192.168.122.20:8080
- **Usuario:** `admin`
- **Contraseña:** la definida en `.env` (`JENKINS_ADMIN_PASSWORD`) o en
  `ansible/group_vars/all/vault.yml` (no hay ninguna en el repositorio).

### Acceder al Podman Host

```bash
ssh <nombre-usuario>@192.168.122.21
sudo -u jenkins -i
```

### Ejecutar el pipeline de ejemplo

1. En la UI web, acceder a la vista del job `reference-pipeline`.
2. Pulsar **"Build Now"**.
3. El pipeline lanza 4 stages secuenciales:
   - **Construcción Backend (Java/Maven)** — imagen `ubi9/openjdk-17`.
   - **Construcción Frontend (Node/npm)** — imagen `ubi9/nodejs-20`.
   - **Empaquetado Imagen Backend** — imagen `ubi9/podman`.
   - **Empaquetado Imagen Frontend** — imagen `ubi9/podman`.

Cada stage crea un contenedor efímero en el podman-host, monta el workspace
del agente mediante `--userns=keep-id` y, al terminar, lo destruye.

**El código que compila el pipeline ya está incluido en el laboratorio:**
una app de ejemplo funcional (backend Java 17/Spring Boot + frontend
Angular 20) reside en `jenkins-config/samples/reference-app/` y Ansible la
copia automáticamente al workspace real del job
(`/datos/jenkins/pipelines-workspace/workspace/reference-pipeline/`)
en cada `deploy.sh`. No es necesario clonar nada manualmente; el primer
"Build Now" ya completa los 4 stages con éxito.

Tras un build exitoso, se puede probar backend y frontend juntos (fuera de
Jenkins) con `podman-compose`:

```bash
ssh <nombre-usuario>@192.168.122.21
sudo -u jenkins -i
cd /datos/jenkins/pipelines-workspace/workspace/reference-pipeline
podman compose -f podman-compose.yml up -d
curl http://localhost:8080/api/hello
curl http://localhost:4200/
podman compose -f podman-compose.yml down
```

Ver `jenkins-config/samples/reference-app/README.md` para más detalle sobre
la app de ejemplo y cómo reconstruirla manualmente.

Para sustituir la app de ejemplo por código propio, se edita
`jenkins-config/samples/reference-app/{backend,frontend}/` (o se modifica
`jenkins_config_reference_app_dir` en `ansible/group_vars/all/vars.yml`
para apuntar a otro directorio) y se vuelve a ejecutar el playbook.

### SSH a las VMs

Las VMs aceptan conexiones SSH como `<nombre-usuario>` con la clave
`~/.ssh/id_ed25519` (ya desplegada vía cloud-init):

```bash
ssh <nombre-usuario>@192.168.122.20   # Jenkins Controller
ssh <nombre-usuario>@192.168.122.21   # Podman Host
```

---

## Uso de Podman Secrets

La Fase 5 del playbook (`podman_secrets_tooling`) deja configurados
**los tres drivers** de Podman Secrets, cada uno con un secret de ejemplo
ya creado (`api_token_prod_file`, `api_token_prod_pass`,
`api_token_prod_shell`). Todas las operaciones se realizan como **root**
en el podman-host (ver ADR-005), por lo que los comandos de esta sección se
ejecutan con `sudo` (o ya como root):

```bash
ssh <nombre-usuario>@192.168.122.21
sudo -i
```

Comparativa rápida de los tres drivers:

| Driver | Almacenamiento | Cifrado en reposo | Prep. extra | Uso típico |
|---|---|---|---|---|
| `file` | `/var/lib/containers/storage/secrets/` | No (texto plano) | Ninguna | Entornos aislados con control de acceso estricto |
| `pass` | `/root/.password-store/` (GPG) | Sí (GPG) | Clave GPG (la crea el rol) | Auditorías, Zero Trust con tooling clásico |
| `shell` | `/root/.config/podman-secrets-shell-store/` (`sops`+`age`) | Sí (`age`) | Script + clave `age` (los crea el rol) | El más avanzado; Podman no guarda el valor, lo descifra al vuelo |

### Driver `file` (sin cifrado)

Almacena el secreto en texto plano en
`/var/lib/containers/storage/secrets/` (perfil root, ver ADR-005). Es el
driver por defecto de Podman y no requiere ninguna preparación.

```bash
# Crear un secret nuevo desde texto plano
echo -n "mi-token-secreto" | podman secret create mi_nuevo_secret -

# Crear un secret desde un fichero (ej. un kubeconfig)
podman secret create k8s_config /ruta/segura/admin.kubeconfig

# Ver los secrets existentes
podman secret ls
```

### Driver `pass` (cifrado GPG en reposo)

Usa una clave GPG sin contraseña (generada por el rol, en
`/root/.gnupg`) y el almacén `pass` (`/root/.password-store`). El secreto
queda cifrado en disco; solo se descifra al arrancar el contenedor que lo
consume.

```bash
# Crear un secret nuevo con este driver (la clave GPG ya existe)
echo -n "mi-token-secreto" | podman secret create --driver pass mi_nuevo_secret_pass -

# Ver el almacén pass directamente (opcional, para depurar)
pass ls
```

El rol genera la clave GPG automáticamente si no existe. La detección no se
basa en la mera existencia del directorio
`/root/.gnupg/private-keys-v1.d` (que puede existir sin clave válida si una
generación previa se interrumpió), sino en que haya de verdad una clave
secreta (`gpg --list-secret-keys`). Si ya existe una clave, no la regenera.
Si se quiere usar una clave GPG propia, se crea **antes** de ejecutar
`site.yml`.

### Driver `shell` (cifrado dinámico con `sops` + `age`)

Es el modelo más avanzado: Podman **no almacena el secreto**, solo la
instrucción de cómo obtenerlo. Cuando un contenedor necesita el secreto,
Podman ejecuta un script que lo descifra al vuelo.

En este laboratorio, ese script es
`/usr/local/bin/podman-secret-sops-driver.sh` (desplegado por el rol desde
`ansible/roles/podman_secrets_tooling/templates/sops-shell-driver.sh.j2`).
Sustituye a `crypta` (incompatible con AlmaLinux 9, ver ADR-015) llamando
directamente a `sops` y `age`. El script implementa las 4 acciones que
exige Podman (`lookup`, `store`, `list`, `delete`); no se invoca a mano
normalmente, **Podman lo invoca** al crear, leer o borrar un secret con
este driver.

Los ficheros cifrados quedan en
`/root/.config/podman-secrets-shell-store/` (uno por secret, nombrado por el
ID interno que le asigna Podman, no por el nombre legible), y la clave
`age` en `/root/.config/age/keys.txt`.

Procedimiento para crear y probar un secret nuevo:

```bash
# 1. Entrar al podman-host como root (el script y la clave age ya
#    existen, los puso el playbook; no hay que crearlos)
ssh <nombre-usuario>@192.168.122.21
sudo -i

# 2. Variable de conveniencia (evita repetir la ruta 4 veces)
DRV=/usr/local/bin/podman-secret-sops-driver.sh

# 3. Crear el secret, con el valor real
echo -n "el-valor-real-de-tu-secreto" | podman secret create --driver shell \
  --driver-opts "lookup=$DRV lookup,store=$DRV store,list=$DRV list,delete=$DRV delete" \
  mi_secreto_nuevo -

# 4. Verificar que se ha creado
podman secret ls
ls -la /root/.config/podman-secrets-shell-store/

# 5. Probarlo arrancando un contenedor que lo consuma
podman run --rm --secret mi_secreto_nuevo,type=env,target=MI_VAR \
  alpine sh -c 'echo "Valor: $MI_VAR"'
# -> Valor: el-valor-real-de-tu-secreto

# 6. Borrarlo si ya no se necesita
podman secret rm mi_secreto_nuevo
```

Para crearlo desde Ansible en lugar de manualmente, se usa el módulo
(así lo hace el propio rol; ver
`ansible/roles/podman_secrets_tooling/tasks/main.yml`):

```yaml
- containers.podman.podman_secret:
    name: mi_nuevo_secret_shell
    data: "mi-token-secreto"
    driver: shell
    driver_opts:
      lookup: "/usr/local/bin/podman-secret-sops-driver.sh lookup"
      store: "/usr/local/bin/podman-secret-sops-driver.sh store"
      list: "/usr/local/bin/podman-secret-sops-driver.sh list"
      delete: "/usr/local/bin/podman-secret-sops-driver.sh delete"
    state: present
    skip_existing: true   # no falla si ya existe; no lo regenera
```

Para ver el fichero cifrado de un secret concreto (solo con fines de
depuración):

```bash
podman secret inspect mi_nuevo_secret_shell --format '{{.ID}}'
# el fichero cifrado está en:
# /root/.config/podman-secrets-shell-store/<ID>.enc
SOPS_AGE_KEY_FILE=/root/.config/age/keys.txt \
  sops --decrypt --input-type binary --output-type binary \
  /root/.config/podman-secrets-shell-store/<ID>.enc
```

### Consumir un secret en el pipeline

Con independencia del driver utilizado para crear el secreto, el código del
pipeline es **agnóstico**: solo referencia el nombre del secret con `--secret`
en los `args` del agente Docker. Podman resuelve el driver (y lo descifra si
aplica) internamente, lo inyecta en el contenedor efímero y lo borra de
memoria al terminar el stage. Los tres drivers se utilizan exactamente igual:

```groovy
pipeline {
    agent { label 'podman-node' }

    stages {
        // Con el driver 'file' (el ejemplo del laboratorio)
        stage('Secret driver file') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/podman:latest'
                    reuseNode true
                    args '''
                        --userns=keep-id
                        --security-opt label=disable
                        -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock
                        --secret api_token_prod_file,type=env,target=API_TOKEN
                    '''
                }
            }
            steps {
                sh 'echo "Token (file): $API_TOKEN"'
            }
        }

        // Con el driver 'pass' (cifrado GPG)
        stage('Secret driver pass') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/podman:latest'
                    reuseNode true
                    args '''
                        --userns=keep-id
                        --security-opt label=disable
                        -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock
                        --secret api_token_prod_pass,type=env,target=API_TOKEN
                    '''
                }
            }
            steps {
                sh 'echo "Token (pass): $API_TOKEN"'
            }
        }

        // Con el driver 'shell' (sops+age)
        stage('Secret driver shell') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/podman:latest'
                    reuseNode true
                    args '''
                        --userns=keep-id
                        --security-opt label=disable
                        -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock
                        --secret api_token_prod_shell,type=env,target=API_TOKEN
                    '''
                }
            }
            steps {
                sh 'echo "Token (shell): $API_TOKEN"'
            }
        }
    }
}
```

Notas sobre `--secret`:

- `type=env,target=API_TOKEN` inyecta el valor como **variable de entorno**
  `API_TOKEN` dentro del contenedor (lo más habitual).
- `type=mount,target=/run/secrets/mi_secret` lo monta como **fichero**
  (útil para elementos como un `kubeconfig`).
- Sin `target=`, el nombre por defecto es el del secret
  (`/run/secrets/<nombre>`).

Si un secret no existiera, el stage falla con
`no secret with name or id "..."`; se crea el secret antes (de forma manual
como se ha descrito, o mediante una tarea en `podman_secrets_tooling` para
que se cree en cada `deploy.sh`).

### Añadir secrets propios al playbook

Si se quiere que un secret propio se cree automáticamente en cada
`deploy.sh` (en lugar de crearlo a mano tras el provisioning), se añade una
tarea equivalente a las de
`ansible/roles/podman_secrets_tooling/tasks/main.yml` (bloque "DRIVER
FILE/PASS/SHELL"), o se crea un `.yml` nuevo bajo `tasks/` y se incluye con
`import_tasks`/`include_tasks` desde `main.yml`.

---

## Verificación y smoke-tests

### Verificar que Jenkins está en ejecución

```bash
ssh <nombre-usuario>@192.168.122.20 'sudo systemctl status jenkins --no-pager'
curl -s http://192.168.122.20:8080/login | head -5
```

### Verificar que el agente está conectado

```bash
curl -s -u admin:<tu-password> http://192.168.122.20:8080/computer/podman-host-almalinux9/api/json | \
  python3 -m json.tool | grep -E '"offline"|"displayName"'
```

Salida esperada:

```json
"displayName": "podman-host-almalinux9",
"offline": false,
```

### Verificar que los plugins están instalados

```bash
curl -s -u admin:<tu-password> http://192.168.122.20:8080/pluginManager/api/json?depth=1 | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print('Total:', len(d['plugins']))"
```

Salida esperada: `Total: 93`.

### Verificar que el job reference-pipeline existe

```bash
curl -s -u admin:<tu-password> http://192.168.122.20:8080/job/reference-pipeline/api/json | \
  python3 -c "import sys,json; d=json.load(sys.stdin); print('Name:', d['name']); print('URL:', d['url'])"
```

### Verificar que Podman funciona

```bash
ssh <nombre-usuario>@192.168.122.21 'sudo podman --version && sudo podman run --rm hello-world'
```

### Verificar que los Podman Secrets existen

Se configuran automáticamente en la Fase 5 de `site.yml`, sin pasos manuales
adicionales:

```bash
ssh <nombre-usuario>@192.168.122.21 'sudo podman secret ls'
```

Salida esperada (los tres drivers, uno por línea):

```
ID                         NAME                  DRIVER      CREATED         UPDATED
8b06b1738de1da9bcb2a33f05  api_token_prod_file   file        35 minutes ago  35 minutes ago
...                        api_token_prod_pass   pass        35 minutes ago  35 minutes ago
...                        api_token_prod_shell  shell       35 minutes ago  35 minutes ago
```

Los tres deben aparecer siempre: los drivers `pass` y `shell` están
completamente automatizados (ver ADR-015), sin pasos manuales pendientes.

---

## Personalización

### Secretos y credenciales (no versionados)

El repositorio trae credenciales por defecto **solo válidas para un
laboratorio aislado**. Para usar credenciales propias sin que acaben en git,
hay dos puntos de entrada, ambos en `.gitignore`:

- **`.env`** (raíz del repo) — para `deploy.sh`: usuario y contraseña de
  las VMs, comando de privilegios, rutas, etc. Plantilla en
  [`.env.example`](./.env.example):
  ```bash
  cp .env.example .env
  # editar .env con los valores
  ```
- **`ansible/group_vars/all/vault.yml`** — para Ansible: contraseña del
  admin de Jenkins y cualquier otro secreto. Plantilla en
  `ansible/group_vars/all/vault.yml.example`:
  ```bash
  cp ansible/group_vars/all/vault.yml.example ansible/group_vars/all/vault.yml
  # editar vault.yml con los valores
  ```
  `vault.yml` se carga después de `vars.yml` (orden alfabético), por lo que
  sus valores tienen prioridad. Opcionalmente se cifra con
  `ansible-vault encrypt ansible/group_vars/all/vault.yml` y se ejecuta el
  playbook con `--ask-vault-pass`.

### Cambiar la contraseña del admin

La forma recomendada es `ansible/group_vars/all/vault.yml` (ver arriba).
Alternativamente, se edita `ansible/group_vars/all/vars.yml`:

```yaml
jenkins_admin_user: "admin"
jenkins_admin_password: "MiPasswordSeguro123"
```

Y se reaplica el playbook. El hash bcrypt se regenera automáticamente.

### Cambiar las direcciones IP del laboratorio

Se editan tres ficheros de forma sincronizada:

1. `deploy.sh` — array `NODES_IPS` y `NODES_MACS`:
   ```bash
   declare -A NODES_IPS
   NODES_IPS=(
       ["jenkins-controller"]="192.168.122.20"
       ["podman-host"]="192.168.122.21"
   )
   declare -A NODES_MACS
   NODES_MACS=(
       ["jenkins-controller"]="52:54:00:4a:c8:01"
       ["podman-host"]="52:54:00:4a:c8:02"
   )
   ```

2. `ansible/hosts.ini` — actualizar `ansible_host`:
   ```ini
   [jenkins_controllers]
   jenkins-controller ansible_host=192.168.122.20

   [podman_hosts]
   podman-host ansible_host=192.168.122.21
   ```
   (El usuario de conexión `ansible_user` no se fija aquí: se deduce en
   `ansible/group_vars/all/vars.yml` a partir de la variable `vm_user`.)

3. `ansible/group_vars/all/vars.yml` — `jenkins_agent_jar_url` se genera
   automáticamente con la dirección IP del host.

### Cambiar el usuario de las VMs

Por defecto, el laboratorio crea en las VMs el **mismo usuario que ejecuta
`deploy.sh`** (variable `vm_user` en `ansible/group_vars/all/vars.yml`, que a
su vez usa `$VM_USER` o `$USER`). `deploy.sh` y Ansible comparten ese valor,
de modo que no es necesario tocar nada para que funcione en cualquier máquina.

Para usar un usuario distinto, se exporta `VM_USER` antes de desplegar:

```bash
VM_USER=mi-usuario ./deploy.sh
```

Y, si Ansible se lanza por separado, se exporta la misma variable (o se
edita `vm_user` en `ansible/group_vars/all/vars.yml`).

### Cambiar la versión de Jenkins

Se edita `ansible/group_vars/all/vars.yml`:

```yaml
jenkins_version: "2.999.0"   # LTS que se quiera usar
```

> Tras cambiar la versión, se regenera el `plugins.yaml` con las versiones
> del update center estable correspondiente:
>
> ```bash
> # Para LTS 2.999.0
> curl -sL https://updates.jenkins.io/dynamic-stable-2.999.0/update-center.json | python3 -c "..."
> ```
>
> Ver ADR-006 para más detalle.

### Añadir plugins extra

Se edita `jenkins-config/plugins.yaml` y se reaplica el playbook:

```yaml
plugins:
  - artifactId: "my-new-plugin"
    source:
      version: "1.2.3"
  ...
```

### Cambiar los drivers activos de Podman Secrets

Por defecto, `site.yml` (Fase 5) activa los tres drivers (`file`, `pass`,
`shell`) y crea un secret de ejemplo con cada uno. Para modificarlo, se
edita el bloque `vars:` de la Fase 5 en `ansible/site.yml`:

```yaml
- name: "Fase 5 · Podman Secrets Tooling (drivers file/pass/shell)"
  hosts: podman_hosts
  become: true
  vars:
    enabled_drivers: [file, pass]      # quitar el driver que no se quiera
    create_example_secrets: true       # poner "false" para solo instalar los binarios
  roles:
    - podman_secrets_tooling
```

Ver ADR-013 para la motivación de esta integración y ADR-005 para las
limitaciones de cada driver.

---

## Resolución de problemas

### El agente aparece como "offline" tras provisionar

Causa más probable: el secret del agente no se propagó correctamente, o el
handshake WebSocket falló por algún plugin faltante.

Diagnóstico:

```bash
# Ver el secret que Jenkins espera vs el del agente
ssh <nombre-usuario>@192.168.122.20 'curl -s -u admin:<tu-password> http://127.0.0.1:8080/computer/podman-host-almalinux9/jenkins-agent.jnlp'
ssh <nombre-usuario>@192.168.122.21 'sudo cat /datos/jenkins/agent/secret-file'
```

Si son diferentes, se reaplica `ansible-playbook -i hosts.ini site.yml` (la
fase 4 lee el secret real).

Logs:

```bash
ssh <nombre-usuario>@192.168.122.20 'sudo journalctl -u jenkins.service -n 100 --no-pager | grep -iE "secret|webSocket|agent"'
ssh <nombre-usuario>@192.168.122.21 'sudo journalctl -u user@1100.service -n 50 --no-pager | grep -iE "jenkins-agent"'
```

### Jenkins tarda mucho en arrancar

El primer arranque puede tardar hasta 60 segundos. En arranques
posteriores, entre 5 y 15 segundos. Si supera los 5 minutos, se revisa
`journalctl -u jenkins.service -f` en busca de errores.

### Podman no encuentra la imagen `ubi9/openjdk-17`

Las imágenes base de Red Hat están en `registry.access.redhat.com` y requieren
autenticación en algunos casos. Para este laboratorio las imágenes son
públicas y deben descargarse sin credenciales. Se verifica la conectividad
con:

```bash
ssh <nombre-usuario>@192.168.122.21 'sudo podman pull registry.access.redhat.com/ubi9/openjdk-17:latest'
```

### Errores de Ansible "Timeout (12s) waiting for privilege escalation prompt"

Indican que Ansible no puede autenticarse para hacer `become`. Se verifica:

```bash
ssh <nombre-usuario>@192.168.122.20 'sudo -n true && echo SUDO_OK || echo SUDO_FAIL'
```

Si falla, el usuario `<nombre-usuario>` no tiene `NOPASSWD` en sudoers. Se
añade `/etc/sudoers.d/<nombre-usuario>`:

```
<nombre-usuario> ALL=(ALL) NOPASSWD:ALL
```

### El playbook se queda colgado en "Gathering Facts"

Suele ser un problema de SSH rate limit o de conexiones colgadas. Soluciones:

```bash
# Limpiar control paths stale
rm -f /home/<nombre-usuario>/.ansible/cp/*

# Esperar 30 segundos y reintentar
sleep 30 && ansible-playbook -i hosts.ini site.yml
```

---

## Limitaciones conocidas

1. **JCasC no funciona** con Jenkins 2.568.3 por un bug de orden de carga
   entre plugins. Se usan scripts Groovy nativos en `init.groovy.d/` como
   alternativa (Plan B). Ver ADR-003.
2. **El podman-host expone los secrets a nivel de sistema**, no rootless.
   Cualquier proceso del host puede leerlos. Aceptable en este laboratorio
   monousuario. En producción, usar rootless con cgroups delegadas. Ver
   ADR-005.
3. **El repositorio NO crea el clúster de Kubernetes ni nada relacionado.**
   Es solo Jenkins + Podman. La guía original menciona Kubernetes como
   destino final de los pipelines (empaquetado de imágenes), pero eso queda
   fuera del alcance de este laboratorio.
4. **Credenciales fuera del repositorio.** El repo **no contiene ninguna
   contraseña**. Antes de desplegar se definen (ambas ignoradas por git):
   `VM_PASSWORD` (y, opcionalmente, `JENKINS_ADMIN_PASSWORD`) en `.env`, o
   `jenkins_admin_password` en `ansible/group_vars/all/vault.yml`. Si no se
   definen, `deploy.sh` o el playbook fallan con un mensaje explícito.
   Además, `deploy.sh` escribe en `aux-files/` (ignorado por git) la clave
   SSH pública del usuario y datos de la máquina; esa carpeta nunca debe
   subirse a un repositorio público.

---

## Notas para publicar o portar el repositorio

- `deploy.sh` deduce su ruta raíz a partir de su propia ubicación, y crea en
  las VMs el mismo usuario que lo ejecuta: se puede clonar en cualquier
  ruta y por cualquier usuario sin editar nada.
- El comando de privilegios del host es configurable
  (`SUDO="${SUDO:-sudo}"`, también en `destroy.sh`); por defecto `sudo`,
  sobreescribible con `SUDO=sudo-rs` si el sistema usa esa variante.
- `aux-files/` está en `.gitignore` (cloud images, discos qcow2, ISOs y
  `user-data` con datos de la máquina). Solo se versionan `.gitkeep` y el
  resto del código.
- No hay ninguna ruta ni usuario de una persona concreta en el código:
  todo se deduce del entorno (`$USER`, `$HOME`, `$BASH_SOURCE`).

---

## Decisiones arquitectónicas (ADRs)

Las decisiones técnicas relevantes se documentan como ADRs en
[`docs/adr/`](docs/adr/README.md):

- **ADR-001:** Elegir AlmaLinux 9 como distro base.
- **ADR-002:** DHCP estático en libvirt frente a network-config en cloud-init.
- **ADR-003:** Plan B init.groovy.d frente a JCasC declarativo.
- **ADR-004:** Leer el secret del agente desde la API REST de Jenkins.
- **ADR-005:** Podman Secrets a nivel de sistema, no rootless.
- **ADR-006:** Plugin Installation Manager Tool con versiones del update center
  estable.
- **ADR-007:** Fix de `nsswitch.conf` para que el DNS funcione en AlmaLinux 9
  cloud image.
- **ADR-008:** Habilitar JNLP port y persistir InstallState en init.groovy.
- **ADR-009:** Aplicación de referencia (Java/Maven + Angular/npm) sin SCM y
  Managed Config Files sin JCasC.
- **ADR-010:** Habilitar explícitamente el socket rootless de Podman
  (`podman.socket`).
- **ADR-011:** `--security-opt label=disable` en lugar de `:z` para montar el
  socket de Podman (SELinux).
- **ADR-012:** Instalar `podman-compose` (EPEL) para las pruebas manuales de
  la reference-app.
- **ADR-013:** Integrar `podman_secrets_tooling` en `site.yml` (Fase 5) y
  automatizar los 3 drivers.
- **ADR-014:** Entropía suficiente en las VMs para operaciones criptográficas
  (GPG).
- **ADR-015:** Sustituir `crypta` por `sops`+`age` directo, y corregir el
  driver `pass` (bug real, no relacionado con TTY).

Guías de arquitecturas de agentes efímeros:

1. [Comparativa de los tres modelos](../guides/Ephemeral-jenkins-Agents-Architectures-Compartive-guide.md) —
   panorama de Podman-Host, Podman-Cloud y Jenkins-Kubernetes.
2. [Podman-Host](docs/Ephemeral-Jenkins-Agents-Podman-host.md) — agentes
   efímeros con `docker-workflow` (`agent { docker { ... } }`), un contenedor
   por stage.
3. [Podman-Cloud](../Podman-Cloud/docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md) —
   Cloud con `docker-plugin` y Docker Agent Templates.
4. [Jenkins-Kubernetes](../Jenkins-Kubernetes/docs/Ephemeral-Jenkins-Agents-Kubernetes.md) —
   agentes efímeros sobre Kubernetes (controller dentro o fuera del clúster).
5. [Documento unificado](../guides/Ephemeral-jenkins-agents-Architectures-models-complete-guide.md) —
   todo en uno, como referencia.

---

## Créditos y referencias

Basado en:

- *"Arquitectura de Agentes Efímeros con Podman y Jenkins"* (documento
  interno del equipo DevOps, Sep 2026).

Inspirado por:

- El proyecto hermano `k8s-home-lab` (mismo autor; ver directorio padre
  `../k8s-home-lab/`).

Tecnologías clave:

- Jenkins LTS: https://www.jenkins.io/
- Plugin Installation Manager Tool:
  https://github.com/jenkinsci/plugin-installation-manager-tool
- Podman: https://podman.io/
- SOPS: https://github.com/getsops/sops
- age: https://age-encryption.org/
- AlmaLinux: https://almalinux.org/
- Ansible: https://www.ansible.com/
- KVM/libvirt: https://libvirt.org/

---

**Autor:** Cloudsdoers — Luis Alberto Calvo Muñiz <luis.calvo@cloudsdoers.com>
**Versión:** 0.1.0
**Fecha:** 2026-09-09
**Cambios:** ver [CHANGELOG.md](../CHANGELOG.md)

---

## Licencia

- **Código del laboratorio** (scripts `deploy.sh`/`destroy.sh`, roles de
  Ansible, configuración de Jenkins): licencia
  [Apache 2.0](../LICENSE), con el aviso de atribución en
  [NOTICE](../NOTICE).
- **Guías de arquitecturas de agentes efímeros**
  (`guides/` y `docs/`): licencia
  [CC BY 4.0](../guides/LICENSE)
  (Creative Commons Atribución 4.0 Internacional).
- **Marcas:** "Cloudsdoers" y su logotipo son marcas de Cloudsdoers
  (https://cloudsdoers.com). Ninguna de las licencias anteriores concede
  derechos sobre ellas; solo se permite el uso razonable y habitual para
  atribuir el origen del material. Ver [NOTICE](../NOTICE).
