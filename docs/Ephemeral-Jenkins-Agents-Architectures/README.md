# Arquitecturas de Agentes Efímeros en Jenkins — Índice

Guías sobre las **tres formas** de ejecutar builds de Jenkins en **agentes
efímeros** (entornos que nacen para un build y mueren al terminar).

## Orden de lectura recomendado

1. **[1_Ephemeral-jenkins-Agents-Architectures-Compartive.md](./1_Ephemeral-jenkins-Agents-Architectures-Compartive.md)**
   — Comparativa de los tres modelos. **Empieza aquí.**
2. **[2_Ephemeral-Jenkins-Agents-Podman-host.md](./2_Ephemeral-Jenkins-Agents-Podman-host.md)**
   — Modelo **Podman-Host** (`docker-workflow`, `agent { docker { ... } }`).
   Es el modelo del laboratorio `jenkins-podman-lab`.
3. **[3_Ephemeral-Jenkins-Agents-Podman-Cloud.md](./3_Ephemeral-Jenkins-Agents-Podman-Cloud.md)**
   — Modelo **Podman-Cloud** (`docker-plugin`: Cloud + Docker Agent Templates).
4. **[4_Ephemeral-Jenkins-Agents-Kubernetes.md](./4_Ephemeral-Jenkins-Agents-Kubernetes.md)**
   — Modelo **Jenkins-Kubernetes** (`kubernetes-plugin`; el controller puede
   estar dentro o fuera del clúster).
5. **[5_Ephemeral-jenkins-agents-Architectures-models.md](./5_Ephemeral-jenkins-agents-Architectures-models.md)**
   — **Documento unificado** (todo en uno). Úsalo como referencia/consulta.

## Los tres modelos de un vistazo

| # | Modelo | Plugin | Entorno | Grano |
|---|---|---|---|---|
| 2 | **Podman-Host** | `docker-workflow` | contenedor | 1 contenedor por stage |
| 3 | **Podman-Cloud** | `docker-plugin` | contenedor (nodo-agente) | 1 contenedor por build |
| 4 | **Jenkins-Kubernetes** | `kubernetes-plugin` | Pod (nodo-agente) | 1 Pod por build |

> El **documento unificado** (5) ya incluye la comparativa (1), así que si
> prefieres leer un único documento, ve directo al 5.

## Licencia

Los documentos de esta carpeta se distribuyen bajo la licencia
[Creative Commons Atribución 4.0 Internacional (CC BY 4.0)](./LICENSE).
El código del repositorio `jenkins-podman-lab` usa por separado la licencia
MIT ([`LICENSE`](../../LICENSE)).

## Documentos relacionados (en el repo)

- [Architecture Decision Records](../adr/README.md) — decisiones del laboratorio
  `jenkins-podman-lab` (incluida la que documenta el modelo Podman-Host).

---

*Licencia: [CC BY 4.0](./LICENSE). © 2026 jenkins-podman-lab contributors.*
