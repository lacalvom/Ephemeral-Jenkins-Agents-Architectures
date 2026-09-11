# agent-node10-ubi8

Imagen legacy para Jenkins con **Node.js 10.24.1** sobre UBI 8 minimal.

## Por que existe esta imagen

Node.js 10.x fue una LTS popular entre octubre 2018 y abril 2021. Aunque
esta EOL, muchos proyectos legacy siguen usandola:

- **Angular 8 y 9** requieren Node 10.9+ pero funcionan mejor con Node 10.
- **Vue 2** con webpack 4 y node-sass 4.x.
- **NestJS 6 y 7** (anteriores a Node 12).
- **React Native 0.59 a 0.61**.

Node 10 no existe en imagenes UBI 9 (que proveen Node 16/18/20), asi que
esta imagen es necesaria para builds legacy que no pueden migrar todavia.

## Que incluye

- **Node.js 10.24.1** (la ultima de la rama 10.x).
- `tar`, `xz`, `gzip`, `shadow-utils`, `ca-certificates`.
- Usuario no-privilegiado `jenkins_agent` (UID/GID 1001).
- `NODE_HOME=/opt/node`.

## Que NO incluye

- **Angular CLI**: instalar via `npm install -g @angular/cli@9` en runtime.
- **yarn / pnpm / nvm**: usar el `npm` que viene con Node 10 (npm 6.14.x).

## Como construirla

```bash
podman build -t agent-node10-ubi8:1.0.0 .
```

Build con version custom:

```bash
podman build --build-arg NODE_VERSION=10.24.1 -t agent-node10-ubi8:1.0.0 .
```

## Verificacion local

```bash
podman run --rm agent-node10-ubi8:1.0.0 bash -c "node --version && npm --version"
```

Salida esperada:

```
v10.24.1
6.14.18
```

## Como subirla a un registro

### Registry local

```bash
podman tag agent-node10-ubi8:1.0.0 localhost:5000/agent-node10-ubi8:1.0.0
podman push localhost:5000/agent-node10-ubi8:1.0.0 --tls-verify=false
```

### GitHub Container Registry

```bash
echo "$GITHUB_TOKEN" | podman login ghcr.io -u mi-usuario --password-stdin
podman tag agent-node10-ubi8:1.0.0 ghcr.io/mi-org/agent-node10-ubi8:1.0.0
podman push ghcr.io/mi-org/agent-node10-ubi8:1.0.0
```

## Como usarla desde Jenkins

```groovy
pipeline {
    agent {
        docker {
            image 'localhost:5000/agent-node10-ubi8:1.0.0'
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
                        # Para Angular 9: npm install -g @angular/cli@9.1.15
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

| Framework | Node 10 OK? | Notas |
|---|---|---|
| Angular 8, 9 | ✅ | ultima con buen soporte |
| Angular 10 | ✅ (parcial) | requiere Node 10.13+ |
| Angular 11+ | ❌ | requiere Node 12+ |
| React 16, 17 | ✅ | funciona |
| Vue 2 (webpack 4) | ✅ | funciona |
| NestJS 6, 7 | ✅ | funciona |

## Seguridad

Node 10 NO recibe parches de seguridad desde abril 2021. Esta imagen es
**solo para builds legacy aislados**. No usar para servidores expuestos.

## Versionado

Tags generados:

- `agent-node10-ubi8:1.0.0` (version explicita).
- `agent-node10-ubi8:1.0` (minor + patch).
- `agent-node10-ubi8:1` (major + minor).
- `agent-node10-ubi8:latest` (no usar en produccion).

Como Node 10 esta EOL, esta imagen se considera **finalizada**.

## Referencias

- Node.js 10 EOL: https://nodejs.org/en/about/previous-releases
- Node.js distribution: https://nodejs.org/dist/
- UBI 8 minimal: https://catalog.redhat.com/software/containers/ubi8/ubi-minimal
- Angular 9 support: https://angular.io/guide/versions
