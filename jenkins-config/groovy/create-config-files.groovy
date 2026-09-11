// =====================================================================
// init.groovy.d/04-create-config-files.groovy — Managed Config Files
// =====================================================================
// El pipeline de referencia (reference-pipeline.groovy) usa el paso
// "configFileProvider([configFile(fileId: '...')])" para inyectar un
// settings.xml de Maven y un .npmrc durante los builds. Esos "Managed
// Files" NORMALMENTE se definen via JCasC (bloque configFileProvider:
// en jenkins.yaml), pero este laboratorio usa el "Plan B sin JCasC"
// (ver docs/adr/0003-init-groovy-vs-jcasc.md) porque JCasC 2.568 tiene
// un bug de orden de carga de plugins.
//
// Sin este script, los IDs 'maven-settings-modern' y 'npmrc-frontend'
// NUNCA se crean, y el pipeline falla en el primer build con
// "No settings.xml file with fileId ... found in configuration".
//
// Aqui los creamos de forma imperativa con el mismo mecanismo que usa
// el plugin Config File Provider (GlobalConfigFiles + CustomConfig),
// usando mirrors REALES (Maven Central / npm registry publico), no el
// placeholder "artifactory.mi-empresa.local" del documento guia
// original (esa URL no existe y rompe cualquier build real).
//
// Idempotente: si el ID ya existe (por ejemplo tras un `systemctl
// restart jenkins` sin recrear la VM), no lo sobreescribe.
// =====================================================================

import jenkins.model.Jenkins
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles
import org.jenkinsci.plugins.configfiles.custom.CustomConfig

def jenkins = Jenkins.getInstanceOrNull()
if (jenkins == null) {
    println "CONFIG_FILES_SKIPPED: Jenkins instance not available"
    return
}

def store = GlobalConfigFiles.get()

// ---------------------------------------------------------------------
// maven-settings-modern: settings.xml minimo que apunta a Maven
// Central. El repo local (-Dmaven.repo.local) lo fija el propio
// pipeline por stage, via el volumen de cache maven-cache-<executor>.
// ---------------------------------------------------------------------
def mavenSettingsId = "maven-settings-modern"
def mavenSettingsContent = '''<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0
                               http://maven.apache.org/xsd/settings-1.0.0.xsd">
  <!--
    En un despliegue real, sustituye este mirror por el repositorio
    interno de tu organizacion (Artifactory/Nexus). Para el laboratorio
    usamos Maven Central directamente para que los builds funcionen
    sin infraestructura adicional.
  -->
  <mirrors>
    <mirror>
      <id>maven-central</id>
      <name>Maven Central (mirror explicito para el laboratorio)</name>
      <url>https://repo.maven.apache.org/maven2/</url>
      <mirrorOf>*</mirrorOf>
    </mirror>
  </mirrors>
</settings>
'''

if (store.getById(mavenSettingsId) == null) {
    def mavenConfig = new CustomConfig(
        mavenSettingsId,
        mavenSettingsId,
        "settings.xml para builds Maven del pipeline de referencia (mirror: Maven Central)",
        mavenSettingsContent,
        []
    )
    store.save(mavenConfig)
    println "CONFIG_FILE_CREATED: ${mavenSettingsId}"
} else {
    println "CONFIG_FILE_ALREADY_EXISTS: ${mavenSettingsId}"
}

// ---------------------------------------------------------------------
// npmrc-frontend: .npmrc minimo que apunta al registry publico de npm.
// La cache (-cache) la fija el propio pipeline por stage, via el
// volumen npm-cache-<executor>.
// ---------------------------------------------------------------------
def npmrcId = "npmrc-frontend"
def npmrcContent = '''registry=https://registry.npmjs.org/
strict-ssl=true
'''

if (store.getById(npmrcId) == null) {
    def npmConfig = new CustomConfig(
        npmrcId,
        npmrcId,
        ".npmrc para builds npm/Angular del pipeline de referencia (registry: npmjs.org)",
        npmrcContent,
        []
    )
    store.save(npmConfig)
    println "CONFIG_FILE_CREATED: ${npmrcId}"
} else {
    println "CONFIG_FILE_ALREADY_EXISTS: ${npmrcId}"
}

println "INIT_GROOVY_04_COMPLETED"
