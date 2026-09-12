// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Cloudsdoers
// =====================================================================
// init.groovy.d/00-create-docker-credentials.groovy
// =====================================================================
// Modelo Podman-Cloud. Crea en Jenkins la credencial X.509
// (DockerServerCredentials de docker-commons) que el docker-plugin usa
// para hablar con la API de Podman por mTLS:
//   - client-key.pem   -> clave privada del cliente
//   - client-cert.pem  -> certificado de cliente (CN=jenkins-controller)
//   - ca.pem           -> CA con la que validar el certificado de
//                         servidor de Podman
//
// El material lo deja Ansible en JENKINS_PODMAN_TLS_DIR (por defecto
// /etc/jenkins/podman-tls), con permisos del usuario jenkins. NUNCA se
// versiona en el repositorio.
//
// Si JENKINS_PODMAN_TLS_ENABLED != true, el script no crea nada: la
// Cloud se configura sin credencial (modo claro, solo para depurar).
// Ver ADR-0005.
// =====================================================================

import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.SystemCredentialsProvider
import com.cloudbees.plugins.credentials.domains.Domain
import hudson.util.Secret
import jenkins.model.Jenkins
import org.jenkinsci.plugins.docker.commons.credentials.DockerServerCredentials

def jenkins = Jenkins.getInstanceOrNull()
if (jenkins == null) {
    println "DOCKER_CREDENTIALS_SKIPPED: Jenkins no disponible"
    return
}

def tlsEnabled = System.getenv("JENKINS_PODMAN_TLS_ENABLED") ?: "false"
def credId = System.getenv("JENKINS_PODMAN_CLOUD_TLS_CREDENTIALS") ?: ""
def tlsDir = System.getenv("JENKINS_PODMAN_TLS_DIR") ?: "/etc/jenkins/podman-tls"

if (!(tlsEnabled.toLowerCase() in ["true", "1", "yes"]) || !credId) {
    println "DOCKER_CREDENTIALS_SKIPPED: TLS deshabilitado (JENKINS_PODMAN_TLS_ENABLED=${tlsEnabled})"
    return
}

def caFile = new File(tlsDir, "ca.pem")
def certFile = new File(tlsDir, "client-cert.pem")
def keyFile = new File(tlsDir, "client-key.pem")
if (!caFile.isFile() || !certFile.isFile() || !keyFile.isFile()) {
    println "DOCKER_CREDENTIALS_ERROR: faltan ficheros TLS en ${tlsDir} (ca.pem/client-cert.pem/client-key.pem)"
    return
}

def store = SystemCredentialsProvider.getInstance().getStore()
def existing = store.getCredentials(Domain.global()).find { it.id == credId }

def cred = new DockerServerCredentials(
    CredentialsScope.GLOBAL,
    credId,
    "mTLS para la API de Podman del laboratorio Podman-Cloud",
    Secret.fromString(keyFile.getText("UTF-8")),
    certFile.getText("UTF-8"),
    caFile.getText("UTF-8")
)

if (existing == null) {
    store.addCredentials(Domain.global(), cred)
    println "DOCKER_CREDENTIAL_CREATED: ${credId}"
} else {
    store.updateCredentials(Domain.global(), existing, cred)
    println "DOCKER_CREDENTIAL_UPDATED: ${credId}"
}
jenkins.save()
