// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Cloudsdoers
// =====================================================================
// init.groovy.d/create-cloud.groovy — Configura la Cloud de Podman
// =====================================================================
// Modelo Podman-Cloud (plugin docker-plugin). Jenkins ejecuta los
// scripts de init.groovy.d/ al arrancar; aqui se registra una Cloud
// Docker que apunta a la API de Podman del podman-host (TCP + mTLS) y
// se definen las plantillas de agente (una por toolchain).
//
// Sustituye a create-agent.groovy del lab Podman-Host: en el modelo
// Cloud NO hay nodo JNLP permanente; el plugin aprovisiona contenedores
// bajo demanda segun el label.
//
// Parametros que dependen del entorno (IPs) se leen de variables de
// entorno definidas en el drop-in de systemd:
//   JENKINS_PODMAN_CLOUD_API_URI          (tcp://<podman-host>:2376)
//   JENKINS_PODMAN_CLOUD_JENKINS_URL      (http://<controller>:8080/)
//   JENKINS_PODMAN_CLOUD_NAME             (podman-cloud)
//   JENKINS_PODMAN_CLOUD_USER             (usuario del contenedor; 0 = root)
//   JENKINS_PODMAN_CLOUD_TLS_CREDENTIALS  (id de la credencial X.509; vacio = sin TLS)
//
// Los montajes (workspace compartido, caches, socket) y los labels estan
// fijados aqui, alineados con ansible/group_vars/all/vars.yml.
// =====================================================================

import com.nirima.jenkins.plugins.docker.DockerCloud
import com.nirima.jenkins.plugins.docker.DockerImagePullStrategy
import com.nirima.jenkins.plugins.docker.DockerTemplate
import com.nirima.jenkins.plugins.docker.DockerTemplateBase
import hudson.slaves.Cloud
import io.jenkins.docker.client.DockerAPI
import io.jenkins.docker.connector.DockerComputerJNLPConnector
import jenkins.model.Jenkins
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerEndpoint

def jenkins = Jenkins.getInstanceOrNull()
if (jenkins == null) {
    println "PODMAN_CLOUD_SKIPPED: Jenkins no disponible"
    return
}

// Habilitar el puerto JNLP (50000): los contenedores-agente conectan por
// JNLP. Sin esto aparecen "offline" aunque el contenedor arranque.
def jnlpPort = 50000
if (jenkins.getSlaveAgentPort() != jnlpPort) {
    jenkins.setSlaveAgentPort(jnlpPort)
    println "JNLP_PORT_ENABLED: ${jnlpPort}"
} else {
    println "JNLP_PORT_ALREADY_ENABLED: ${jnlpPort}"
}

def apiUri = System.getenv("JENKINS_PODMAN_CLOUD_API_URI") ?: "tcp://127.0.0.1:2376"
def jenkinsUrl = System.getenv("JENKINS_PODMAN_CLOUD_JENKINS_URL") ?: jenkins.getRootUrl()
def cloudName = System.getenv("JENKINS_PODMAN_CLOUD_NAME") ?: "podman-cloud"
def containerUser = System.getenv("JENKINS_PODMAN_CLOUD_USER") ?: "0"
// Credencial X.509 creada por 00-create-docker-credentials.groovy. Si
// esta vacia, el endpoint se configura sin TLS (solo para depurar).
def tlsCredentialsId = System.getenv("JENKINS_PODMAN_CLOUD_TLS_CREDENTIALS") ?: ""

// Workspace compartido y socket de Podman del host (ROOTFUL).
def workspace = "/datos/jenkins/pipelines-workspace"
def podmanSocket = "/run/podman/podman.sock"

// Factory de plantillas: monta el workspace compartido + lo especifico.
def makeTemplate = { String name, String label, String image, String mounts, String envStr, String secOpts ->
    def base = new DockerTemplateBase(image)
    base.tty = true
    base.mountsString = mounts
    if (envStr) {
        base.environmentsString = envStr
    }
    if (secOpts) {
        base.securityOptsString = secOpts
    }
    def connector = new DockerComputerJNLPConnector()
    connector.jenkinsUrl = jenkinsUrl
    if (containerUser) {
        connector.user = containerUser
    }
    def template = new DockerTemplate(base, connector, label, null)
    template.name = name
    template.remoteFs = workspace
    template.pullStrategy = DockerImagePullStrategy.PULL_NEVER
    return template
}

// El campo "mounts" del template (mountsString) NO usa la sintaxis "-v
// host:contenedor": espera pares key=value separados por comas, una linea
// por mount (type=bind|volume,source=...,destination=...). Pasar la
// sintaxis "-v" da "Invalid mount: expected key=value comma separated".
//
// SELinux: el workspace es un bind del host y, con SELinux en enforcing,
// el contenedor no puede escribir en el salvo que se relabele. El plugin
// no puede expresar ":z", por eso las TRES plantillas llevan
// securityOptsString="label=disable".
def templates = [
    makeTemplate(
        "agent-maven-jdk17", "maven-jdk17", "localhost/agent-maven-jdk17:latest",
        "type=bind,source=${workspace},destination=${workspace}\ntype=volume,source=maven-cache,destination=/cache/.m2",
        "MAVEN_OPTS=-Dmaven.repo.local=/cache/.m2/repository", "label=disable"),
    makeTemplate(
        "agent-node20", "node20", "localhost/agent-node20:latest",
        "type=bind,source=${workspace},destination=${workspace}\ntype=volume,source=npm-cache,destination=/cache/.npm",
        "", "label=disable"),
    makeTemplate(
        "agent-podman", "podman-build", "localhost/agent-podman:latest",
        "type=bind,source=${workspace},destination=${workspace}\ntype=bind,source=${podmanSocket},destination=/run/podman/podman.sock",
        "CONTAINER_HOST=unix:///run/podman/podman.sock", "label=disable"),
]

def api = new DockerAPI(new DockerServerEndpoint(apiUri, tlsCredentialsId ?: null))
api.connectTimeout = 5
api.readTimeout = 60

def cloud = new DockerCloud(cloudName, api, templates)
cloud.containerCap = 10

// Idempotente: reemplaza la Cloud existente del mismo nombre.
Cloud oldCloud = jenkins.clouds.getByName(cloudName)
if (oldCloud != null) {
    jenkins.clouds.remove(oldCloud)
}
jenkins.clouds.add(cloud)
jenkins.save()

println "PODMAN_CLOUD_CONFIGURED: ${cloudName} (api=${apiUri}, url=${jenkinsUrl}, plantillas=${templates*.name})"
