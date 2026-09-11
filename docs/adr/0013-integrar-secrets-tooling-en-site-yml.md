# ADR-013: Integrar `podman_secrets_tooling` en `site.yml` (Fase 5) y automatizar los 3 drivers

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisor:** DevOps Team

## Contexto

Desde el inicio del proyecto, el tooling de cifrado para Podman Secrets
(`age`, `sops`, `crypta`, `gpg`, `pass`) se aplicaba con un playbook
**separado**, `ansible/secrets-tooling.yml`, que requería:

1. Editar `ansible/hosts.ini` para descomentar/rellenar el grupo
   `[podman_secret_hosts]`.
2. Ejecutar manualmente:
   ```bash
   ansible-playbook -i hosts.ini secrets-tooling.yml \
     -e "enabled_drivers=[file]" -e "create_example_secrets=true"
   ```

Esto significaba que, tras `deploy.sh` + `ansible-playbook site.yml`,
el laboratorio quedaba funcional para Jenkins/Podman pero **sin**
Podman Secrets configurado, salvo que el operador ejecutara este
tercer paso manual adicional — contradiciendo la regla del proyecto de
que un `deploy.sh` limpio debe dejarlo todo funcional.

## Decisión

1. **Fusionar el rol en `site.yml`** como una nueva Fase 5, que corre
   sobre `podman_hosts` con `enabled_drivers: [file, pass, shell]` y
   `create_example_secrets: true` fijados directamente en el playbook
   (no hace falta pasar `-e` para obtener el comportamiento completo).
2. **Eliminar `ansible/secrets-tooling.yml`** y el grupo
   `[podman_secret_hosts]` de `hosts.ini`: el rol ahora se aplica
   siempre sobre `podman_hosts`, el mismo grupo que ya usan las Fases
   3 y 4.
3. **`deploy.sh` ejecuta `ansible-playbook site.yml` automáticamente**
   al final (ver también el cambio en el propio script): ya no hace
   falta ningún comando manual tras `./deploy.sh`.

## Bugs encontrados durante la integración

Al pasar de "playbook opcional invocado a mano con `-e`" a "siempre
integrado con valores fijados en YAML", aparecieron dos bugs latentes
que nunca se habían manifestado porque el rol nunca se había ejecutado
con `enabled_drivers` como lista YAML nativa (solo como string via
`-e "enabled_drivers=[file]"`):

### Bug 1: conversión de `enabled_drivers` rompía TODOS los drivers

La tarea original convertía `enabled_drivers` con:
```yaml
enabled_drivers_list: "{{ enabled_drivers | regex_replace('\\[|\\]', '') | split(',') | map('trim') | list }}"
```
Esto asume que `enabled_drivers` es un **string** (`"[file,pass]"`).
Cuando `enabled_drivers` es una **lista YAML nativa**
(`[file, pass, shell]`, que es como se pasa ahora desde `site.yml`),
Jinja2 la convierte primero a su representación de texto de Python
(`"['file', 'pass', 'shell']"`) antes de aplicar `regex_replace`, y el
resultado final queda con comillas sueltas dentro de cada elemento:
`["'file'", "'pass'", "'shell'"]`. Todas las condiciones
`when: "'file' in enabled_drivers_list"` de las tareas de cada driver
comparaban contra `"file"` (sin comillas), así que **ninguna
coincidía nunca** — el rol se ejecutaba, instalaba los binarios, pero
**no creaba ningún secret de ningún driver**, sin dar ningún error.

Se corrigió detectando el tipo de entrada antes de decidir cómo
convertirla:
```yaml
enabled_drivers_list: >-
  {{ enabled_drivers if (enabled_drivers is sequence and enabled_drivers is not string)
     else (enabled_drivers | regex_replace('\[|\]', '') | split(',') | map('trim') | list) }}
```
Validado localmente con `ansible-playbook` contra ambos casos (lista
nativa y string `-e`) antes de aplicarlo.

### Bug 2: paths de `$HOME` inconsistentes con el modelo "root" de ADR-005

ADR-005 estableció que las tareas de gestión de secrets corren como
**root** (`become: true` sin `become_user`), pero varias rutas de
comprobación de idempotencia (`creates: ...`) y la variable
`age_key_dir` seguían apuntando a `/home/{{ jenkins_user }}/...`. Como
GPG/pass/age-keygen se ejecutan como root, en realidad escriben en
`/root/...`; las comprobaciones de idempotencia contra
`/home/jenkins/...` **nunca coincidían**, así que esas tareas (generar
clave GPG, inicializar `pass`, generar clave `age`) se re-ejecutaban
en cada `ansible-playbook site.yml`, generando una clave GPG/age nueva
cada vez en lugar de reutilizar la existente.

Se corrigieron todas las rutas para apuntar a `/root/...`
(`age_key_dir`, `creates:` de GPG y `pass init`, y el path de
almacenamiento de `crypta`), y se cambió el `owner`/`group` del
directorio de claves `age` de `jenkins:jenkins` a `root:root` para ser
consistentes con quién realmente escribe ahí.

Ninguno de estos dos bugs se había detectado antes porque el rol nunca
se había ejecutado de forma repetida con `enabled_drivers` como lista
real; con la integración en `site.yml` (que sí se ejecuta en cada
`deploy.sh`), ambos se manifestarían en cada despliegue.

## Consecuencias

### Positivas

- `./deploy.sh` deja el laboratorio 100% funcional, incluyendo Podman
  Secrets con sus 3 drivers, sin ningún paso manual.
- Se eliminan dos bugs de idempotencia/lógica que habrían afectado a
  cualquier usuario que integrara este rol de la forma "moderna"
  (variables YAML en vez de `-e` strings).
- El inventario (`hosts.ini`) queda más simple: un solo grupo por rol
  de máquina, sin grupos "opcionales" que hay que recordar activar.

### Negativas

- Cada `deploy.sh`/`ansible-playbook site.yml` ahora también instala
  ~4 binarios adicionales (age, sops, crypta) y genera una clave GPG y
  una clave age, incluso si el operador no piensa usar Podman Secrets
  en esa sesión del laboratorio. Aumenta el tiempo total de
  provisioning en un par de minutos.

### Neutras / trade-offs

- Sigue siendo posible pasar `-e "enabled_drivers=[file]"` para
  limitar los drivers si se relanza `site.yml` manualmente con otra
  configuración; la lógica de conversión ahora soporta ambos formatos.

## Alternativas consideradas

- **Mantener `secrets-tooling.yml` como playbook aparte, pero
  documentarlo mejor:** descartada porque no resuelve el problema de
  fondo (un `deploy.sh` limpio debe dejarlo todo funcional sin pasos
  manuales).
- **Hacer el tooling de secrets condicional a una variable de entorno
  de `deploy.sh` (ej. `ENABLE_SECRETS=true ./deploy.sh`):** descartada
  por simplicidad; el coste adicional de tiempo (~2 min) no justifica
  la complejidad de un flag opcional en un laboratorio de un solo
  propósito.

## Referencias

- `docs/adr/0005-podman-secrets-como-root.md`
- `ansible/roles/podman_secrets_tooling/`
