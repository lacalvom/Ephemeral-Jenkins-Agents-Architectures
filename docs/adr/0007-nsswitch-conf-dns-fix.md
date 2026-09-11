# ADR-007: Fix post-provisioning de nsswitch.conf para que DNS funcione en AlmaLinux 9

- **Estado:** Aceptado
- **Fecha:** 2026-09-09
- **Decisor:** DevOps Team

## Contexto

Al desplegar el laboratorio desde cero con `./deploy.sh` + `ansible-playbook`,
el playbook se quedaba colgado indefinidamente en el task
`jenkins_controller | Instalar Java 21 y python3-bcrypt` con `dnf install`.

## Diagnostico

La investigacion revelo tres sintomas:

1. `curl https://archive.almalinux.org` → vacio (no resuelve).
2. `curl https://1.1.1.1` → HTTP/2 301 (responde por IP).
3. `ping 1.1.1.1` → 64 bytes (hay salida a internet).
4. `python3 -c "import socket; socket.gethostbyname('archive.almalinux.org')"` → IP correcta.
5. `getent hosts archive.almalinux.org` → vacio.

Es decir: **la red funciona, Python resuelve DNS, pero `getent` no**.

## Causa raiz

La cloud image de AlmaLinux 9 viene con `/etc/nsswitch.conf` que pone
`files` antes que `dns` en la base de datos `hosts`:

```
hosts:      files myhostname
```

Para la mayoria de operaciones esto funciona porque `myhostname` resuelve
el nombre local y `files` consulta `/etc/hosts`. Pero cuando `dnf`
intenta resolver un nombre de dominio externo, el orden incorrecto
provoca que la consulta DNS no se realize consistentemente.

**Confirmado por:**
- `getent hosts archive.almalinux.org` → vacio (mala consulta NSS).
- `socket.gethostbyname()` → IP correcta (consulta directa a getaddrinfo).

Esto es un bug conocido de la cloud image de AlmaLinux 9 que se manifiesta
cuando el gateway de libvirt (`virbr0` / `dnsmasq`) no hace forwarding DNS
correctamente y la VM depende de un servidor DNS upstream (como 1.1.1.1).

## Decision

Aplicar el fix en `deploy.sh` justo despues del polling SSH, antes de que
Ansible intente instalar paquetes. El fix es:

```bash
sed -i 's|^hosts:.*|hosts:      files dns myhostname|' /etc/nsswitch.conf
```

Esto pone `dns` entre `files` y `myhostname`, garantizando que las consultas
NSS externas usen el resolver configurado en `/etc/resolv.conf`.

El fix se aplica via SSH desde el host a cada VM recien provisionada
(antes de ejecutar Ansible), evitando que el primer `dnf install` se cuelgue.

## Consecuencias

### Positivas

- **`./deploy.sh` + `ansible-playbook` funciona desde cero** sin intervencion
  manual. Es la primera vez que el lab es completamente reproducible.
- El fix es idempotente: si ya estaba aplicado, el `sed` no cambia nada.
- El fix es transparente: cualquier `dnf` posterior funciona normalmente.

### Negativas

- Anade ~10 segundos al `deploy.sh` (2 SSH + sed + grep por VM).
- Requiere que las VMs ya respondan a SSH, lo cual ya esta garantizado
  por el polling previo.
- Si en el futuro cambia el orden en `nsswitch.conf` (ej. RHEL 10 cloud
  image lo arregla de fabrica), el fix seguira funcionando (es idempotente).

### Neutras / trade-offs

- El fix NO toca `/etc/resolv.conf` (que sigue apuntando al gateway de
  libvirt). Solo reordena las bases de datos NSS. Esto es intencional:
  el gateway puede dar DNS local para VMs locales, pero debe delegar
  las externas al upstream DNS.

## Como verificar que esta aplicado

```bash
ssh <nombre-usuario>@192.168.122.20 'cat /etc/nsswitch.conf | grep "^hosts:"'
# Debe mostrar: hosts:      files dns myhostname
```

Si tras un `deploy.sh` el playbook vuelve a colgarse en `dnf install`,
ejecutar el fix manualmente:

```bash
ssh <nombre-usuario>@192.168.122.20 \
  'sudo sed -i "s|^hosts:.*|hosts:      files dns myhostname|" /etc/nsswitch.conf'
```

## Lección aprendida: `sudo-rs` no existe en AlmaLinux 9

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

## Alternativas consideradas

- **Configurar DNS en cloud-init user-data:** descartada porque
  `/etc/resolv.conf` se regenera en cada arranque DHCP, no es
  persistente.
- **Cambiar el `user-data` para incluir un script que arregle
  nsswitch.conf:** descartada porque complica el bloque `generar_user_data`
  del `deploy.sh` sin ventaja sobre el fix post-provisioning.
- **Configurar `NM_CONTROLLED=no` en la interfaz de red y poner DNS
  estatico:** descartada por ser invasiva y no portable a otros
  hipervisores.

## Referencias

- https://github.com/AlmaLinux/cloud-images/issues (buscar "nsswitch DNS")
- man nsswitch.conf(5)
- Red Hat Bugzilla #1945550 (orden NSS incorrecto en cloud images)
