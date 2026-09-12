# ADR-0001: API y motor Podman ROOTFUL en el modelo Podman-Cloud

- **Estado:** Aceptado
- **Fecha:** 2026-09-12
- **Decisor:** DevOps Team

## Contexto

El modelo Podman-Cloud aprovisiona contenedores-agente a través de la **API
de Podman**, usando el plugin `docker-plugin` (Docker Cloud). A diferencia del
lab Podman-Host —donde el pipeline monta el socket rootless del usuario
`jenkins` con `--userns=keep-id`—, aquí **quien corre la API manda**: decide en
qué contexto corren los contenedores y qué "ve" el daemon (store, secrets,
permisos).

Había que elegir entre API/motor **rootful** (root) o **rootless** (jenkins).

## Diagnóstico

Comparativa en el contexto de este laboratorio:

| Aspecto | Rootful (API = root) | Rootless (API = jenkins) |
|---|---|---|
| **Podman Secrets (3 drivers)** | Ve el store de sistema (donde los crea el rol) → `--secret` funciona directo | Ve el store del usuario jenkins; los secrets de sistema NO se ven |
| **Permisos workspace/cachés** | Fácil (root del contenedor = root real) | Requiere `user: 0` o `--userns=keep-id` (el plugin no lo soporta) |
| **Semántica para el plugin** | Lo más parecido a un daemon Docker, para el que se diseñó `docker-plugin` | Más casos límite |
| **Aislamiento** | Menor | Mayor (user namespaces) |

El punto decisivo: los **3 drivers de Podman Secrets** del laboratorio crean
los secretos a **nivel de sistema (root)** (ver ADR-0005 del lab Podman-Host).
Con la API rootless, los agentes Cloud **no los verían**. Con rootful, sí.

## Decisión

Se adopta **rootful para la API y el motor**:

1. La API se sirve como servicio de sistema (`podman-tcp.service`, root) y el
   socket Unix rootful es `/run/podman/podman.sock`.
2. Los contenedores-agente los crea el motor rootful (los procesos del
   contenedor corren como el `user` de la plantilla; en este lab `0`).
3. Las imágenes-agente se construyen **rootful** para que queden en el mismo
   store que usa la API.
4. Los secretos del rol `podman_secrets_tooling` (store de sistema) quedan
   visibles para la API → un template podría consumirlos con `--secret`.

El lab **Podman-Host se mantiene rootless** (allí `--userns=keep-id` sí
funciona a nivel de pipeline); así los dos labs ilustran los dos enfoques.

## Consecuencias

### Positivas

- Los **3 drivers de Podman Secrets** funcionan sin workarounds y quedan
  disponibles para los agentes Cloud.
- **Sin fricción de permisos**: el workspace compartido y las cachés (named
  volumes) son escribibles sin trucos de UID.
- El `docker-plugin` opera en el modelo para el que fue diseñado (daemon
  rootful), reduciendo comportamientos inesperados.

### Negativas

- **Menos aislamiento**: los agentes pueden correr como root real del host.
  Aceptable en una red libvirt aislada de un solo propósito.
- Requiere un servicio de sistema extra (`podman-tcp.service`) y abrir el
  puerto 2376 (con mTLS desde ADR-0005).

### Neutras / trade-offs

- Las imágenes y los secretos quedan en el store **root**
  (`/var/lib/containers/storage`); `podman compose` se ejecuta como root.

## Alternativas consideradas

- **Rootless (API = jenkins) con secretos en el store del usuario:** coherente
  con el lab Podman-Host, pero obliga a cambiar el rol de secrets (ADR-005) a
  rootless y a depender del socket rootless; más frágil.
- **Rootless API + secretos root (hibrido):** es el estado inicial; los
  secretos se crean pero los agentes Cloud no los ven. Descartado por incoherente.

## Referencias

- `docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md` (sección 4.2)
- ADR-0005 del lab Podman-Host (Podman Secrets a nivel de sistema)
- https://docs.podman.io/en/latest/markdown/podman-system-service.1.html
