# ADR-0005: mTLS obligatorio en la API de Podman del laboratorio

- **Estado:** Aceptado (sustituye a [ADR-0003](./0003-api-tcp-sin-tls.md))
- **Fecha:** 2026-09-12
- **Decisor:** DevOps Team

## Contexto

El controller (VM) habla con la API de Podman del `podman-host` para que el
`docker-plugin` aprovisione contenedores-agente. El [ADR-0003](./0003-api-tcp-sin-tls.md)
expuso esa API por **TCP en claro** (`tcp://…:2375`) amparándose en que la red
libvirt del laboratorio está aislada. La propia documentación de Podman avisa
de que la API permite ejecución arbitraria de código como el usuario que la
corre, y recomienda **mTLS o túnel SSH** para cualquier acceso remoto.

Se quiere que el laboratorio enseñe la configuración **segura por defecto**, no
solo advertir de ella.

## Decisión

El laboratorio genera su **propia PKI** (rol `podman_tls`) y expone la API en
**TCP + mTLS** en el puerto **2376** (el convencional para API Docker/Podman con
TLS):

- **CA del laboratorio:** `ca.pem` / `ca-key.pem` (la clave de la CA **nunca**
  sale del `podman-host`).
- **Certificado de servidor** (`CN=podman-cloud-host`) con SAN de IP y DNS del
  `podman-host`: lo usa `podman-tcp.service`.
- **Certificado de cliente** (`CN=jenkins-controller`): Ansible lo copia al
  controller y Jenkins lo guarda como credencial **`DockerServerCredentials`**
  (docker-commons), referenciada por la Cloud.

`podman-tcp.service` arranca con:

```ini
ExecStart=/usr/bin/podman system service --time=0 \
  --tls-cert=/etc/podman/tls/server-cert.pem \
  --tls-key=/etc/podman/tls/server-key.pem \
  --tls-client-ca=/etc/podman/tls/ca.pem \
  tcp://0.0.0.0:2376
```

El flag `--tls-client-ca` es lo que convierte la conexión en **mutua**: sin un
certificado de cliente firmado por la CA, el servidor rechaza la conexión.
En Jenkins, `DockerServerEndpoint(apiUri, "podman-cloud-tls")` hace que
docker-plugin presente el certificado de cliente y valide el de servidor.

Se sigue usando un servicio systemd **aparte** (no un override de
`podman.socket`) para conservar el socket Unix rootful
`/run/podman/podman.sock`, que montan los contenedores-agente que empaquetan
imágenes.

## Consecuencias

### Positivas

- **Confidencialidad e identidad mutuas**: solo el controller (o quien tenga su
  clave) puede usar la API; el controller verifica que habla con el
  `podman-host` auténtico.
- La configuración por defecto del laboratorio es **segura**, no un atajo de
  laboratorio que haya que "arreglar" en producción.
- El certificado de cliente se gestiona como **credencial de Jenkins** (no como
  fichero suelto leído por el pipeline).

### Negativas

- Más piezas que mantener: CA + rotación de certificados (aquí, borrar
  `/etc/podman/tls` y relanzar el playbook).
- Un certificado de cliente filtrado da control total de Podman. Debe tratarse
  como material sensible (por eso `0600` y `no_log` en Ansible).

### Neutras / trade-offs

- El SAN del certificado de servidor incluye la IP del `podman-host`; documentar
  que, si cambia la IP, hay que regenerar la PKI.
- La verificación end-to-end se hace con `ansible.builtin.uri` sobre HTTPS
  `/_ping` presentando el certificado de cliente.

## Alternativas consideradas

- **TCP sin TLS (ADR-0003):** válido solo en red aislada; se descarta como
  configuración por defecto.
- **Túnel SSH** desde el controller al socket rootful: seguro y sin PKI propia,
  pero añade una unidad/túnel que gestionar. Se documenta como alternativa.
- **Generar la PKI con `community.crypto`:** más idiomático en Ansible, pero
  añade una dependencia de colección; se opta por `openssl` CLI (ya disponible)
  para mantener el laboratorio autocontenido.

## Referencias

- `docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md` (secciones 4.1 y 4.4)
- `ansible/roles/podman_tls/`, `ansible/roles/podman_host/templates/podman-tcp.service.j2`
- `jenkins-config/groovy/create-docker-credentials.groovy`, `create-cloud.groovy`
- `podman system service`: https://docs.podman.io/en/latest/markdown/podman-system-service.1.html
- `DockerServerCredentials` / `DockerServerEndpoint`: https://github.com/jenkinsci/docker-commons-plugin
