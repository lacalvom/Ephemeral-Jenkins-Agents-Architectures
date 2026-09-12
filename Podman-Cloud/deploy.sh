#!/bin/bash
# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Cloudsdoers
set -euo pipefail
clear

# Directorio raiz del laboratorio: se deduce de la ubicacion del propio
# script (no hardcodea el home de ningun usuario), para que el repo
# funcione clonado en cualquier ruta y por cualquier usuario.
HOME_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Configuracion local opcional NO versionada (.env). Permite fijar
# secretos y ajustes (VM_USER, VM_PASSWORD, SUDO, STORAGE_DIR, etc.) sin
# editar este script ni commitearlos. Ver .env.example.
if [[ -f "${HOME_DIR}/.env" ]]; then
    set -a
    # shellcheck disable=SC1091
    source "${HOME_DIR}/.env"
    set +a
fi

# Comando de elevacion de privilegios para operaciones en el HOST (no en
# las VMs, que usan "sudo" clasico). Por defecto "sudo"; se puede
# sobreescribir si el sistema usa otra implementacion, ej.:
#   SUDO=sudo-rs ./deploy.sh
SUDO="${SUDO:-sudo}"

# Función para pausar la ejecución del script
pause() {
    read -s -n 1 -r -p "${1:-Presione una tecla para continuar...}"
    echo ""
}

# Pares clave-valor (Hostnames, IPs, MAC prefijadas y roles)
# MAC prefijada con 52:54:00:XX:XX:XX (rango libvirt estándar)
declare -A NODES_IPS
declare -A NODES_MACS
declare -A NODES_ROLES
NODES_IPS=(
    ["jenkins-controller-cloud"]="192.168.122.30"
    ["podman-cloud-host"]="192.168.122.31"
)
NODES_MACS=(
    ["jenkins-controller-cloud"]="52:54:00:4a:c8:03"
    ["podman-cloud-host"]="52:54:00:4a:c8:04"
)
NODES_ROLES=(
    ["jenkins-controller-cloud"]="controller"
    ["podman-cloud-host"]="podman-host"
)

# Recursos asignados a cada VM (puedes sobreescribir por env var antes de invocar)
DEFAULT_VCPUS=2
DEFAULT_MEMORY_MB=4096
DEFAULT_DISK_ADD_GB=16

VCPUS="${VCPUS:-$DEFAULT_VCPUS}"
MEMORY_MB="${MEMORY_MB:-$DEFAULT_MEMORY_MB}"
DISK_ADD_GB="${DISK_ADD_GB:-$DEFAULT_DISK_ADD_GB}"

AUX_DIR="${HOME_DIR}/aux-files"
# Directorio donde se guardan los discos/imagenes de las VMs y el
# propietario con el que libvirt debe poder leerlos. Ajustables por si el
# host usa otra ruta o el usuario/grupo de libvirt difiere (en RHEL/Fedora
# suele ser "qemu:qemu" en lugar de "libvirt-qemu:kvm").
STORAGE_DIR="${STORAGE_DIR:-/mnt/recursos/vms-storage}"
VM_DISK_OWNER="${VM_DISK_OWNER:-libvirt-qemu:kvm}"
SSH_KEY_FILE="${SSH_KEY_FILE:-$HOME/.ssh/id_ed25519.pub}"
if [[ ! -f "${SSH_KEY_FILE}" ]]; then
    echo "Error: no existe la clave publica '${SSH_KEY_FILE}'." >&2
    echo "Genera un par con: ssh-keygen -t ed25519" >&2
    echo "o indica otra con SSH_KEY_FILE=/ruta/a/tu_clave.pub" >&2
    exit 1
fi
SSH_KEY=$(cat "${SSH_KEY_FILE}")

# Usuario administrador que se crea dentro de las VMs (via cloud-init) y
# con el que se conecta Ansible. Por defecto, el mismo usuario que ejecuta
# este script. Para usar otro, definelo en .env o exporta VM_USER antes de
# invocar deploy.sh.
VM_USER="${VM_USER:-${USER}}"

# URL oficial de la cloud image genérica de AlmaLinux 9 (x86_64)
CLOUD_IMAGE_URL="https://repo.almalinux.org/almalinux/9/cloud/x86_64/images/AlmaLinux-9-GenericCloud-latest.x86_64.qcow2"
CLOUD_IMAGE_NAME="almalinux-9.qcow2"

# Password del usuario de las VMs. NO hay valor por defecto en el repo:
# se define en .env (no versionado, copia de .env.example) o por env var.
VM_PASSWORD="${VM_PASSWORD:-}"
if [[ -z "${VM_PASSWORD}" ]]; then
    echo "Error: VM_PASSWORD no esta definida." >&2
    echo "Copia .env.example a .env y define VM_PASSWORD (password del usuario de las VMs)." >&2
    exit 1
fi
if ! command -v openssl >/dev/null 2>&1; then
    echo "Error: se necesita 'openssl' para generar el hash de la password de las VMs." >&2
    exit 1
fi
USER_PASSWD_HASH="$(openssl passwd -6 "${VM_PASSWORD}")"

# La password del admin de Jenkins no se versiona: debe venir de
# ansible/group_vars/all/vault.yml o de la variable de entorno
# JENKINS_ADMIN_PASSWORD (que exportamos a Ansible si esta en .env).
VAULT_FILE="${HOME_DIR}/ansible/group_vars/all/vault.yml"
if [[ -z "${JENKINS_ADMIN_PASSWORD:-}" && ! -f "${VAULT_FILE}" ]]; then
    echo "Error: no hay password para el admin de Jenkins." >&2
    echo "Define JENKINS_ADMIN_PASSWORD en .env, o crea ${VAULT_FILE}" >&2
    echo "(copia de ansible/group_vars/all/vault.yml.example)." >&2
    exit 1
fi

# NOTA: el usuario debe tener NOPASSWD en sudoers (verificar con: sudo -l).
# Para evitar prompts en mitad del script, todas las llamadas privilegiadas
# en el host usan la variable ${SUDO} (por defecto "sudo").

# === Función para generar user-data de cloud-init ===
# NOTA: NO incluimos network-config v2. La IP estática se garantiza
# mediante reservas DHCP en libvirt, lo que evita problemas con el
# nombre variable de la interfaz (eth0 / ens3) entre cloud images.
generar_user_data() {
    local nodo="$1"
    local rol="$2"
    local ssh_key="$3"

    cat > "user-data" << EOF
#cloud-config
hostname: ${nodo}
timezone: Europe/Madrid
locale: en_US.UTF-8
keyboard:
  layout: es
  model: pc105
autoinstall_compat: true

package_update: false
package_upgrade: false

users:
  - name: ${VM_USER}
    primary_group: ${VM_USER}
    groups: sudo
    shell: /bin/bash
    lock_passwd: false
    passwd: "${USER_PASSWD_HASH}"
    sudo: "ALL=(ALL) NOPASSWD:ALL"
    ssh_authorized_keys:
      - "${ssh_key}"
ssh_pwauth: true
chpasswd:
  list: |
    ${VM_USER}:${VM_PASSWORD}
  expire: false

# Etiqueta legible en el banner de login
write_files:
  - path: /etc/motd
    content: |
      ============================================
      Jenkins + Podman Lab :: ${nodo} (${rol})
      ============================================
  - path: /etc/modules-load.d/k8s.conf
    content: |
      overlay
      br_netfilter
  - path: /etc/sysctl.d/99-k8s.conf
    content: |
      net.bridge.bridge-nf-call-iptables  = 1
      net.bridge.bridge-nf-call-ip6tables = 1
      net.ipv4.ip_forward                 = 1

growpart:
  mode: auto
  devices: ['/']

runcmd:
  - echo "VM ${nodo} (rol ${rol}) provisionada correctamente" >> /var/log/lab-init.log
  - sysctl --system
  - systemctl disable --now dnf-makecache.timer 2>/dev/null || true
  - dnf install -y qemu-guest-agent
  - systemctl enable --now qemu-guest-agent
  - systemctl enable --now sshd
EOF
}

# === Función para añadir una reserva DHCP estática a la red 'default' de libvirt ===
# Asocia una MAC con una IP de forma persistente. La VM siempre obtendrá esa IP por DHCP.
add_dhcp_reservation() {
    local mac="$1"
    local ip="$2"
    local name="$3"

    echo "  -> Añadiendo reserva DHCP ${mac} -> ${ip} (${name})..."

    ${SUDO} virsh net-update default add ip-dhcp-host \
        "<host mac='${mac}' name='${name}' ip='${ip}'/>" \
        --config --live >/dev/null
}

# === Inicio del flujo principal ===
echo
echo "=== Initiating Jenkins + Podman lab building... ==="

# Limpieza previa de VMs y reservas DHCP que pudieran quedar de intentos anteriores
echo "= Cleaning up previous lab state (if any)..."
for NODE in "${!NODES_IPS[@]}"; do
    MAC="${NODES_MACS[${NODE}]}"
    IP="${NODES_IPS[${NODE}]}"
    ${SUDO} virsh destroy "${NODE}" >/dev/null 2>&1 || true
    ${SUDO} virsh undefine "${NODE}" --remove-all-storage --wipe-storage --nvram >/dev/null 2>&1 || true
    ${SUDO} rm -f "${STORAGE_DIR}/${NODE}-cloud-init.iso" >/dev/null 2>&1 || true
    ${SUDO} rm -f "${STORAGE_DIR}/${NODE}.qcow2" >/dev/null 2>&1 || true
    # Quitar reservas DHCP previas (best-effort)
    ${SUDO} virsh net-update default delete ip-dhcp-host \
        "<host mac='${MAC}' name='${NODE}' ip='${IP}'/>" \
        --config --live >/dev/null 2>&1 || true
done

# Descargar la imagen base de AlmaLinux 9 (solo si no existe ya)
mkdir -p "${AUX_DIR}"
echo "= Verifying AlmaLinux 9 cloud image..."
if [[ ! -f "${AUX_DIR}/${CLOUD_IMAGE_NAME}" ]]; then
    echo "  -> Downloading ${CLOUD_IMAGE_NAME}..."
    wget -qO "${AUX_DIR}/${CLOUD_IMAGE_NAME}" "${CLOUD_IMAGE_URL}"
else
    echo "  -> ${CLOUD_IMAGE_NAME} already present, skipping download."
fi

# Configurar reservas DHCP antes de levantar las VMs (orden importante)
echo
echo "= Configuring static DHCP reservations in libvirt..."
for NODE in "${!NODES_IPS[@]}"; do
    add_dhcp_reservation "${NODES_MACS[${NODE}]}" "${NODES_IPS[${NODE}]}" "${NODE}"
done

# Iterar sobre cada nodo para preparar sus recursos y desplegar
for NODE in "${!NODES_IPS[@]}"; do
    echo "----------------------------------------------------------------"
    echo "= Processing node: ${NODE}"
    NODE_IP="${NODES_IPS[${NODE}]}"
    NODE_MAC="${NODES_MACS[${NODE}]}"
    NODE_ROLE="${NODES_ROLES[${NODE}]}"

    # Crear subdirectorio de trabajo del nodo
    mkdir -p "${AUX_DIR}/${NODE}"
    cd "${AUX_DIR}/${NODE}"

    # Generar archivo meta-data vacío (requerido por NoCloud)
    echo "  -> Generating meta-data..."
    : > meta-data

    # Generar archivo user-data personalizado
    echo "  -> Generating user-data..."
    generar_user_data "${NODE}" "${NODE_ROLE}" "${SSH_KEY}"

    # Crear la imagen ISO NoCloud con la configuración de cloud-init
    # (solo user-data + meta-data, sin network-config: la IP viene por DHCP estática)
    echo "  -> Building cloud-init ISO..."
    genisoimage -output "${NODE}-cloud-init.iso" -volid cidata -joliet -rock \
        user-data meta-data >/dev/null 2>&1

    # Preparar el disco de sistema qcow2 a partir de la imagen base
    echo "  -> Copying and resizing system disk..."
    cp "${AUX_DIR}/${CLOUD_IMAGE_NAME}" "${NODE}.qcow2"
    qemu-img resize "${NODE}.qcow2" +${DISK_ADD_GB}G >/dev/null 2>&1

    # Mover y configurar permisos de los recursos en el almacenamiento de libvirt
    echo "  -> Configuring permissions in Libvirt Storage Pool..."
    ${SUDO} cp "${NODE}-cloud-init.iso" "${STORAGE_DIR}/${NODE}-cloud-init.iso"
    ${SUDO} cp "${NODE}.qcow2" "${STORAGE_DIR}/${NODE}.qcow2"
    ${SUDO} chown ${VM_DISK_OWNER} "${STORAGE_DIR}/${NODE}-cloud-init.iso"
    ${SUDO} chown ${VM_DISK_OWNER} "${STORAGE_DIR}/${NODE}.qcow2"
    ${SUDO} chmod 664 "${STORAGE_DIR}/${NODE}-cloud-init.iso"
    ${SUDO} chmod 664 "${STORAGE_DIR}/${NODE}.qcow2"

    # Desplegar la máquina virtual con MAC prefijada (para que la reserva DHCP aplique)
    # --rng /dev/urandom: sin esto, la VM arranca sin dispositivo virtio-rng
    # y el pool de entropia del kernel invitado puede tardar mucho (o
    # bloquearse indefinidamente) en inicializarse. Esto se manifiesta
    # como un "cuelgue" silencioso en cualquier operacion criptografica
    # temprana (ej. "gpg --batch --generate-key" en el rol
    # podman_secrets_tooling, ver ADR-014).
    echo "  -> Provisioning virtual machine via virt-install..."
    ${SUDO} virt-install \
        --name "${NODE}" \
        --memory "${MEMORY_MB}" \
        --vcpus "${VCPUS}" \
        --os-variant almalinux9 \
        --disk "path=${STORAGE_DIR}/${NODE}.qcow2,device=disk,bus=virtio" \
        --disk "path=${STORAGE_DIR}/${NODE}-cloud-init.iso,device=cdrom" \
        --network "network=default,model=virtio,mac=${NODE_MAC}" \
        --rng /dev/urandom \
        --graphics none \
        --import \
        --noautoconsole >/dev/null 2>&1 || true

    # Volver al directorio auxiliar para procesar el siguiente nodo
    cd "${AUX_DIR}"
done

# Fase de sincronización y verificación dinámica de inicialización
echo
echo "= Giving nodes 5 seconds to map network interfaces..."
sleep 5

echo
echo "= Waiting for SSH daemon validation on all nodes..."
for NODE in "${!NODES_IPS[@]}"; do
    NODE_IP="${NODES_IPS[${NODE}]}"
    echo "  -> Polling connectivity for ${NODE} (${NODE_IP})..."

    MAX_RETRIES=60
    RETRY_COUNT=0
    SUCCESS=0

    while [ $RETRY_COUNT -lt $MAX_RETRIES ]; do
        if ssh -i "${SSH_KEY_FILE%.pub}" \
               -o StrictHostKeyChecking=no \
               -o UserKnownHostsFile=/dev/null \
               -o ConnectTimeout=3 \
               -o PasswordAuthentication=no \
               -o BatchMode=yes \
               "${VM_USER}"@"${NODE_IP}" "true" >/dev/null 2>&1; then
            SUCCESS=1
            break
        fi
        RETRY_COUNT=$((RETRY_COUNT + 1))
        echo "     [!] Socket unavailable or auth pending. (Attempt ${RETRY_COUNT}/${MAX_RETRIES}). Retrying..."
        sleep 5
    done

    if [ $SUCCESS -ne 1 ]; then
        echo "Error: Timeout reached. Node ${NODE} failed to establish SSH connectivity handshake." >&2
        exit 1
    fi
    echo "     [+] Handshake successful. Node ${NODE} is reachable."
done

# =====================================================================
# Post-provisioning: arreglar nsswitch.conf y DNS en cada VM
# =====================================================================
# La cloud image de AlmaLinux 9 viene con nsswitch.conf que pone 'files'
# antes que 'dns' en la base de datos 'hosts', lo que provoca que
# 'getent hosts' no resuelva nombres DNS aunque curl a IP funcione.
# Esto bloquea 'dnf install' que cuelga esperando descarga.
#
# Aplicamos el fix en cada VM recien provisionada, antes de que Ansible
# intente instalar paquetes. Ver docs/adr/ para mas detalles.
# =====================================================================
echo
echo "=== Aplicando fix de nsswitch.conf en ambas VMs ==="
for NODE in "${!NODES_IPS[@]}"; do
    NODE_IP="${NODES_IPS[${NODE}]}"
    echo "  -> Arreglando nsswitch.conf en ${NODE} (${NODE_IP})..."
    ssh -i "${SSH_KEY_FILE%.pub}" \
        -o StrictHostKeyChecking=no \
        -o UserKnownHostsFile=/dev/null \
        -o ConnectTimeout=5 \
        -o BatchMode=yes \
        "${VM_USER}"@"${NODE_IP}" \
        "sudo /bin/sh -c \"sed -i 's|^hosts:.*|hosts:      files dns myhostname|' /etc/nsswitch.conf && grep '^hosts:' /etc/nsswitch.conf\""
done
echo "  [OK] nsswitch.conf arreglado en ambas VMs."

echo
echo "=== Jenkins + Podman lab base deployment successfully completed ==="
echo
echo "VMs desplegadas:"
echo "  - jenkins-controller-cloud @ 192.168.122.30"
echo "  - podman-cloud-host   @ 192.168.122.31"
echo

# =====================================================================
# Provisionamiento con Ansible (site.yml)
# =====================================================================
# deploy.sh deja las VMs listas a nivel de sistema operativo (cloud-init,
# DNS, SSH). A partir de aqui, TODO el resto del laboratorio (Jenkins
# Controller, Podman Host, registro del agente, y el tooling de Podman
# Secrets con sus 3 drivers) lo aplica el playbook Ansible principal,
# sin pasos manuales adicionales. Ver ADR-013.
# =====================================================================
echo "=== Ejecutando el playbook Ansible (site.yml): Jenkins + Podman + Secrets ==="
echo

(
    cd "${HOME_DIR}/ansible"
    # "$@" permite pasar argumentos extra a ansible-playbook, por ejemplo
    # --ask-vault-pass si has cifrado ansible/group_vars/all/vault.yml.
    ansible-playbook site.yml "$@"
)

echo
echo "=== Laboratorio desplegado y provisionado por completo ==="
echo
echo "Jenkins:      http://192.168.122.30:8080  (usuario: admin)"
echo "Password:     la definida en .env / vault.yml"
echo "Podman Host:  192.168.122.31"
echo

# === Comandos utiles ===

# ssh -i ~/.ssh/id_ed25519 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null <nombre-usuario>@192.168.122.30
# ssh -i ~/.ssh/id_ed25519 -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null <nombre-usuario>@192.168.122.31
