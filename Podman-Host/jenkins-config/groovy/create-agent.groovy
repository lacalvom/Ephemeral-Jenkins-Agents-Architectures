// =====================================================================
// init.groovy.d/02-create-agent.groovy — Configura el Controller y crea el nodo
// =====================================================================
// Esta version consolida TRES correcciones criticas en un solo script:
//   1. Habilita el puerto JNLP (50000) en el Controller.
//      Por defecto en Jenkins 2.568+ viene deshabilitado (-1), lo que
//      hace que los nodos aparezcan "offline" aunque el agente envie
//      "Connected".
//   2. Marca el setup wizard como completado (PERSISTENTE).
//      Llama a completeSetup() ANTES de save() Y crea el fichero
//      jenkins.install.InstallState con contenido "2.0" (INITIAL_SETUP_COMPLETED).
//      Esto es necesario porque jenkins.save() no garantiza persistir
//      el InstallState en todas las versiones de Jenkins 2.568.
//   3. Crea el nodo permanente podman-host-almalinux9 con launcher
//      JNLP + WebSocket.
//
// Ver docs/adr/0003-init-groovy-vs-jcasc.md y docs/adr/0008-jnlp-port-and-installstate-fix.md
// para el contexto completo.
// =====================================================================

import jenkins.model.*
import hudson.model.*
import hudson.slaves.*
import jenkins.slaves.*

def jenkins = Jenkins.getInstance()
if (jenkins == null) {
    println "Jenkins instance not available, abortando."
    return
}

// ---------------------------------------------------------------------
// (1) Habilitar el puerto JNLP en el Controller
// ---------------------------------------------------------------------
def desiredAgentPort = 50000
if (jenkins.getSlaveAgentPort() != desiredAgentPort) {
    jenkins.setSlaveAgentPort(desiredAgentPort)
    println "JNLP_PORT_ENABLED: ${desiredAgentPort}"
} else {
    println "JNLP_PORT_ALREADY_ENABLED: ${desiredAgentPort}"
}

// ---------------------------------------------------------------------
// (2) Marcar el setup wizard como completado (PERSISTENTE)
// ---------------------------------------------------------------------
// Bug conocido en Jenkins 2.568: completeSetup() cambia el estado en
// memoria pero jenkins.save() no lo persiste siempre. Por eso hacemos
// DOS cosas:
//   (a) Llamar a completeSetup() para que el estado interno sea correcto.
//   (b) Crear/asegurar el fichero jenkins.install.InstallState con
//       contenido "2.0" (= INITIAL_SETUP_COMPLETED). Esto es lo que
//       Jenkins lee al arrancar para decidir si mostrar el wizard.
//
// IMPORTANTE: NO usamos installStateFile.setOwnerOnly() porque ese
// metodo no existe en java.io.File en Java 21. Un task externo del
// playbook (file: chown jenkins:jenkins) ajusta los permisos despues.
//
// El contenido del fichero es un numero de version:
//   2.0 = INITIAL_SETUP_COMPLETED
jenkins.getSetupWizard().completeSetup()
println "COMPLETE_SETUP_CALLED"

def installStateFile = new File(jenkins.getRootDir(), "jenkins.install.InstallState")
if (!installStateFile.exists() || installStateFile.text != "2.0\n") {
    installStateFile.text = "2.0\n"
    println "INSTALL_STATE_FILE_WRITTEN: ${installStateFile.absolutePath}"
} else {
    println "INSTALL_STATE_FILE_ALREADY_CORRECT"
}

// ---------------------------------------------------------------------
// (3) Crear el nodo permanente podman-host-almalinux9
// ---------------------------------------------------------------------
def nodeName = System.getenv("JENKINS_AGENT_NODE_NAME") ?: "podman-host-almalinux9"
def existingNode = jenkins.getNode(nodeName)
if (existingNode != null) {
    println "NODE_ALREADY_EXISTS: ${nodeName}"
} else {
    def launcher = new JNLPLauncher()
    launcher.setWebSocket(true)

    def node = new DumbSlave(
        nodeName,
        System.getenv("JENKINS_AGENT_WORKDIR") ?: "/datos/jenkins/pipelines-workspace",
        launcher
    )
    node.setNumExecutors(2)
    node.setLabelString(System.getenv("JENKINS_AGENT_LABEL") ?: "podman-node")
    node.setMode(Node.Mode.NORMAL)
    node.setRetentionStrategy(new RetentionStrategy.Always())

    jenkins.addNode(node)
    println "NODE_CREATED: ${nodeName}"
}

// ---------------------------------------------------------------------
// Persistir TODO el estado
// ---------------------------------------------------------------------
// El save() graba la mayoria del estado pero no garantiza el
// InstallState.xml. Por eso lo creamos manualmente arriba.
jenkins.save()
println "INIT_GROOVY_02_COMPLETED"
