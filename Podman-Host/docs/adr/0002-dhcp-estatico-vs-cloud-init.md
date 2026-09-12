# ADR-002: IPs fijas via DHCP estatico en libvirt, no via network-config en cloud-init

- **Estado:** Aceptado
- **Fecha:** 2026-09-09
- **Decisor:** DevOps Team

## Contexto

Las VMs necesitan direcciones IP **fijas y conocidas de antemano** para que Ansible pueda configurar `/etc/hosts`, certificados, JCasC, y el secret del agente sin depender de DNS dinamico.

La cloud image de AlmaLinux 9 trae `cloud-init` activado, que por defecto:

1. Detecta la primera interfaz de red (`enp1s0` o similar).
2. Aplica la configuracion del bloque `network-config` del user-data.
3. Si ese bloque no existe, hace **DHCP** y obtiene una IP aleatoria del rango del servidor libvirt.

Hay tres formas habituales de garantizar IP fija:

- **Opcion A:** Definir la IP en el bloque `network-config` del user-data cloud-init.
- **Opcion B:** Hacer DHCP estatico en el servidor DHCP de libvirt (reservas DHCP por MAC).
- **Opcion C:** Crear un segundo interface de red en libvirt con una subred aislada donde controlamos las IPs.

## Decision

Adoptamos la **Opcion B (reservas DHCP estatico en libvirt)**.

En el script `deploy.sh`:
- Cada VM se crea con una MAC prefijada (`52:54:00:4a:c8:01` para controller, `:02` para podman-host).
- Antes de levantar la VM, se anade una entrada DHCP estatico en la red `default` de libvirt con `virsh net-update default add ip-dhcp-host "<host mac='XX' name='YY' ip='ZZ'/>"`.
- El bloque `network-config` del user-data **se omite** deliberadamente: cloud-init obtiene la IP del DHCP estatico y todo funciona sin conflictos.

Al destruir el lab con `destroy.sh`, las reservas DHCP se eliminan junto con las VMs.

## Consecuencias

### Positivas

- **No depende del nombre de interfaz** que asigne el kernel. En AlmaLinux 9 cloud image la primera interfaz puede llamarse `eth0`, `enp1s0`, `ens3` u otra, segun el hypervisor. Configurar `network-config` con `eth0` fijo en AlmaLinux 9 fallo durante el primer intento del laboratorio porque el sistema uso otro nombre y cloud-init no aplico la config.
- **No depende de cloud-init en absoluto.** Cualquier cloud image que use DHCP estatico del hypervisor arranca con IP fija. Esto facilita migrar a otra distro o version sin tocar Ansible.
- **Visibilidad centralizada.** Todas las IPs del laboratorio estan listadas en una sola seccion del `deploy.sh` (array `NODES_IPS`).
- **Limpieza automatica.** `destroy.sh` elimina tanto las VMs como las reservas DHCP.

### Negativas

- Hay que mantener el array `NODES_IPS` en sincronia con las MACs en el inventario y con las reservas DHCP. Si alguien cambia uno sin los otros, todo se rompe.
- Las reservas DHCP son especificas de libvirt. Si migramos a otro hypervisor (VMware, VirtualBox, cloud), hay que reimplementar la logica.

### Neutras / trade-offs

- El bloque `network-config` se genera en el script pero no se incluye en la ISO NoCloud. Esto evita que cloud-init intente aplicar config que no matchea la interfaz.

## Alternativas consideradas

- **Opcion A (network-config con eth0 fijo):** Descartada por incompatibilidad con el nombre de interfaz variable en AlmaLinux 9 cloud image. Ademas, requiere conocer de antemano el nombre de la interfaz para cada imagen cloud.
- **Opcion C (subred aislada):** Descartada por complejidad innecesaria. Aporta lo mismo que DHCP estatico pero requiere tocar la configuracion de red de libvirt y del host, rompiendo portabilidad.

## Referencias

- https://libvirt.org/formatnetwork.html
- https://cloudinit.readthedocs.io/en/latest/topics/network-config.html
