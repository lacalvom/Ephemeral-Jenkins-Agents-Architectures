# agent-node12-ubi8

Imagen legacy para Jenkins con **Node.js 12.22.12** sobre UBI 8 minimal.

## Por que existe esta imagen

Node.js 12.x fue una LTS muy popular entre octubre 2019 y abril 2022. Aunque
esta EOL, sigue siendo necesaria para:

- **Angular 10, 11 y 12** (mejor compatibilidad con Node 12).
- **NestJS 7 y 8**.
- **Nuxt 2** (las primeras versiones).
- **Webpack 4** y **Babel 7** en sus versiones mas usadas.
- **node-gyp 7+** (Node 12 introducio n-api estable).

Node 12 no existe en imagenes UBI 9 (que proveen Node 16+), asi que esta
imagen es necesaria para builds legacy.

## Que incluye

- **Node.js 12.22.12** (la ultima de la rama 12.x).
- `tar`, `xz`, `gzip`, `shadow-utils`, `ca-certificates`.
- Usuario no-privilegiado `jenkins_agent` (UID/GID 1001).
- `NODE_HOME=/opt/node`.

## Que NO incluye

- **Angular CLI**: instalar via `npm install -g @angular/cli@12` en runtime.
- **yarn / pnpm / nvm**: usar el `npm` que viene con Node 12 (npm 6.14.x).

## Como construirla

```bash
podman build -t agent-node12-ubi8:1.0.0 .
```

Build con version custom:

```bash
podman build --build-arg NODE_VERSION=12.22.12 -t agent-node12-ubi8:1.0.0 .
```

## Verificacion local

```bash
podman run --rm agent-node12-ubi8:1.0.0 bash -c "node --version && npm --version"
```

Salida esperada:

```
v12.22.12
6.14.18
```

## Como subirla a un registro

### Registry local

```bash
podman tag agent-node12-ubi8:1.0.0 localhost:5000/agent-node12-ubi8:1.0.0
podman push localhost:5000/agent-node12-ubi8:1.0.0 --tls-verify=false
```

### GitHub Container Registry

```bash
echo "$GITHUB_TOKEN" | podman login ghcr.io -u mi-usuario --password-stdin
podman tag agent-node12-ubi8:1.0.0 ghcr.io/mi-org/agent-node12-ubi8:1.0.0
podman push ghcr.io/mi-org/agent-node12-ubi8:1.0.0
```

## Como usarla desde Jenkins

```groovy
pipeline {
    agent {
        docker {
            image 'localhost:5000/agent-node12-ubi8:1.0.0'
            reuseNode true
            args '--userns=keep-id -v npm-cache-${EXECUTOR_NUMBER}:/cache/.npm:z'
        }
    }
    stages {
        stage('Build') {
            steps {
                configFileProvider([
                    configFile(fileId: 'npmrc-frontend',
                                variable: 'NPMRC_FILE')
                ]) {
                    sh '''
                        export NPM_CONFIG_USERCONFIG="$NPMRC_FILE"
                        npm config set cache /cache/.npm
                        # Para Angular 12: npm install -g @angular/cli@12.2.18
                        npm install
                        npm run build
                    '''
                }
            }
        }
    }
}
```

## Compatibilidad con frameworks

| Framework | Node 12 OK? | Notas |
|---|---|---|
| Angular 10, 11, 12 | ✅ | ultima con buen soporte |
| Angular 13+ | ❌ | requiere Node 14+ |
| React 17, 18 | ✅ | funciona |
| Vue 2 (Vue 3 mejor en Node 14+) | ✅ | funciona |
| NestJS 7, 8 | ✅ | funciona |
| Next.js 10-13 | ✅ | funciona |

## Seguridad

Node 12 NO recibe parches de seguridad desde abril 2022. Esta imagen es
**solo para builds legacy aislados**. No usar para servidores expuestos.

## Versionado

Tags generados:

- `agent-node12-ubi8:1.0.0` (version explicita).
- `agent-node12-ubi8:1.0` (minor + patch).
- `agent-node12-ubi8:1` (major + minor).
- `agent-node12-ubi8:latest` (no usar en produccion).

Como Node 12 esta EOL, esta imagen se considera **finalizada**.

## Referencias

- Node.js 12 EOL: https://nodejs.org/en/about/previous-releases
- Node.js distribution: https://nodejs.org/dist/
- UBI 8 minimal: https://catalog.redhat.com/software/containers/ubi8/ubi-minimal
- Angular 12 support: https://angular.io/guide/versions
