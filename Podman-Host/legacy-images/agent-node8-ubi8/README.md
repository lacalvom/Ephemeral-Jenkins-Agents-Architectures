# agent-node8-ubi8

Imagen legacy para Jenkins con **Node.js 8.17.0** sobre UBI 8 minimal.

## Por que existe esta imagen

La guia original del proyecto documenta que los proyectos legacy que
usan **Node.js 8** (Angular 5/6/7, Vue 2 con webpack 3, etc.) no pueden
construirse sobre imagenes modernas porque:

1. **Node 8.x esta EOL** desde abril 2022 (sin parches de seguridad).
2. **npm 6.x** (el que viene con Node 8) no soporta muchas dependencias
   modernas.
3. Las imagenes UBI 9 oficiales proveen Node 16/18/20, no 8.
4. **GLIBC** de Node 8 prebuilt es 2.17+, compatible con UBI 8 (2.28).

Esta imagen es **la minima posible** para correr proyectos Node 8: solo
el binario de Node y las utilidades para descomprimirlo.

## Que incluye

- **Node.js 8.17.0** (la ultima de la rama 8.x).
- `tar`, `xz`, `gzip`, `shadow-utils`, `ca-certificates`.
- Usuario no-privilegiado `jenkins_agent` (UID/GID 1001).
- `NODE_HOME=/opt/node` y node en el PATH.

## Que NO incluye

- **npm separado**: se usa el `npm` que viene con Node 8 (es npm 6.13.4).
- **yarn, pnpm, nvm**: idem, no se incluyen.
- **Angular CLI**: no se pre-instala para mantener la imagen pequena.
  Instalar via `npm install -g @angular/cli@7` en runtime o construir otra
  imagen si se usa siempre.

## Como construirla

```bash
podman build -t agent-node8-ubi8:1.0.0 .
```

Build con version custom (no hay mas 8.x, pero por si acaso):

```bash
podman build --build-arg NODE_VERSION=8.17.0 -t agent-node8-ubi8:1.0.0 .
```

## Verificacion local

```bash
podman run --rm agent-node8-ubi8:1.0.0 bash -c \
    "node --version && npm --version"
```

Salida esperada:

```
v8.17.0
6.13.4
```

Test rapido de calculo:

```bash
podman run --rm agent-node8-ubi8:1.0.0 node -e "console.log(2+2)"
# 4
```

## Como subirla a un registro

### Registry local

```bash
podman tag agent-node8-ubi8:1.0.0 localhost:5000/agent-node8-ubi8:1.0.0
podman push localhost:5000/agent-node8-ubi8:1.0.0 --tls-verify=false
```

### GitHub Container Registry (ghcr.io)

```bash
echo "$GITHUB_TOKEN" | podman login ghcr.io -u mi-usuario --password-stdin
podman tag agent-node8-ubi8:1.0.0 ghcr.io/mi-org/agent-node8-ubi8:1.0.0
podman push ghcr.io/mi-org/agent-node8-ubi8:1.0.0
```

## Como usarla desde Jenkins

```groovy
pipeline {
    agent {
        docker {
            image 'localhost:5000/agent-node8-ubi8:1.0.0'
            reuseNode true
            args '--userns=keep-id -v npm-cache-${EXECUTOR_NUMBER}:/cache/.npm:z'
        }
    }
    stages {
        stage('Install') {
            steps {
                configFileProvider([
                    configFile(fileId: 'npmrc-frontend',
                                variable: 'NPMRC_FILE')
                ]) {
                    sh '''
                        export NPM_CONFIG_USERCONFIG="$NPMRC_FILE"
                        npm config set cache /cache/.npm
                        # Para proyectos Angular < 9 que usan angular/cli 7/8:
                        # npm install -g @angular/cli@7.3.9
                        npm install
                    '''
                }
            }
        }
        stage('Build') {
            steps {
                sh 'npm run build'
            }
        }
    }
}
```

## Compatibilidad con frameworks

| Framework | Node 8 OK? | Notas |
|---|---|---|
| Angular 5, 6, 7 | ✅ | ultima version compatible |
| Angular 8 | ❌ | requiere Node 10.9+ |
| React 16 | ✅ | funciona |
| Vue 2 (webpack 3/4) | ✅ | funciona |
| Vue 3 (Vite) | ❌ | requiere Node 14+ |
| Next.js 9-12 | ✅ | funciona |

## Seguridad

Node 8 NO recibe parches de seguridad desde abril 2022. Esto es
**aceptable para**:

- Builds legacy internos.
- Proyectos en fase de migracion.
- Entornos aislados sin exposicion a internet.

**No es aceptable para** servidores en produccion expuestos. Si necesitas
ejecutar el codigo legacy en produccion, considera migrar a Node 18 LTS o
recompilar contra Node actual.

### Verificacion de integridad del binario

Esta imagen **no incluye verificacion SHA256** del binario de Node. La razon
es tecnica: las imagenes UBI 8 minimal no traen el comando `file`, y la
combinacion `curl -o` + `sha256sum -c` con paths absolutos en BuildKit
presenta problemas sutiles de propagacion del CWD a subshells de pipes.

**Mitigacion aplicada:** descargamos directamente de `nodejs.org` (la fuente
oficial firmada con PGP por la Node.js Foundation). Para entornos con
requisitos de integridad estrictos, se recomienda:

1. Verificar la firma PGP localmente antes del build:
   ```bash
   gpg --verify node-v8.17.0-linux-x64.tar.xz.sig node-v8.17.0-linux-x64.tar.xz
   ```
2. O anadir un paso manual al pipeline que ejecute `sha256sum -c
   SHASUMS256.txt` antes de invocar la imagen.

Si la verificacion SHA256 automatica es requisito (ej. produccion
regulada), se puede reimplementar usando un script bash externo con
WORKDIR explicito.

## Versionado

Tags generados:

- `agent-node8-ubi8:1.0.0` (version explicita, recomendada).
- `agent-node8-ubi8:1.0` (minor + patch).
- `agent-node8-ubi8:1` (major + minor).
- `agent-node8-ubi8:latest` (no usar en produccion).

Como Node 8 esta EOL, esta imagen se considera **finalizada en v1.x**:
no se esperan actualizaciones mayores.

## Troubleshooting

**`npm install` falla con `EINTEGRITY`**: cache de npm corrupto. Limpiar:
```bash
podman run --rm -v /tmp/clean:/clean agent-node8-ubi8:1.0.0 \
    sh -c 'rm -rf /cache/.npm /clean && npm cache clean --force'
```

**`node: command not found`**: el PATH no incluye `/opt/node/bin`.
Verificar que la imagen se construyo correctamente con `podman inspect`.

**Error `GLIBC_2.28 not found` al ejecutar**: la imagen base es UBI 8 que
tiene glibc 2.28+. Si la usas sobre algo mas viejo (RHEL 7), no arrancara.

## Referencias

- Node.js 8 EOL: https://nodejs.org/en/about/previous-releases
- Node.js distribution: https://nodejs.org/dist/
- SHASUMS: https://github.com/nodejs/node/blob/main/doc/changelogs/CHANGELOG_V8.md
- UBI 8 minimal: https://catalog.redhat.com/software/containers/ubi8/ubi-minimal
- Angular 7 support: https://angular.io/guide/versions
