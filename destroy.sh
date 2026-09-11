#!/bin/bash
clear
set -euo pipefail

# Comando de elevacion de privilegios en el host (ver deploy.sh).
SUDO="${SUDO:-sudo}"

NODES=("jenkins-controller" "podman-host")
STORAGE_DIR="${STORAGE_DIR:-/mnt/recursos/vms-storage}"

# Mismas MACs que en deploy.sh (mantener sincronizadas)
declare -A NODES_IPS
declare -A NODES_MACS
NODES_IPS=(
    ["jenkins-controller"]="192.168.122.20"
    ["podman-host"]="192.168.122.21"
)
NODES_MACS=(
    ["jenkins-controller"]="52:54:00:4a:c8:01"
    ["podman-host"]="52:54:00:4a:c8:02"
)

# Cacheamos la clave de sudo (no-op si NOPASSWD está configurado)
${SUDO} -n true 2>/dev/null || true

echo
echo "=== Iniciando destrucción del laboratorio Jenkins + Podman ==="
echo "----------------------------------------------------------------"

for NODE in "${NODES[@]}"; do
    echo "= Eliminando ${NODE}..."

    # 1. Forzar el apagado silencioso
    ${SUDO} virsh destroy "${NODE}" >/dev/null 2>&1 || true

    # 2. Eliminación de la VM y su disco principal indexado en libvirt
    ${SUDO} virsh undefine "${NODE}" --remove-all-storage --wipe-storage --nvram >/dev/null 2>&1 || true

    # 3. Limpieza de archivos residuales (ej. ISO de cloud-init que libvirt no borra)
    ${SUDO} rm -f "${STORAGE_DIR}/${NODE}-cloud-init.iso" >/dev/null 2>&1 || true
    ${SUDO} rm -f "${STORAGE_DIR}/${NODE}.qcow2" >/dev/null 2>&1 || true

    # 4. Eliminar reserva DHCP estática
    MAC="${NODES_MACS[${NODE}]}"
    IP="${NODES_IPS[${NODE}]}"
    ${SUDO} virsh net-update default delete ip-dhcp-host \
        "<host mac='${MAC}' name='${NODE}' ip='${IP}'/>" \
        --config --live >/dev/null 2>&1 || true
done

echo "----------------------------------------------------------------"
echo "=== Laboratorio Jenkins + Podman destruido por completo ==="
echo
