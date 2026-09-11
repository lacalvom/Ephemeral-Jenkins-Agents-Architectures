# Contribuir

Gracias por tu interes en mejorar `jenkins-podman-lab`. Este documento
resume como esta organizado el proyecto y como proponer cambios.

## Estructura

- `deploy.sh` / `destroy.sh` — provisioning de bajo nivel (VMs KVM/libvirt
  + cloud-init) y limpieza.
- `ansible/site.yml` — playbook principal (5 fases) que configura Jenkins,
  el Podman Host, el agente y el tooling de Podman Secrets.
- `jenkins-config/` — configuracion inmutable de Jenkins: plugins, scripts
  Groovy (`init.groovy.d`), pipeline de ejemplo y la app de referencia.
- `legacy-images/` — Containerfiles de imagenes de agentes con toolchains
  antiguos (JDK 6/7/8, Node 8/10/12).
- `docs/adr/` — Architecture Decision Records. **Todo cambio tecnico
  relevante debe ir acompanado de su ADR.**

## Flujo de trabajo

1. Haz un fork y crea una rama descriptiva.
2. Mantén la filosofia del proyecto: **todo lo que se arregla queda
   integrado en el codigo/playbook**, nunca como paso manual. La regla es
   que `./deploy.sh` (destroy + deploy + playbook) funcione de principio a
   fin sin intervencion manual.
3. Si tocas el provisioning, valida como minimo:
   ```bash
   bash -n deploy.sh && bash -n destroy.sh
   cd ansible && ansible-playbook site.yml --syntax-check
   ```
4. Si tocas el pipeline o los roles, idealmente reproduce un ciclo
   `./destroy.sh && ./deploy.sh` completo.
5. Actualiza `CHANGELOG.md` (seccion `Unreleased`) y anade/actualiza el ADR
   correspondiente.

## Convenciones

- Idioma: espanol (sin tildes en el codigo/comentarios largos, por
  simplicidad de encoding; en documentacion se puede usar con normalidad).
- No incluyas datos personales: usa `<nombre-usuario>` o variables
  (`$USER`, `$HOME`) en lugar de rutas o usuarios concretos.
- No subas secretos: usa `.env` (deploy.sh) y
  `ansible/group_vars/all/vault.yml` (Ansible), ambos ignorados por git.
  Las plantillas `.example` sí se versionan.

## Reportar problemas

Al abrir un issue incluye: distribucion y version del host, salida del
comando que falla, y (si aplica) el log del stage de Jenkins y
`journalctl -u jenkins` de la VM.
