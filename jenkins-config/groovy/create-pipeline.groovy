// =====================================================================
// init.groovy.d/03-create-pipeline.groovy — Crea el job reference-pipeline
// =====================================================================
// Crea el job de ejemplo "reference-pipeline" leyendo el script
// desde $JENKINS_HOME/jobs/reference-pipeline.groovy (copiado por
// Ansible en el rol jenkins_controller).
//
// Nota historica: en la primera version del provisionamiento, el
// script se buscaba en casc_configs/jobs/. Esa ruta queda reservada
// para una futura migracion a JCasC declarativo (ver ADR-003).
// =====================================================================

import jenkins.model.*
import org.jenkinsci.plugins.workflow.job.WorkflowJob
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition

def jenkins = Jenkins.getInstanceOrNull()
if (jenkins == null) {
    return
}

def jobName = "reference-pipeline"
def existing = jenkins.getItem(jobName)
if (existing != null) {
    println "JOB_ALREADY_EXISTS: ${jobName}"
    return
}

def jobRoot = jenkins.getRootDir()
def scriptFile = new File(jobRoot, "jobs/reference-pipeline.groovy")
if (!scriptFile.exists()) {
    println "ERROR: Script no encontrado en ${scriptFile.absolutePath}"
    return
}

def job = jenkins.createProject(WorkflowJob.class, jobName)
job.setDescription("""Pipeline de ejemplo inspirado en la guia 'Arquitectura de Agentes Efimeros con Podman y Jenkins'.
Crea stages separados que compilan backend (Java/Maven) y frontend (Node/npm) usando
contenedores efimeros UBI 9 sobre el Podman Host.""")
job.setDefinition(new CpsFlowDefinition(scriptFile.text, true))
job.setConcurrentBuild(false)

println "JOB_CREATED: ${jobName}"

