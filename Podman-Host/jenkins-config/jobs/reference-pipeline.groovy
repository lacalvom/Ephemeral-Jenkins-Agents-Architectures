// =====================================================================
// reference-pipeline.groovy — Pipeline "Maestro de Referencia" del lab.
// =====================================================================
// Adaptacion del pipeline de la guia "Arquitectura de Agentes Efimeros
// con Podman y Jenkins" (seccion "Pipeline Maestro de Referencia").
//
// El codigo fuente que compila (backend/, frontend/) NO viene de un
// SCM: Ansible lo copia directamente al workspace del job en cada
// provision del laboratorio (ver ansible/roles/podman_host/tasks/main.yml
// y jenkins-config/samples/reference-app/). Es una app Java 17/Spring
// Boot 3 (backend) + Angular 20 (frontend), minimal pero funcional,
// pensada solo para validar el pipeline.
//
// Stages:
//   1. Construccion Backend  (Java 17/Maven sobre UBI 9)
//   2. Construccion Frontend (Node 20/npm  sobre UBI 9)
//   3. Empaquetado Backend   (Podman build sobre UBI 9)
//   4. Empaquetado Frontend  (Podman build sobre UBI 9)
//
// Tras un build exitoso, las imagenes quedan disponibles en el
// almacen local de Podman como "reference-backend:latest" y
// "reference-frontend:latest" (ademas del tag con numero de build),
// listas para probarse manualmente con:
//   podman compose -f podman-compose.yml up -d
// (ver jenkins-config/samples/reference-app/README.md)
//
// Notas:
//   - label 'podman-node' enrutara al podman-host-almalinux9.
//   - reuseNode true hace que el workspace del host se monte en el contenedor.
//   - --userns=keep-id es obligatorio en Podman rootless para evitar
//     problemas de permisos (UID 1100 del usuario jenkins).
//   - :z/:Z en los -v de cache (maven-cache-N, npm-cache-N) es correcto:
//     son volumenes nuevos, propiedad exclusiva del contenedor.
//   - El socket de Podman (/run/user/1100/podman/podman.sock) NO usa :z:
//     es "contenido de sistema" ya gestionado por systemd (podman.socket),
//     relabelarlo rompe el label que la politica SELinux espera para
//     permitir la conexion. Se usa --security-opt label=disable en su
//     lugar. Ver ADR-011.
// =====================================================================


pipeline {
    agent {
        label 'podman-node'
    }

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    environment {
        EXECUTOR_NUMBER_TAG = "${env.EXECUTOR_NUMBER}"
    }

    stages {

        // -----------------------------------------------------------------
        // FASE 1: COMPILACION BACKEND (Java/Maven)
        // -----------------------------------------------------------------
        stage('Construccion Backend (Java/Maven)') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/openjdk-17:latest'
                    reuseNode true
                    args "--userns=keep-id -v maven-cache-${env.EXECUTOR_NUMBER}:/cache/.m2:z"
                }
            }
            steps {
                configFileProvider([configFile(fileId: 'maven-settings-modern',
                                                variable: 'MAVEN_SETTINGS')]) {
                    sh '''
                        set -euo pipefail
                        mkdir -p /cache/.m2
                        echo "Usando settings: $MAVEN_SETTINGS"
                        if [ -f backend/pom.xml ]; then
                            cd backend
                            mvn -s "$MAVEN_SETTINGS" \
                                -Dmaven.repo.local=/cache/.m2/repository \
                                -B -ntp \
                                clean package
                        else
                            echo "AVISO: backend/pom.xml no encontrado, saltando build"
                        fi
                    '''
                }
            }
        }

        // -----------------------------------------------------------------
        // FASE 2: COMPILACION FRONTEND (Node/npm)
        // -----------------------------------------------------------------
        stage('Construccion Frontend (Node/npm)') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/nodejs-20:latest'
                    reuseNode true
                    args "--userns=keep-id -v npm-cache-${env.EXECUTOR_NUMBER}:/cache/.npm:z"
                }
            }
            steps {
                configFileProvider([configFile(fileId: 'npmrc-frontend',
                                                variable: 'NPMRC_FILE')]) {
                    sh '''
                        set -euo pipefail
                        mkdir -p /cache/.npm
                        export NPM_CONFIG_USERCONFIG="$NPMRC_FILE"
                        npm config set cache /cache/.npm
                        if [ -f frontend/package.json ]; then
                            cd frontend
                            npm install
                            npm run build
                        else
                            echo "AVISO: frontend/package.json no encontrado, saltando build"
                        fi
                    '''
                }
            }
        }

        // -----------------------------------------------------------------
        // FASE 3: EMPAQUETADO IMAGEN BACKEND (Podman sobre UBI 9)
        // -----------------------------------------------------------------
        stage('Empaquetar Imagen Backend') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/podman:latest'
                    reuseNode true
                    // NO usar ":z"/":Z" en el socket de Podman: son para
                    // "compartir contenido normal", y relabelan el fichero
                    // hacia un contexto SELinux que la politica NO permite
                    // conectar via unix_stream_socket (el socket real, creado
                    // por el servicio podman.socket, ya tiene el label
                    // correcto -- relabelarlo lo rompe). "label=disable" es
                    // la solucion oficial documentada por Podman para montar
                    // "contenido de sistema" como este. Ver ADR-011.
                    args "--userns=keep-id --security-opt label=disable -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock"
                }
            }
            steps {
                sh '''
                    set -euo pipefail
                    export CONTAINER_HOST=unix:///run/podman/podman.sock
                    if [ -f backend/Dockerfile ]; then
                        podman build --format docker \
                                     -t artifactory.mi-empresa.local/backend-app:${BUILD_NUMBER} \
                                     -f backend/Dockerfile .
                        # Tag adicional estable (sin numero de build) para que
                        # podman-compose.yml (pruebas manuales) siempre encuentre
                        # la ultima imagen generada sin tener que editarlo.
                        podman tag artifactory.mi-empresa.local/backend-app:${BUILD_NUMBER} \
                                   reference-backend:latest
                    else
                        echo "AVISO: backend/Dockerfile no encontrado, saltando build"
                    fi
                '''
            }
        }

        // -----------------------------------------------------------------
        // FASE 4: EMPAQUETADO IMAGEN FRONTEND (Podman sobre UBI 9)
        // -----------------------------------------------------------------
        stage('Empaquetar Imagen Frontend') {
            agent {
                docker {
                    image 'registry.access.redhat.com/ubi9/podman:latest'
                    reuseNode true
                    // Ver comentario equivalente en el stage del backend (ADR-011).
                    args "--userns=keep-id --security-opt label=disable -v /run/user/1100/podman/podman.sock:/run/podman/podman.sock"
                }
            }
            steps {
                sh '''
                    set -euo pipefail
                    export CONTAINER_HOST=unix:///run/podman/podman.sock
                    if [ -f frontend/Dockerfile ]; then
                        podman build --format docker \
                                     -t artifactory.mi-empresa.local/frontend-app:${BUILD_NUMBER} \
                                     -f frontend/Dockerfile .
                        # Ver comentario equivalente en el stage del backend.
                        podman tag artifactory.mi-empresa.local/frontend-app:${BUILD_NUMBER} \
                                   reference-frontend:latest
                    else
                        echo "AVISO: frontend/Dockerfile no encontrado, saltando build"
                    fi
                '''
            }
        }
    }

    post {
        success {
            echo "Pipeline completado correctamente en build #${env.BUILD_NUMBER}"
        }
        failure {
            echo "Pipeline FALLO en build #${env.BUILD_NUMBER}. Revisa los logs."
        }
        always {
            // Limpieza explicita (workaround para ws-cleanup plugin opcional)
            sh 'podman system prune -f || true'
        }
    }
}
