# ADR-014: Entropía suficiente en las VMs para operaciones criptográficas (GPG)

- **Estado:** Aceptado
- **Fecha:** 2026-09-10
- **Decisor:** DevOps Team

## Contexto

Tras integrar `podman_secrets_tooling` en `site.yml` (ADR-013), el
playbook se quedó colgado indefinidamente, sin ningún error, en la
tarea `podman_secrets_tooling | DRIVER PASS | Generar clave GPG sin
passphrase`.

## Diagnóstico

Las VMs se crean con `virt-install` sin especificar ningún dispositivo
de entropía (`--rng`). Esto significa que la VM arranca **sin**
dispositivo `virtio-rng`, y el pool de entropía del kernel invitado
depende únicamente de fuentes internas lentas (interrupciones de
disco/red, jitter de scheduling), que en una VM recién arrancada,
headless, sin actividad de usuario, tardan mucho en acumularse.

`gpg --batch --generate-key` generando una clave RSA de 2048 bits es
una de las primeras operaciones del laboratorio que realmente consume
entropía "de calidad" en cantidad, así que es donde el síntoma
aparece primero: el proceso se queda esperando a que el kernel
considere que hay suficiente entropía acumulada, potencialmente para
siempre en una VM sin fuentes adicionales.

Es un problema muy conocido y documentado en entornos KVM/libvirt y
contenedores ("gpg gen-key hangs", "entropy starvation in VMs").

## Decisión

Se aplican **tres capas** de mitigación, de la más fundamental a la
más defensiva:

### 1. Dispositivo `virtio-rng` en la VM (causa raíz)

`deploy.sh` añade `--rng /dev/urandom` a la invocación de
`virt-install` para ambas VMs. Esto expone un dispositivo
`/dev/hwrng` dentro del invitado que el kernel usa automáticamente
para alimentar su pool de entropía (vía el framework `hw_random`,
sin necesidad de ningún demonio adicional en el invitado). Es la
solución recomendada por la documentación de QEMU/libvirt para este
problema exacto.

### 2. `haveged` en el Podman Host (defensa en profundidad)

El rol `podman_secrets_tooling` instala y arranca `haveged`
(generador de entropía basado en jitter de CPU, sin depender de
hardware) antes de cualquier operación GPG. Esto hace que el rol siga
funcionando correctamente incluso si se ejecuta en una infraestructura
donde no se puede controlar el hipervisor (o si por alguna razón el
`--rng` no llegara a aplicarse).

### 3. Timeout + fallo explícito (red de seguridad)

La generación de la clave GPG se envuelve en `timeout 120`, con una
tarea posterior que falla con un mensaje de diagnóstico claro
(distinguiendo "timeout por entropía" de otros errores de GPG) si el
comando no termina o termina con error. Así, si esta combinación de
capas no fuera suficiente en algún entorno, el playbook **falla
rápido con un mensaje útil** en vez de colgarse en silencio para
siempre.

## Consecuencias

### Positivas

- El playbook nunca vuelve a quedarse colgado indefinidamente por este
  motivo: o tiene entropía suficiente (capas 1+2) o falla en <= 2
  minutos con un diagnóstico claro (capa 3).
- La solución de fondo (`--rng`) también beneficia a **cualquier otra**
  operación criptográfica del laboratorio (TLS, SSH keygen si se
  añadiera en el futuro, etc.), no solo a GPG.

### Negativas

- Ninguna relevante. `haveged` consume una cantidad marginal de CPU en
  background; `--rng /dev/urandom` no tiene coste de seguridad
  relevante en un laboratorio (usa el generador del host, ya
  confiable).

### Neutras / trade-offs

- Si un operador ejecuta este playbook contra una VM ya desplegada
  **antes** de este fix (sin `--rng`), la capa 1 no aplica
  retroactivamente sin recrear la VM; las capas 2 y 3 sí se aplican en
  cualquier caso al re-ejecutar `ansible-playbook site.yml`.

## Alternativas consideradas

- **Reducir `Key-Length` de la clave GPG (ej. a 1024 bits):** descartada;
  no resuelve el problema de fondo (sigue dependiendo de entropía de
  calidad) y reduce la seguridad del ejemplo sin necesidad.
- **`rng-tools` (`rngd`) en vez de `haveged`:** descartada como capa de
  defensa por defecto porque `rngd` reenvía entropía de fuentes de
  hardware (como el propio `virtio-rng`), pero si esa fuente no
  existe, `rngd` no aporta nada por sí solo. `haveged` genera entropía
  de la nada (CPU jitter) y por tanto es más robusto como red de
  seguridad independiente del hipervisor.
- **Ignorar el problema y documentar "puede tardar":** descartada por
  violar la regla del laboratorio de que un `deploy.sh` limpio debe
  funcionar sin intervención manual ni sorpresas.

## Referencias

- `docs/adr/0013-integrar-secrets-tooling-en-site-yml.md`
- https://wiki.qemu.org/Features/VirtIORNG
- https://www.gnupg.org/documentation/manuals/gnupg/Unattended-GPG-key-generation.html
