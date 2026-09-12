# ADR-015: Reemplazar `crypta` por `sops`+`age` directo, y corregir el driver `pass`

- **Estado:** Aceptado
- **Fecha:** 2026-09-11
- **Decisor:** DevOps Team

## Contexto

Tras corregir el problema de entropía (ADR-014), el playbook completó
la Fase 5, pero el usuario reportó dos avisos:

```
AVISO: crypta no funciona en este host.
Error: /usr/local/bin/crypta: /lib64/libc.so.6: version `GLIBC_2.39'
not found (required by /usr/local/bin/crypta)
```

```
AVISO: el driver 'pass' con GPG sin passphrase tiene limitaciones en
modo rootless sin TTY (mensaje "No public key")
```

Según lo documentado hasta ahora (ADR-005), ambos eran "limitaciones
conocidas, sin workaround". Revisando la guía original
([`Ephemeral-Jenkins-Agents-Podman-host.md`](../guides/Ephemeral-Jenkins-Agents-Podman-host.md), sección "Uso de
Podman Secrets para Credenciales y Configuraciones") y depurando cada
caso a fondo, se encontraron **causas concretas y solucionables** para
ambos.

## Caso 1: `crypta` — glibc, y ademas la API del driver `shell` cambió

### Diagnóstico

Se descargaron y analizaron (con `readelf -V`) **todas** las versiones
publicadas de `crypta` (`v0.1.1` hasta `v0.2.2`, github.com/atareao/crypta):
**todas**, sin excepción, requieren `GLIBC_2.39`. No es un problema de
versión específica — el proyecto entero se compila con un toolchain
que produce binarios incompatibles con AlmaLinux 9 (glibc 2.34).

Además, al intentar registrar el secret en Podman con la sintaxis de
la guía (`--opt path=...,arg1=...,arg2=...`), Podman moderno (5.x)
responde `Error: unknown flag: --opt` y, con la sintaxis corregida
(`--driver-opts`), `Error: invalid shell driver option: "path"`.
Revisando el código fuente real
(`containers/common/pkg/secrets/shelldriver/shelldriver.go`), el
driver `shell` en las versiones actuales de `containers/common` exige
**cuatro** comandos (`lookup`, `store`, `list`, `delete`), no
`path`/`argN`. Cada comando se ejecuta vía `/bin/sh -c` y recibe el ID
interno del secreto en la variable de entorno `SECRET_ID`:

- `lookup`: debe imprimir el valor del secreto en stdout.
- `store`: recibe el valor por stdin, debe guardarlo.
- `list`: debe imprimir un ID por línea.
- `delete`: debe borrar el secreto.

La guía original (y el código que se había portado de ella) usaban una
API de `containers/common` **más antigua**, ya no vigente.

### Decisión

Se elimina `crypta` del rol por completo. El driver `shell` ahora usa
un script propio, desplegado por Ansible
(`templates/sops-shell-driver.sh.j2` → `/usr/local/bin/podman-secret-sops-driver.sh`),
que implementa las 4 acciones llamando directamente a `sops` + `age`
(ambos SÍ compatibles con AlmaLinux 9, verificado):

- `store`: cifra el valor recibido por stdin con `sops --encrypt --age <recipient>` y lo guarda en `{{ podman_secrets_shell_store_dir }}/<SECRET_ID>.enc`.
- `lookup`: descifra ese fichero con `sops --decrypt` y lo imprime.
- `list`/`delete`: operan sobre los ficheros `*.enc` de ese directorio.

El registro del secret pasa a usar el módulo
`containers.podman.podman_secret` (con `driver_opts` como diccionario,
`skip_existing: true` para idempotencia) en vez de un `shell:` con
`podman secret create --opt ...` a mano.

Se valida end-to-end (cifrar, registrar el secret, arrancar un
contenedor con `--secret ...,type=env`, y leer el valor descifrado
correctamente) antes de aplicarlo al rol.

## Caso 2: `pass` — bug real en la extracción del fingerprint, no TTY

### Diagnóstico

El aviso decía que el driver `pass` "requiere TTY interactivo". Se
reprodujo el flujo completo (generar clave GPG sin passphrase,
inicializar `pass`, crear el secret) tal cual lo hace Ansible (sin
pty, `become` no interactivo) en un entorno de prueba, y se descubrió
la causa real:

```yaml
gpg_key_id: "{{ gpg_list_raw.stdout_lines | select('match', '^fpr:') | map('regex_replace', '^fpr:([^:]+):.*', '\\1') | list | first }}"
```

El formato `gpg --with-colons` de un registro `fpr` es
`fpr:::::::::FINGERPRINT:` — el fingerprint es el **campo 10** (hay 8
campos vacíos entre `fpr` y el fingerprint), no "lo que sigue
directamente a `fpr:`". La regex `^fpr:([^:]+):.*` asume que el
fingerprint está en el campo 2; como ese campo está vacío, la regex
**nunca hace match**, y `regex_replace` con un patrón que no matchea
**devuelve la cadena original sin modificar** (comportamiento estándar
de la función, no un error). El resultado (`gpg_key_id`) terminaba
siendo literalmente `"fpr:::::::::FINGERPRINT:"` completo, con el
prefijo y los dos puntos incluidos.

Ese valor se pasaba a `pass init <eso>`, que lo guarda tal cual en
`.gpg-id`. Al cifrar (`podman secret create --driver pass ...`), GPG
busca una clave pública cuyo ID sea esa cadena completa — que
obviamente no existe en el llavero — y responde `No public key`. El
mensaje de error es indistinguible, a simple vista, de un problema
real de pinentry/TTY, pero **no tiene relación** con eso: la clave se
generó explícitamente sin passphrase (`%no-protection`), así que
cifrar con ella nunca debería requerir ningún prompt interactivo.

### Decisión

Se corrige la extracción usando el índice de campo correcto en vez de
una regex frágil:

```yaml
gpg_key_id: "{{ (gpg_list_raw.stdout_lines | select('match', '^fpr:') | list | first).split(':')[9] }}"
```

Validado end-to-end de la misma forma que el driver `shell`: generar
clave → `pass init` → `podman secret create --driver pass` (ahora via
el módulo `containers.podman.podman_secret`, con `skip_existing: true`) →
arrancar un contenedor con `--secret ...,type=env` → el valor se
descifra e inyecta correctamente. **Sin TTY, sin `GPG_TTY`, sin ningún
workaround manual.**

Se elimina el aviso "`pass` requiere TTY interactivo" (era
diagnóstico incorrecto) y se sustituye por un aviso genérico que solo
aparece si la creación del secret realmente falla, con instrucciones
de diagnóstico manual.

## Consecuencias

### Positivas

- Los **tres** drivers (`file`, `pass`, `shell`) funcionan de forma
  automática, sin intervención manual, en AlmaLinux 9.
- Se elimina una dependencia externa (`crypta`, binario de un tercero
  sin garantía de compatibilidad) a favor de `sops`+`age`, ambos con
  binarios oficiales verificados.
- El uso del módulo `containers.podman.podman_secret` con
  `skip_existing: true` en los tres drivers unifica el estilo y hace
  el rol completamente idempotente (antes: `shell:`/`command:` con
  `failed_when: false` o sin ninguna guarda).

### Negativas

- Ninguna relevante. El script del driver `shell` es más código propio
  que mantener (vs. delegar en un binario de terceros), pero es
  simple (< 50 líneas) y no tiene dependencias externas más allá de
  `sops`/`age`, que ya se instalaban de todas formas.

### Neutras / trade-offs

- Si en el futuro `crypta` publica un build compatible con glibc más
  antigua (o AlmaLinux sube de versión a una con glibc 2.39+), se
  podría reintroducir como alternativa, pero no hay urgencia: el
  script propio ya cubre el mismo caso de uso con las mismas garantías
  de seguridad (cifrado en reposo con `age`, Podman solo guarda la
  instrucción de cómo obtener el valor, nunca el valor en sí).

## Alternativas consideradas

- **Compilar `crypta` desde fuente en el propio playbook** (requiere
  Rust/cargo): descartada por el coste de instalar un toolchain
  completo solo para un binario que además no aporta nada que
  `sops`+`age` no den ya por sí solos.
- **Mantener el aviso de "TTY requerido" y dejarlo como limitación
  documentada:** descartada una vez confirmado que era un bug real y
  no una limitación de la herramienta.

## Referencias

- `Podman-Host/docs/adr/0005-podman-secrets-como-root.md`
- `Podman-Host/docs/adr/0013-integrar-secrets-tooling-en-site-yml.md`
- `Podman-Host/docs/adr/0014-entropia-vms-gpg.md`
- https://github.com/containers/common/blob/main/pkg/secrets/shelldriver/shelldriver.go
- https://github.com/getsops/sops
- https://github.com/FiloSottile/age

## Addendum (2026-09-11): el secret `pass` no se creaba, en silencio

Tras aplicar las correcciones anteriores, en un despliegue real el
playbook terminaba sin errores pero **el secret del driver `pass` no
aparecía** en `podman secret ls` (solo `file` y `shell`). Dos causas
encadenadas:

### Causa A: guarda de idempotencia debil al generar la clave GPG

La tarea de generar la clave GPG usaba
`args: { creates: "/root/.gnupg/private-keys-v1.d" }`. Ese directorio
lo crea `gpg` **antes** de terminar de generar la clave. Si una
generación anterior se interrumpió (por ejemplo, el cuelgue por falta
de entropía de ADR-014, que el usuario canceló), el directorio existe
pero **no hay ninguna clave dentro**. En las siguientes ejecuciones,
`creates:` ve el directorio y **se salta la generación para siempre**,
así que `pass` no tiene clave con la que cifrar y la creación del
secret falla.

**Fix:** la guarda ya no se basa en el directorio, sino en si existe
una clave secreta **real**:

```yaml
- name: "... Comprobar si ya existe una clave secreta GPG"
  ansible.builtin.command: "gpg --list-secret-keys --with-colons"
  register: gpg_precheck
  changed_when: false
  failed_when: false

# ... generar solo si no hay "sec:" en gpg_precheck.stdout
```

Además, `pass init` pasa a ejecutarse **siempre** (sin `creates:`), para
garantizar que el almacén `pass` apunta a la clave GPG vigente aunque
`/root/.password-store` ya exista de una ejecución previa.

### Causa B: el fallo quedaba totalmente oculto

La tarea de crear el secret tenía `failed_when: false`. Eso, además de
ocultar el error, hace que `result is failed` sea **siempre `false`**,
de modo que la tarea de aviso posterior (`when: pass_secret_result is
failed`) **nunca se mostraba**. El resultado neto era un fallo
completamente silencioso.

**Fix:** se sustituye `failed_when: false` por `ignore_errors: true`.
`ignore_errors` deja continuar el playbook igual que antes, pero
**conserva `failed: true`** en el resultado registrado, así que el
aviso de diagnóstico ahora sí aparece cuando algo falla.

### Validación

Reproducido el escenario exacto (keyring con
`private-keys-v1.d` pero sin clave) y validado que, con el fix, el rol
detecta la ausencia de clave, la genera, y crea el secret `pass`
correctamente. Verificada también la idempotencia (en la segunda
ejecución se saltan las tareas de generación y el secret queda `ok`,
sin recrearse) y el uso real del secret en un contenedor.

