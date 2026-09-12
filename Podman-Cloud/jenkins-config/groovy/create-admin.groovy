// =====================================================================
// init.groovy.d/01-create-admin.groovy — Crea el usuario admin local
// =====================================================================
// Jenkins ejecuta TODOS los scripts .groovy en init.groovy.d/ al
// arrancar. Creamos el admin local con JCasC-como-codigo (sin JCasC).
//
// Username y password se pasan via variables de entorno JENKINS_ADMIN_USER
// y JENKINS_ADMIN_PASSWORD, configuradas por Ansible.
// =====================================================================

import jenkins.model.*
import hudson.security.*
import jenkins.install.InstallState

def jenkins = Jenkins.getInstanceOrNull()
if (jenkins == null) {
    return
}

def adminUser = System.getenv("JENKINS_ADMIN_USER") ?: "admin"
def adminPass = System.getenv("JENKINS_ADMIN_PASSWORD")
if (adminPass == null || adminPass.isEmpty()) {
    println "ERROR: JENKINS_ADMIN_PASSWORD no esta definida; no se crea el usuario admin."
    return
}

def hudsonRealm = new HudsonPrivateSecurityRealm(false)
hudsonRealm.createAccount(adminUser, adminPass)
jenkins.setSecurityRealm(hudsonRealm)

def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
strategy.setAllowAnonymousRead(false)
jenkins.setAuthorizationStrategy(strategy)

jenkins.save()
println "ADMIN_USER_CREATED: ${adminUser}"

// Si el setup wizard esta en marcha, marcarlo como completo
if (jenkins.getInstallState() == InstallState.INITIAL_SETUP_COMPLETED) {
    println "SETUP_ALREADY_COMPLETED"
} else {
    jenkins.getSetupWizard().completeSetup()
    println "SETUP_COMPLETED"
}
