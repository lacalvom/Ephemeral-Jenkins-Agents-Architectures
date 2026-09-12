# ADR-006: Usar Plugin Installation Manager Tool con versiones fijadas del update center estable

- **Estado:** Aceptado
- **Fecha:** 2026-09-09
- **Decisor:** DevOps Team

## Contexto

Jenkins provee multiples formas de instalar plugins:

1. **UI web:** Click en "Install" desde Manage Jenkins > Manage Plugins. No reproducible.
2. **Descargar `.jpi` manualmente** y copiarlos a `/var/lib/jenkins/plugins/`. Funciona pero requiere conocer URLs exactas.
3. **Plugin Installation Manager Tool** (`jenkins-plugin-manager`): herramienta CLI oficial de Jenkins que descarga plugins desde un `plugins.yaml` declarativo.
4. **Preinstalar plugins via Docker:** mecanismo especifico para imagenes Docker, no aplica a VMs.

La guia original del proyecto mencionaba JCasC para la configuracion pero no entraba en el detalle de como instalar plugins. En este laboratorio se necesitaba un mecanismo reproducible que:

- Funcione en el primer arranque de Jenkins (sin tener que abrir la UI).
- Permita fijar versiones exactas de plugins (para reproducibilidad).
- Sea mantenible (declarativo, no imperativo).

## Decision

Adoptamos **Plugin Installation Manager Tool** con un fichero `plugins.yaml` declarativo que fija versiones exactas.

Las versiones se obtienen del **update center estable de Jenkins LTS 2.568.2**:
`https://updates.jenkins.io/dynamic-stable-2.568.2/update-center.json`

El fichero `plugins.yaml` sigue el formato oficial:

```yaml
plugins:
  - artifactId: "configuration-as-code"
    source:
      version: "2121.v86fe99d4b_b_a_b_"
  - artifactId: "docker-workflow"
    source:
      version: "653.v2f2c08eff0ec"
  ...
```

Y se invoca via:

```bash
java -jar /usr/local/bin/jenkins-plugin-manager.jar \
  --plugin-file /etc/jenkins-plugins.yaml \
  --plugin-download-directory /var/lib/jenkins/plugins \
  --jenkins-version 2.568.3
```

## Consecuencias

### Positivas

- **Reproducible.** El mismo `plugins.yaml` produce siempre el mismo conjunto de plugins.
- **Resuelve dependencias transitivas automaticamente.** Si `docker-workflow` requiere `docker-plugin`, el manager lo descarga sin que tengamos que declararlo.
- **Permite fijar versiones exactas**, evitando que un cambio upstream rompa el laboratorio.
- **Es declarativo.** Cualquier cambio en plugins es un cambio en `plugins.yaml` que se revisa en git.

### Negativas

- **Hay que actualizar las versiones manualmente** cuando hay nuevas releases. Esto se hace ejecutando:
  ```bash
  curl -sL https://updates.jenkins.io/dynamic-stable-2.568.2/update-center.json \
    | python3 -c "import json,sys,re; r=sys.stdin.read(); m=re.match(r'updateCenter.post\\((.+)\\)',r,re.DOTALL); d=json.loads(m.group(1)); ..."
  ```
- **El formato YAML del Plugin Manager es distinto** del formato habitual (`id` / `version` no funciona; es `artifactId` / `source.version`). Documentado en el README.

### Neutras / trade-offs

- Si el Plugin Manager descarga una version "snapshot" experimental (que pasa con `latest: true`), JCasC falla al cargar configuradores. Por eso **siempre fijamos versiones exactas** del update center estable.

## Leccion aprendida: `latest: true` no es lo que parece

En las primeras versiones del `plugins.yaml` use `latest: true` esperando que el Plugin Manager descargara las versiones mas recientes **estables**. En realidad, lo que hizo fue descargar **versiones incrementales experimentales** (e.g. `configuration-as-code 2121.v86fe99d4b_b_a_b_` cuando la version estable real era `1850.va_a_8c31d_c_d_38`).

Estas versiones experimentales existen en el update center de Jenkins como "snapshots" que aun no han sido promovidas a "release". El Plugin Manager las descarga si se le pide `latest: true` y `--jenkins-version`, porque interpreta que esas son las "mas recientes" compatibles con esa version de Jenkins.

El resultado fue que los plugins descargados tenian una version mucho mas nueva de lo que parecia, **eran incompatibles con Jenkins 2.568.3 LTS** y JCasC no encontraba los configuradores.

La solucion fue fijar versiones exactas obtenidas del **update center estable** (`dynamic-stable-2.568.2/update-center.json`), no del update center general (`/current/`).

## Como actualizar las versiones en el futuro

```bash
curl -sL https://updates.jenkins.io/dynamic-stable-2.568.2/update-center.json > /tmp/uc.json
python3 <<'EOF'
import json, re
raw = open('/tmp/uc.json').read()
m = re.match(r'updateCenter.post\((.+)\)', raw, re.DOTALL)
d = json.loads(m.group(1))
plugins = d['plugins']
ids = ['configuration-as-code', 'configuration-as-code-groovy',
       'docker-workflow', 'docker-plugin', 'config-file-provider',
       'timestamper', 'ssh-slaves', 'instance-identity',
       'credentials', 'plain-credentials', 'ssh-credentials',
       'workflow-aggregator', 'pipeline-stage-view',
       'pipeline-groovy-lib', 'git', 'github',
       'build-timeout', 'dark-theme', 'antisamy-markup-formatter']
for pid in ids:
    p = plugins.get(pid, {})
    if p:
        print(f'  - artifactId: "{pid}"')
        print(f'    source:')
        print(f'      version: "{p[\"version\"]}"')
EOF
```

## Alternativas consideradas

- **Descargar `.jpi` manualmente y versionarlos en git:** Descartada por inflar el repositorio (cada plugin pesa MBs).
- **Usar `latest: true` del Plugin Manager:** Descartada por el problema descrito arriba (snapshots experimentales).
- **Instalar via UI y exportar `plugins.txt`:** Descartada por requerir intervencion manual en el primer arranque.
- **Usar el docker-image de Jenkins pre-instalado:** Descartada porque el laboratorio usa VMs nativas, no contenedores.

## Referencias

- https://github.com/jenkinsci/plugin-installation-manager-tool
- https://updates.jenkins.io/dynamic-stable-2.568.2/
- https://plugins.jenkins.io/
