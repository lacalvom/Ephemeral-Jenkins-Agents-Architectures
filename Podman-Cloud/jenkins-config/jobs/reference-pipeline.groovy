// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Cloudsdoers
// =====================================================================
// reference-pipeline.groovy — Pipeline del laboratorio Podman-Cloud
// =====================================================================
// Modelo "Cloud" (plugin docker-plugin): NO se usa "agent { docker }".
// Cada stage elige un label y el plugin aprovisiona un contenedor-agente
// a partir de la plantilla correspondiente (maven-jdk17 / node20 /
// podman-build) y lo destruye al terminar.
//
// El workspace y las caches los define la PLANTILLA (ver
// create-cloud.groovy), no el pipeline: el workspace esta compartido en
// /datos/jenkins/pipelines-workspace y las caches son named volumes
// montados en /cache/.m2 y /cache/.npm.
//
// El codigo (backend/, frontend/) lo copia Ansible al workspace del job
// (no se usa SCM). Ver jenkins-config/samples/reference-app/.
// =====================================================================

pipeline {
    // Sin agente global: cada stage elige su plantilla por label.
    agent none

    options {
        timestamps()
        buildDiscarder(logRotator(numToKeepStr: '10'))
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    stages {

        // -----------------------------------------------------------------
        // FASE 1: COMPILACION BACKEND (agente maven-jdk17)
        // -----------------------------------------------------------------
        // El agente corre sobre JDK 21 (el de la imagen), pero el backend
        // se compila con JDK 17: Maven lo selecciona via el toolchains.xml
        // que trae la imagen (maven-toolchains-plugin en el pom). Por eso
        // se pasa "-t /opt/toolchains/toolchains.xml".
        // -----------------------------------------------------------------
        stage('Construccion Backend (Maven)') {
            agent { label 'maven-jdk17' }
            steps {
                configFileProvider([configFile(fileId: 'maven-settings-modern',
                                                variable: 'MAVEN_SETTINGS')]) {
                    sh '''
                        set -euo pipefail
                        mkdir -p /cache/.m2
                        if [ -f backend/pom.xml ]; then
                            cd backend
                            echo "JDK del agente (runtime): $(java -version 2>&1 | head -n1)"
                            mvn -s "$MAVEN_SETTINGS" \
                                -t /opt/toolchains/toolchains.xml \
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
        // FASE 2: COMPILACION FRONTEND (agente node20)
        // -----------------------------------------------------------------
        stage('Construccion Frontend (Node)') {
            agent { label 'node20' }
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
        // FASE 3: EMPAQUETADO IMAGEN BACKEND (agente podman-build)
        // -----------------------------------------------------------------
        stage('Empaquetar Imagen Backend') {
            agent { label 'podman-build' }
            steps {
                sh '''
                    set -euo pipefail
                    if [ -f backend/Dockerfile ]; then
                        podman build --format docker \
                                     -t reference-backend:latest \
                                     -f backend/Dockerfile .
                    else
                        echo "AVISO: backend/Dockerfile no encontrado, saltando build"
                    fi
                '''
            }
        }

        // -----------------------------------------------------------------
        // FASE 4: EMPAQUETADO IMAGEN FRONTEND (agente podman-build)
        // -----------------------------------------------------------------
        stage('Empaquetar Imagen Frontend') {
            agent { label 'podman-build' }
            steps {
                sh '''
                    set -euo pipefail
                    if [ -f frontend/Dockerfile ]; then
                        podman build --format docker \
                                     -t reference-frontend:latest \
                                     -f frontend/Dockerfile .
                    else
                        echo "AVISO: frontend/Dockerfile no encontrado, saltando build"
                    fi
                '''
            }
        }

        // -----------------------------------------------------------------
        // FASE 5: CONSUMO DE SECRETS + HERRAMIENTAS KUBERNETES
        // -----------------------------------------------------------------
        // Ejemplo de dos buenas practicas en el propio pipeline:
        //   1) consumir un Podman Secret SIN pasarlo por argumentos: el
        //      motor lo monta como fichero en /run/secrets/<nombre>;
        //   2) inyectar un kubeconfig via Config File Provider (no se
        //      hornea en la imagen) y exportarlo como KUBECONFIG para
        //      kubectl/kubectx/kubens.
        stage('Secrets y Kubernetes') {
            agent { label 'podman-build' }
            steps {
                configFileProvider([configFile(fileId: 'kubeconfig-demo',
                                                variable: 'KUBECONFIG')]) {
                    sh '''
                        set -euo pipefail

                        echo "--- Secrets en el store rootful del podman-host ---"
                        podman secret ls || true

                        echo "--- Consumo de un secret en un contenedor efimero ---"
                        if podman secret inspect api_token_prod_file >/dev/null 2>&1; then
                            podman run --rm --secret api_token_prod_file \
                                docker.io/library/alpine:3.20 \
                                sh -c 'echo "leido desde /run/secrets:"; cat /run/secrets/api_token_prod_file'
                        else
                            echo "AVISO: secret api_token_prod_file no encontrado, saltando demo"
                        fi

                        echo "--- kubeconfig inyectado (kubectl/kubectx/kubens) ---"
                        kubectl config get-contexts || true
                        echo "contexto actual: $(kubectl config current-context 2>/dev/null || echo '(sin cluster real)')"
                        kubectx 2>/dev/null || true
                    '''
                }
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
    }
}
