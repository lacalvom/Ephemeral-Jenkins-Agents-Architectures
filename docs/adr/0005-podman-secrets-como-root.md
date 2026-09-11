# ADR-005: Podman Secrets a nivel de sistema, no rootless del usuario jenkins

- **Estado:** Aceptado
- **Fecha:** 2026-09-09
- **Decisor:** DevOps Team

## Contexto

El rol `podman_secrets_tooling` crea secrets en Podman siguiendo la guia original del proyecto, que describe los tres drivers disponibles (`file`, `pass`, `shell`) usando **Podman rootless** del usuario jenkins.

La primera implementacion usaba `become_user: "{{ jenkins_user }}"` en todos los tasks de gestion de secrets, con variables de entorno:

```yaml
become: true
become_user: "{{ jenkins_user }}"
environment:
  XDG_RUNTIME_DIR: "/run/user/{{ jenkins_uid }}"
```

Los secrets se creaban correctamente (verificado en `~/.local/share/containers/storage/secrets/secrets.json`), pero al ejecutar `podman secret ls` desde el verify final **no aparecian**.

## Causa raiz

**El socket de Podman rootless del usuario jenkins no estaba creado** porque `jenkins-agent.service` (que es lo que crea el socket via systemd user instance) no estaba corriendo en ese momento.

El directorio `/run/user/1100/` existia pero no contenia el socket `podman/podman.sock`. Sin socket, podman no puede comunicarse con el podman engine del usuario.

Ademas, las dependencias entre el servicio del agente (que requiere handshake correcto con Controller) y los secrets de Podman (que requieren socket rootless) creaban un **acoplamiento problematico**: si el handshake falla (como nos paso), no hay socket, y por tanto los secrets no son visibles.

## Decision

Los secrets de Podman se crean **a nivel de sistema (root)**, no rootless. Esto significa:

1. Los tasks de gestion de secrets usan `become: true` **sin** `become_user`.
2. Los secrets se almacenan en `/var/lib/containers/storage/secrets/` (root), no en `~/.local/share/containers/...` (rootless del usuario).
3. Cualquier usuario con acceso al socket del sistema (incluido el agente Jenkins) puede leer los secrets.

El tradeoff es que **se pierde el aislamiento de rootless**: el usuario jenkins ve los secrets del sistema. En este laboratorio es aceptable porque:

- La VM `podman-host` es de un solo proposito (jenkins agent + podman).
- El usuario jenkins es el unico usuario no-root de la VM.
- Los secrets se crean y se consumen en la misma VM (no hay comparticion entre hosts).

En un entorno de produccion con multiples usuarios, volveriamos a rootless, pero eso requeriria asegurar primero que el socket rootless este disponible (via `systemctl --user start podman` o similar).

## Consecuencias

### Positivas

- **Funciona siempre**, sin depender del socket rootless.
- **`podman secret ls` desde Ansible o desde root** ve los secrets.
- Mas simple: no hay que gestionar `XDG_RUNTIME_DIR`, `uidmap`, `subuid`/`subgid`, etc.
- Compatible con el sistema de systemd del laboratorio (que usa `enable-linger jenkins` pero no exige socket rootless).

### Negativas

- **El usuario jenkins no es dueno de los secrets.** Si el operador quiere gestionarlos manualmente, debe hacerlo como root.
- **Menos seguro teoricamente**: cualquier proceso con acceso al socket de podman del sistema puede leer los secrets. En un sistema multi-usuario esto seria un problema.
- **Pierde la ventaja de rootless** que es la opcion recomendada por el proyecto upstream de Podman.

### Neutras / trade-offs

- El agent.jar del agente Jenkins puede leer los secrets del sistema via el socket `/run/podman/podman.sock` (root) o `/run/user/1100/podman/podman.sock` (rootless del jenkins user) si existe.
- Si en el futuro queremos volver a rootless, hay que:
  1. Asegurar que `jenkins-agent.service` arranca antes (lo que ahora SI funciona).
  2. Anadir `become_user: jenkins` + `XDG_RUNTIME_DIR` a todos los tasks.
  3. Verificar que el socket `/run/user/1100/podman/podman.sock` existe.

## Alternativas consideradas

- **Rootless con `enable-linger jenkins`:** Es lo que hacemos (esta en `deploy.sh` via el rol `podman_host`). Pero por si solo no es suficiente: ademas hay que tener un proceso rootless corriendo que cree el socket. Lo intentamos, pero el socket no se creaba sin un servicio rootless activo.
- **Usar un socket del sistema siempre:** Es lo que hicimos finalmente. Variante de la decision actual.
- **Crear un servicio `podman@jenkins.service`:** Opcion valida para produccion. systemd generaria el socket automaticamente al iniciar el servicio. Pero anade complejidad de systemd-user y requiere que el usuario jenkins tenga cgroups delegadas (que si tiene via `enable-linger` y `subuid/subgid`).

## Referencias

- https://docs.podman.io/en/latest/markdown/podman.1.html
- https://github.com/containers/podman/blob/main/docs/source/markdown/podman-secret.1.md
