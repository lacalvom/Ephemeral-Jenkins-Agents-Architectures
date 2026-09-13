# Contribuir

Este documento resume cómo está organizado el proyecto y cómo proponer
cambios en `Ephemeral-Jenkins-Agents-Architectures`.

## Estructura

- `Podman-Host/` — laboratorio del modelo **Podman-Host** (completo):
  - `deploy.sh` / `destroy.sh` — provisioning de bajo nivel (VMs KVM/libvirt
    + cloud-init) y limpieza.
  - `ansible/site.yml` — playbook principal (5 fases) que configura Jenkins,
    el Podman Host, el agente y el tooling de Podman Secrets.
  - `jenkins-config/` — configuración inmutable de Jenkins: plugins, scripts
    Groovy (`init.groovy.d`), pipeline de ejemplo y la app de referencia.
  - `legacy-images/` — Containerfiles de imágenes de agentes con toolchains
    antiguos (JDK 6/7/8, Node 8/10/12).
- `Podman-Cloud/` y `Jenkins-Kubernetes/` — laboratorios de los otros dos
  modelos (ver su `README.md`).
- `docs/adr/` (dentro de cada lab, p. ej. `Podman-Host/docs/adr/`) —
  Architecture Decision Records. **Todo cambio técnico relevante debe ir
  acompañado de su ADR.**
- `guides/` — guías generales (comparativa y documento unificado). Las guías
  por modelo viven en `<lab>/docs/`.

## Flujo de trabajo

1. Se parte de un fork y se crea una rama descriptiva.
2. Se mantiene la filosofía del proyecto: **todo lo que se arregla queda
   integrado en el código/playbook**, nunca como paso manual. La regla es
   que `./deploy.sh` (destroy + deploy + playbook) funcione de principio a
   fin sin intervención manual.
3. Si se toca el provisioning, como mínimo se valida con:
   ```bash
   bash -n Podman-Host/deploy.sh && bash -n Podman-Host/destroy.sh
   cd Podman-Host/ansible && ansible-playbook site.yml --syntax-check
   ```
4. Si se tocan el pipeline o los roles, lo ideal es reproducir un ciclo
   `./destroy.sh && ./deploy.sh` completo (dentro de `Podman-Host/`).
5. Se actualiza `CHANGELOG.md` (sección `Unreleased`) y se añade o actualiza el ADR
   correspondiente.

## Convenciones

- Idioma: español (sin tildes en el código y comentarios largos, por
  simplicidad de encoding; en documentación se pueden usar con normalidad).
- No se incluyen datos personales: se usan variables como `<nombre-usuario>` o
  (`$USER`, `$HOME`) en lugar de rutas o usuarios concretos.
- No se suben secretos: se usan `Podman-Host/.env` (deploy.sh) y
  `Podman-Host/ansible/group_vars/all/vault.yml` (Ansible), ambos ignorados
  por git. Las plantillas `.example` sí se versionan.

## Reportar problemas

Al abrir un issue se incluyen: distribución y versión del host, salida del
comando que falla y, si procede, el log del stage de Jenkins y
`journalctl -u jenkins` de la VM.
