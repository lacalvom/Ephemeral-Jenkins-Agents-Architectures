// =====================================================================
// init.groovy — Script ejecutado por configuration-as-code-groovy
//                al arrancar Jenkins tras la primera carga de JCasC.
// =====================================================================
// Se invoca desde jenkins.yaml via configuration-as-code-groovy con:
//   configuration-as-code-groovy:
//     scripts:
//       - "groovy/init.groovy"  (path relativo a $JENKINS_HOME/casc_configs)
//
// Crea el job de ejemplo "reference-pipeline" si no existe.
// =====================================================================

import jenkins.model.*
import hudson.model.*
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition

def jenkins = Jenkins.getInstanceOrNull()
if (jenkins == null) {
    println "Jenkins instance not available yet, skipping job creation."
    return
}

def jobName = "reference-pipeline"
def existing = jenkins.getItem(jobName)
if (existing != null) {
    println "Job '${jobName}' ya existe, no se recrea."
    return
}

// El script del pipeline se lee desde casc_configs/jobs/reference-pipeline.groovy
def scriptFile = new File(jenkins.getRootDir(), "casc_configs/jobs/reference-pipeline.groovy")
if (!scriptFile.exists()) {
    println "ADVERTENCIA: ${scriptFile} no existe. Job '${jobName}' no creado."
    return
}

def scriptText = scriptFile.text
def job = jenkins.createProject(WorkflowJob.class, jobName)
job.setDescription("""Pipeline de ejemplo inspirado en la guia 'Arquitectura de Agentes Efimeros con Podman y Jenkins'.
Crea stages separados que compilan backend (Java/Maven) y frontend (Node/npm) usando
contenedores efimeros UBI 9 sobre el Podman Host.""")
job.setDefinition(new CpsFlowDefinition(scriptText, true))
job.setConcurrentBuild(false)

println "Job '${jobName}' creado correctamente desde configuration-as-code-groovy."
