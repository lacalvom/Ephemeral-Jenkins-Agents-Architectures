# ADR-010: Habilitar explícitamente el socket rootless de Podman (`podman.socket`)

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisor:** DevOps Team

## Contexto

Al desplegar el laboratorio de extremo a extremo por primera vez con
código real en el `reference-pipeline` (ver ADR-009), los stages de
compilación (Maven, npm) funcionaron correctamente, pero los stages de
empaquetado (`Empaquetar Imagen Backend` / `Empaquetar Imagen Frontend`)
fallaron al intentar levantar el agente efímero `ubi9/podman`:

```
Error: statfs /run/user/1100/podman/podman.sock: no such file or directory
```

El pipeline monta ese socket con
`-v /run/user/1100/podman/podman.sock:/run/podman/podman.sock:z` para
que el contenedor efímero pueda hablar con el Podman del **host**
(Podman-outside-of-Podman, análogo a Docker-outside-of-Docker) y así
construir imágenes sin necesitar privilegios adicionales ni Podman
anidado.

> **Nota:** ese `:z` se elimino despues: el montaje correcto usa
> `--security-opt label=disable` y **sin** `:z` (ver
> [ADR-011](./0011-security-opt-label-disable-podman-socket.md)).

## Diagnóstico

Instalar el paquete `podman` no activa automáticamente su socket
rootless. Podman expone la unidad **vendor** `podman.socket` en
`/usr/lib/systemd/user/podman.socket` (activación por socket de la
API REST de Podman), pero esa unidad viene **deshabilitada y parada**
por defecto tras una instalación nueva.

El rol `podman_host` ya habilitaba `loginctl enable-linger` (para que
los servicios `--user` del usuario `jenkins` sigan vivos sin sesión
activa) y desplegaba `jenkins-agent.service`, pero **nunca arrancaba
`podman.socket`**. Sin ese socket activo, el fichero
`/run/user/1100/podman/podman.sock` simplemente no existe, y cualquier
intento de montarlo con `-v` en un `docker run`/`podman run` falla con
"no such file or directory".

Este bug no se detectó en sesiones anteriores porque el
`reference-pipeline` nunca se había ejecutado con código real que
llegara hasta los stages de empaquetado (ver ADR-009).

## Decisión

Añadir a `ansible/roles/podman_host/tasks/main.yml`, justo después de
habilitar el linger:

```yaml
- name: "podman_host | Habilitar y arrancar el socket rootless de Podman para {{ jenkins_user }}"
  ansible.builtin.systemd:
    name: podman.socket
    scope: user
    enabled: true
    state: started
  become: true
  become_user: "{{ jenkins_user }}"
  environment:
    XDG_RUNTIME_DIR: "/run/user/{{ jenkins_uid }}"
```

Seguido de una verificación explícita (`stat` + `fail` con mensaje
claro) de que el fichero de socket existe en disco tras habilitarlo,
para que un futuro fallo de esta naturaleza se detecte **durante el
`ansible-playbook`**, no minutos después dentro de un build de
Jenkins con un stacktrace de Java poco legible.

Se reutiliza el mismo patrón `become`/`become_user`/`XDG_RUNTIME_DIR`
que ya usa la tarea de `jenkins-agent.service` en el mismo rol (patrón
ya validado: el agente se conecta correctamente).

## Consecuencias

### Positivas

- El `reference-pipeline` completo (compilación + empaquetado en
  imagen) funciona desde el primer `deploy.sh`, sin pasos manuales.
- El fallo, si ocurriera por otra causa (paquete podman no instalado,
  usuario sin linger, etc.), ahora se detecta en la fase de
  provisioning con un mensaje explícito, no en un build de Jenkins.

### Negativas

- Ninguna relevante: es una unidad vendor estándar de Podman, no se
  introduce superficie de ataque nueva (el socket ya se diseñó para
  este uso, ver ADR-005 sobre el modelo de Podman Secrets/rootless).

### Neutras / trade-offs

- El socket queda escuchando permanentemente (activado por systemd,
  no por-demanda de un build concreto). Es el comportamiento estándar
  recomendado por Podman para este caso de uso (Podman-outside-of-Podman)
  y no supone overhead relevante en reposo.

## Alternativas consideradas

- **Arrancar el socket "a demanda" desde el propio pipeline** (`podman
  system service` en background antes de cada build): descartada por
  ser menos fiable (proceso huérfano, necesidad de gestionar su ciclo
  de vida) que una unidad systemd declarativa y persistente.
- **Usar Podman rootful (root) en vez de rootless para el socket**:
  descartada por inconsistencia con el resto del laboratorio, que
  corre todo como el usuario `jenkins` (UID 1100) sin privilegios de
  root en el plano de ejecución de contenedores.

## Referencias

- `Podman-Host/docs/adr/0009-reference-app-sin-scm.md`
- https://docs.podman.io/en/latest/markdown/podman-system-service.1.html
