# ADR-011: `--security-opt label=disable` en vez de `:z` para el socket de Podman

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisor:** DevOps Team

## Contexto

Tras corregir el bug del socket inexistente (ADR-010), el `reference-pipeline`
llegó más lejos pero volvió a fallar en los stages de empaquetado
(`Empaquetar Imagen Backend/Frontend`), esta vez con:

```
Cannot connect to Podman. Please verify your connection...
Error: unable to connect to Podman socket: Get "http://d/v5.8.2/libpod/_ping":
dial unix /run/podman/podman.sock: connect: permission denied: unix:///run/podman/podman.sock
```

Lo llamativo es que, comprobado manualmente por SSH, el socket **sí**
existía en el host con los permisos correctos:

```
srw-rw----. 1 jenkins jenkins 0 Sep 10 13:54 /run/user/1100/podman/podman.sock
```

Y el contenedor efímero se arrancaba con `-u 1100:1100 --userns=keep-id`,
por lo que el proceso dentro del contenedor debería mapear exactamente
al UID/GID propietario del socket en el host. El permission denied no
tenía explicación a nivel de permisos POSIX (DAC).

## Diagnóstico

El volumen se montaba así en `reference-pipeline.groovy`:

```
-v /run/user/1100/podman/podman.sock:/run/podman/podman.sock:z
```

El sufijo `:z` le indica a Podman que **relabele** el fichero origen a
un contexto SELinux "compartido entre contenedores" (`container_file_t`
normalmente). El problema es que el socket real de `podman.socket` no
usa ese label: usa uno específico (creado por la política del paquete
`container-selinux` para ficheros bajo `/run/user/<uid>/podman/`) que
**sí** tiene permitida la operación `connectto` desde procesos en
contenedores — es precisamente el label pensado para este caso de uso
("Podman-outside-of-Podman"). Al forzar el relabel con `:z`, se
sobrescribe ese label correcto por uno genérico que la política **no**
tiene autorizado para `unix_stream_socket:connectto`, y SELinux deniega
la conexión (el kernel devuelve `EACCES`, que se ve exactamente igual
que un fallo de permisos POSIX).

Esto coincide con la advertencia oficial de la documentación de
troubleshooting de Podman (sección *"Can't use volume mount, get
permission denied"*):

> Do not relabel system directories and content. Relabeling system
> content might cause other confined services on your machine to
> fail. For these types of containers we recommend having SELinux
> separation disabled. The option `--security-opt label=disable` will
> disable SELinux separation for the container.

El socket de `podman.socket` es exactamente ese "contenido de sistema
gestionado por un servicio confinado" al que se refiere la nota.

Este bug tampoco se había detectado antes porque, hasta esta sesión,
el pipeline nunca había llegado a ejecutar realmente un `podman build`
contra el socket montado (ver ADR-009, ADR-010: los bugs anteriores
bloqueaban la ejecución antes de llegar aquí).

## Decisión

Sustituir, en los dos stages de empaquetado de
`jenkins-config/jobs/reference-pipeline.groovy`:

```diff
- args "--userns=keep-id -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock:z"
+ args "--userns=keep-id --security-opt label=disable -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock"
```

`--security-opt label=disable` desactiva la separación SELinux **solo
para ese contenedor efímero concreto** (no afecta al resto del host ni
a otros contenedores). Es la opción explícitamente recomendada por el
propio proyecto Podman para este escenario.

Los volúmenes con nombre (`maven-cache-N`, `npm-cache-N`) **no** se
tocan: esos sí son volúmenes nuevos y exclusivos del contenedor, para
los que `:z`/`:Z` es el uso correcto y documentado.

## Consecuencias

### Positivas

- El `reference-pipeline` completo (compilación + empaquetado) funciona
  de extremo a extremo con SELinux en `enforcing`, sin necesidad de
  bajar la política del host a `permissive`.
- La solución está acotada al contenedor que realmente necesita hablar
  con el socket del host; no se toca la política SELinux del sistema
  ni se usan `setsebool`/`semanage` globales.

### Negativas

- El agente efímero `ubi9/podman` pierde el aislamiento SELinux mientras
  construye imágenes. Es un trade-off inherente a compartir el socket
  del motor de contenedores del host con un contenedor (equivalente al
  riesgo ya aceptado, documentado en ADR-005, de exponer el socket de
  Podman a los agentes de build).

### Neutras / trade-offs

- Si en el futuro se quisiera mantener SELinux activo también dentro de
  este contenedor, la alternativa sería aplicar un `semanage fcontext`
  + `restorecon` para que el punto de montaje **destino**
  (`/run/podman/podman.sock` dentro del contenedor) reciba el mismo
  label que el socket de origen sin pasar por el relabeling automático
  de `:z`. Se descarta por complejidad frente al beneficio marginal en
  un laboratorio.

## Alternativas consideradas

- **Quitar `:z` sin añadir nada:** podría funcionar si el label
  original del socket ya está autorizado por la política para
  `connectto` desde contenedores (que es lo que se sospecha), pero sin
  poder ejecutar `ausearch`/`sealert` directamente contra la VM no es
  seguro confirmarlo. `--security-opt label=disable` es la opción
  robusta y explícitamente documentada, así que se prefiere por
  fiabilidad frente a una opción no verificada.
- **Poner SELinux en `permissive` o `disabled` a nivel de sistema:**
  descartada por ser un cambio global e innecesariamente amplio para
  resolver un problema acotado a un único bind-mount.
- **`setsebool -P container_manage_cgroup true` u otros booleanos
  globales:** descartada porque ese booleano concreto resuelve un
  problema distinto (cgroups con systemd dentro de contenedores), no
  el `connectto` de sockets Unix.

## Referencias

- `Podman-Host/docs/adr/0010-habilitar-podman-socket.md`
- https://github.com/containers/podman/blob/main/troubleshooting.md (sección "Can't use volume mount, get permission denied")
