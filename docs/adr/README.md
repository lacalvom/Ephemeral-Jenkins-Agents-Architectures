# Architecture Decision Records (ADRs)

Este directorio contiene los registros de decisiones arquitectónicas del
proyecto `jenkins-podman-lab`. Cada ADR documenta una decision tecnica
importante, su contexto, las alternativas consideradas y las consecuencias.

## Indice

| ADR | Titulo | Estado |
|-----|--------|--------|
| [ADR-001](./0001-almalinux-9-vs-rhel.md) | Elegir AlmaLinux 9 como distro base | Aceptado |
| [ADR-002](./0002-dhcp-estatico-vs-cloud-init.md) | IPs fijas via DHCP estatico en libvirt en vez de network-config en cloud-init | Aceptado |
| [ADR-003](./0003-init-groovy-vs-jcasc.md) | Plan B: scripts init.groovy.d en lugar de JCasC declarativo | Aceptado (con JCasC como objetivo futuro) |
| [ADR-004](./0004-secret-agente-via-rest-api.md) | Leer el secret del agente desde la API REST de Jenkins | Aceptado |
| [ADR-005](./0005-podman-secrets-como-root.md) | Podman Secrets a nivel de sistema, no rootless del usuario jenkins | Aceptado |
| [ADR-006](./0006-plugin-manager-tool.md) | Usar Plugin Installation Manager Tool con versiones fijadas del update center estable | Aceptado |
| [ADR-007](./0007-nsswitch-conf-dns-fix.md) | Fix post-provisioning de nsswitch.conf para que DNS funcione en AlmaLinux 9 cloud image | Aceptado |
| [ADR-008](./0008-jnlp-port-and-installstate-fix.md) | Habilitar JNLP port y persistir InstallState en init.groovy para que el lab sea funcional desde el primer arranque | Aceptado |
| [ADR-009](./0009-reference-app-sin-scm.md) | Aplicacion de referencia (Java/Maven + Angular/npm) sin SCM, y Managed Config Files sin JCasC | Aceptado |
| [ADR-010](./0010-habilitar-podman-socket.md) | Habilitar explicitamente el socket rootless de Podman (podman.socket) para agentes efimeros | Aceptado |
| [ADR-011](./0011-security-opt-label-disable-podman-socket.md) | `--security-opt label=disable` en vez de `:z` para montar el socket de Podman (SELinux) | Aceptado |
| [ADR-012](./0012-instalar-podman-compose.md) | Instalar `podman-compose` (EPEL) para las pruebas manuales de la reference-app | Aceptado |
| [ADR-013](./0013-integrar-secrets-tooling-en-site-yml.md) | Integrar `podman_secrets_tooling` en `site.yml` (Fase 5) y automatizar los 3 drivers | Aceptado |
| [ADR-014](./0014-entropia-vms-gpg.md) | Entropia suficiente en las VMs para operaciones criptograficas (GPG): `--rng`, `haveged`, timeout | Aceptado |
| [ADR-015](./0015-fix-crypta-y-pass-driver.md) | Reemplazar `crypta` por `sops`+`age` directo, y corregir el driver `pass` (bug real, no TTY) | Aceptado |

## Como escribir un nuevo ADR

1. Copia `TEMPLATE.md` con el siguiente numero correlativo (e.g. `0007-nombre.md`).
2. Rellena las secciones. El formato esperado es:
   - **Estado:** Propuesto / Aceptado / Supersedido
   - **Fecha:** Fecha de la decision
   - **Contexto:** Por que surge la decision
   - **Decision:** Que se hizo
   - **Consecuencias:** Positivas, negativas, neutrales
   - **Alternativas:** Que otras opciones se consideraron
3. Anade la entrada al indice de arriba.
