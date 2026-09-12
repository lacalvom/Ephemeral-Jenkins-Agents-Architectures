# ADR-012: Instalar `podman-compose` (EPEL) para las pruebas manuales de la reference-app

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisor:** DevOps Team

## Contexto

Tras corregir ADR-010 y ADR-011, el `reference-pipeline` compila y
empaqueta las imágenes (`reference-backend:latest`,
`reference-frontend:latest`) sin problemas. Al intentar probarlas
manualmente en el podman-host con el `podman-compose.yml` incluido en
`jenkins-config/samples/reference-app/` (ver ADR-009), el comando
falló:

```
$ podman compose -f podman-compose.yml up -d
Error: looking up compose provider failed
7 errors occurred:
	* exec: "/home/jenkins/.docker/cli-plugins/docker-compose": ...no such file...
	* exec: "docker-compose": executable file not found in $PATH
	* exec: "podman-compose": executable file not found in $PATH
```

## Diagnóstico

`podman compose` no implementa Compose por sí mismo: es un
*dispatcher* que busca, en orden, un binario `docker-compose`
(v1), el plugin CLI `docker compose` (v2, bajo
`*/cli-plugins/docker-compose`), o el script `podman-compose`
(implementación en Python del proyecto `containers/podman-compose`).
AlmaLinux 9 con el paquete `podman` **no instala ninguno de los tres**
por defecto.

## Decisión

Instalar `podman-compose` desde EPEL 9 en el rol `podman_host`
(`ansible/roles/podman_host/tasks/main.yml`), siguiendo el mismo
patrón ya usado en `podman_secrets_tooling` para habilitar EPEL
(`epel-release` vía `dnf`, ver ADR previo de esa herramienta):

```yaml
- name: "podman_host | Asegurar EPEL habilitado (para el paquete 'podman-compose')"
  ansible.builtin.dnf:
    name: epel-release
    state: present

- name: "podman_host | Instalar podman-compose"
  ansible.builtin.dnf:
    name: podman-compose
    state: present
```

Se eligió `podman-compose` (paquete Python, EPEL) sobre instalar el
plugin CLI `docker compose` v2 (binario Go de Docker Inc., sin
paquete oficial en los repos de RHEL/AlmaLinux) porque:
- Ya se usa EPEL en este laboratorio para otra herramienta
  (`podman_secrets_tooling` → `pass`), así que no introduce una fuente
  de paquetes nueva.
- Es la implementación "nativa" del ecosistema Podman, mantenida por
  el mismo proyecto `containers/`.

## Consecuencias

### Positivas

- `podman compose -f podman-compose.yml up -d` funciona en el
  podman-host sin pasos manuales adicionales tras el `deploy.sh`.

### Negativas

- `podman-compose` (Python) no es 100% idéntico a `docker compose`
  (Go/v2): puede haber pequeñas diferencias de comportamiento en
  casos avanzados de la spec de Compose. Para el `podman-compose.yml`
  de este laboratorio (dos servicios, healthchecks simples,
  `depends_on` con `condition: service_healthy`) la versión de EPEL 9
  (1.5.0) es compatible.

### Neutras / trade-offs

- Se añade EPEL como fuente de paquetes en el podman-host (ya estaba
  presente como opción en `podman_secrets_tooling`, ahora pasa a ser
  parte del despliegue base, no opcional).

## Alternativas consideradas

- **Instalar el binario `docker-compose` v2 descargándolo de GitHub
  Releases:** descartada por no venir empaquetada/firmada por el
  distribuidor del SO, y por añadir un paso de descarga+chmod fuera
  del gestor de paquetes (inconsistente con el resto del laboratorio,
  que instala todo vía `dnf` salvo el Plugin Installation Manager Tool
  de Jenkins, que sí es un `.jar` descargado explícitamente por
  necesidad).
- **No instalar nada y documentar que el operador lo instale a mano:**
  descartada por la regla del laboratorio de que un `deploy.sh` limpio
  debe dejarlo todo funcional sin intervención manual.

## Referencias

- `Podman-Host/docs/adr/0009-reference-app-sin-scm.md`
- https://github.com/containers/podman-compose
- https://packages.fedoraproject.org/pkgs/podman-compose/podman-compose/
