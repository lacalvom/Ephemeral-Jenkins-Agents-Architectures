# ADR-0003: Exposición de la API de Podman por TCP (sin TLS) en el laboratorio

- **Estado:** Sustituido por [ADR-0005](./0005-mtls-api-podman.md)
- **Fecha:** 2026-09-12
- **Decisor:** DevOps Team

> **Nota (2026-09-12):** esta decisión (TCP en claro, puerto 2375) se
> mantiene aquí como registro histórico. El laboratorio ha evolucionado a
> **mTLS obligatorio** en el puerto 2376; ver
> [ADR-0005](./0005-mtls-api-podman.md). El servicio sigue siendo un
> servicio systemd aparte que conserva el socket Unix rootful.

## Contexto

El controller (VM) debe hablar con la API de Podman del `podman-host` para que
el `docker-plugin` aproxime contenedores. La API vive en un socket Unix
(`/run/podman/podman.sock`, rootful); el controller no está en esa máquina, así
que hay que exponerla de alguna forma: TCP (con o sin TLS) o túnel SSH.

## Decisión

En el laboratorio se expone por **TCP sin TLS** (`tcp://192.168.122.31:2375`),
dentro de la red libvirt aislada `192.168.122.0/24`. Se levanta un servicio
systemd **de sistema**:

```ini
# /etc/systemd/system/podman-tcp.service
[Service]
ExecStart=/usr/bin/podman system service --time=0 tcp://0.0.0.0:2375
```

Motivos por los que se usa un servicio **aparte** y no un override de
`podman.socket`:

- `podman system service` **no admite más de un socket por proceso**.
- Se necesita **conservar** el socket Unix rootful `/run/podman/podman.sock`,
  porque lo montan los contenedores-agente que empaquetan imágenes
  (`CONTAINER_HOST=unix:///run/podman/podman.sock`).

Se abre además el puerto 2375 en `firewalld` (best-effort) para el controller.

## Consecuencias

### Positivas

- Configuración mínima y funcional para el lab; el controller conecta directo.
- Se conserva el socket Unix para el agente de build.

### Negativas

- **Inseguro por diseño**: la API de Podman da control total (ejecución
  arbitraria como root). **Solo válido en una red aislada**. No copiar a
  producción. Para producción: **mTLS** (`--tls-cert/--tls-key/--tls-client-ca`)
  o **túnel SSH**.

### Neutras / trade-offs

- El puerto 2375 queda escuchando en `0.0.0.0`. La red de libvirt no es
  accesible desde fuera del host KVM.

## Alternativas consideradas

- **Túnel SSH** desde el controller al socket rootful: más seguro, pero añade
  una unidad/túnel que gestionar; se documenta como alternativa en la guía.
- **TCP + mTLS**: lo recomendado para producción; más laborioso (CA + certs)
  y no aporta en una red aislada de laboratorio.

## Referencias

- `docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md` (sección 4.1)
- https://docs.podman.io/en/latest/markdown/podman-system-service.1.html
