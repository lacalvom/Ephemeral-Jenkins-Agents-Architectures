# ADR-0004: Aprovisionamiento vía Cloud (sin nodo JNLP permanente)

- **Estado:** Aceptado
- **Fecha:** 2026-09-12
- **Decisor:** DevOps Team

## Contexto

El lab Podman-Host usaba un **nodo JNLP permanente** en el podman-host
(`agent_registration` + `jenkins-agent.service`); el pipeline creaba
contenedores efímeros *desde* ese nodo con `agent { docker }`.

En el modelo Cloud es al revés: el **controller habla directamente con la API
de Podman** (vía `docker-plugin`) y el plugin aprovisiona **contenedores-agente
bajo demanda**, seleccionables por *label*. No hace falta un nodo permanente.

## Decisión

1. **Sin nodo JNLP permanente**: no se usa `agent_registration` ni
   `jenkins-agent.service`. La Cloud (`create-cloud.groovy`) registra el
   aprovisionamiento; los contenedores conectan por **JNLP** (puerto 50000
   habilitado en el controller).
2. **Workspace y cachés se configuran en la PLANTILLA** (no en el pipeline):
   - workspace compartido montado desde el host
     (`/datos/jenkins/pipelines-workspace`), `remoteFs` apuntando ahí;
   - cachés como *named volumes* (`maven-cache` en `/cache/.m2`,
     `npm-cache` en `/cache/.npm`);
   - el agente de build monta el socket rootful y fija `CONTAINER_HOST`.

   Los mounts se declaran en el campo `mounts`/`mountsString` del template,
   que **no** usa la sintaxis `-v host:contenedor` sino pares `key=value`
   separados por comas, una línea por mount
   (`type=bind,source=...,destination=...` o
   `type=volume,source=...,destination=...`). Pasar la sintaxis `-v` aborta
   el aprovisionamiento con
   `Invalid mount: expected key=value comma separated…`.

   El pipeline solo usa `agent { label '...' }`.
3. **El código de la app lo copia Ansible** al workspace del job en el
   podman-host (no se usa SCM), igual que en Podman-Host.

## Consecuencias

### Positivas

- El podman-host no necesita el agente residente: menos piezas y menos
  acoplamiento con el controller.
- Los agentes aparecen en `/computer`, con labels, `containerCap`,
  retención, etc. (mentalidad "nodo").
- El pipeline queda limpio (`agent { label }`), sin `args` con `-v`.

### Negativas

- El workspace y las cachés **no** están en el pipeline sino en la plantilla:
  cambiarlos implica tocar infraestructura (o una plantilla por proyecto).
- Depurar es más indirecto (logs del *cloud* y `/computer`, no del stage).

### Neutras / trade-offs

- El workspace compartido en el host es el equivalente funcional a
  `reuseNode` de Podman-Host.

## Alternativas consideradas

- **Mantener un nodo JNLP permanente y usar `agent { docker }`**: es el modelo
  Podman-Host; descartado aquí para no duplicar laboratorios.
- **Workspace interno por contenedor + `stash`/`archiveArtifacts`**: más
  "cloud-native", pero no comparte artefactos entre stages sin trabajo extra.

## Referencias

- `docs/Ephemeral-Jenkins-Agents-Podman-Cloud.md` (secciones 6, 7 y 9)
- `jenkins-config/groovy/create-cloud.groovy`
- `jenkins-config/jobs/reference-pipeline.groovy`
