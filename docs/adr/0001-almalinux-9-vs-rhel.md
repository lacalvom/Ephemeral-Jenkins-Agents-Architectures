# ADR-001: Elegir AlmaLinux 9 como distro base para las VMs

- **Estado:** Aceptado
- **Fecha:** 2026-09-09
- **Decisor:** DevOps Team

## Contexto

La guia original del proyecto ("Arquitectura de Agentes Efimeros con Podman y Jenkins") especifica RHEL/UBI 9 como la distribucion de referencia, por ser la base soportada por Red Hat para entornos empresariales con soporte oficial.

Para este laboratorio montado sobre KVM/libvirt en una workstation local, necesitamos elegir una distro que:

1. Sea 100% libre y redistribuible (sin licencias de Red Hat).
2. Sea 100% compatible a nivel binario con RHEL 9 (mismos paquetes, misma API).
3. Tenga soporte a largo plazo (idealmente > 5 anos).
4. Esté disponible como cloud image oficial descargable desde un proveedor confiable.
5. Soporte oficial del repo de paquetes de Jenkins LTS para "RHEL and derivatives".

## Decision

Adoptamos **AlmaLinux 9** (rama estable "Olive Jaguar", version 9.8 en Sep 2026) como distro base para ambas VMs:

- `jenkins-controller` (AlmaLinux 9.8)
- `podman-host` (AlmaLinux 9.8)

La imagen cloud oficial se descarga desde `https://repo.almalinux.org/almalinux/9/cloud/x86_64/images/`.

## Consecuencias

### Positivas

- AlmaLinux 9 es binario-compatible con RHEL 9. Todos los paquetes, paths, SELinux policies y comportamientos son identicos.
- Soporte oficial de la LTS de Jenkins para "RHEL and derivatives" (el repo `pkg.jenkins.io/redhat-stable` instala sin warnings).
- El repo `EPEL` (necesario para `pass` en el rol de secrets) funciona sin configuracion extra: `dnf install epel-release` ya esta disponible.
- Imagen cloud oficial mantenida por el proyecto AlmaLinux, sin dependencia de mirrors comunitarios.
- Sin coste de licencia. Sin restricciones de redistribution.

### Negativas

- Pequeño desfase temporal respecto a RHEL 9 (las imagenes cloud aparecen dias despues que las de RHEL).
- La comunidad AlmaLinux es mas pequena que la de RHEL/Fedora/CentOS, lo que se traduce en menos tutoriales en internet.

### Neutras / trade-offs

- Todas las decisiones tecnicas tomadas para RHEL 9 (paths, sysctls, modulos kernel) se aplican identicamente a AlmaLinux 9.
- No usamos `ubi9` (Universal Base Image de Red Hat) porque requiere autenticacion en registries oficiales.

## Alternativas consideradas

- **RHEL 9 oficial:** Requiere suscripcion Red Hat y autenticacion en sus repos. Innecesario para un laboratorio local.
- **Rocky Linux 9:** Misma compatibilidad binaria que AlmaLinux, pero el proyecto se fundo mas tarde y su base de usuarios es menor. AlmaLinux llego antes al mercado (Mar 2021 vs Mayo 2021).
- **Oracle Linux 9:** Tambien binario-compatible, pero Oracle a�ade telemetría por defecto que habria que desactivar.
- **Ubuntu 24.04 LTS:** Disponible y con buen soporte, pero introduce un ecosistema de paquetes diferente (apt vs dnf) y la guia del proyecto esta escrita para RHEL/derivatives. Migrar requeriria reescribir todos los roles Ansible.
- **Fedora Server 41:** Rolling release con paquetes mas nuevos, pero ciclo de soporte corto (13 meses) incompatible con la idea de "laboratorio estable".

## Referencias

- https://almalinux.org/
- https://en.wikipedia.org/wiki/AlmaLinux
- https://pkg.jenkins.io/redhat-stable/
