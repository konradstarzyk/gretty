/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import java.util.regex.Pattern
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.slf4j.Logger
import org.slf4j.LoggerFactory

final class ProjectUtils {

  static class RealmInfo {
    String realm
    String realmConfigFile
  }

  private static final Logger log = LoggerFactory.getLogger(ProjectUtils)

  private static Set collectOverlayJars(Project project) {
    Set overlayJars = new HashSet()
    def addOverlayJars // separate declaration from init to enable recursion
    addOverlayJars = { Project proj ->
      if(proj.extensions.findByName('gretty'))
        for(def overlay in proj.gretty.overlays) {
          overlay = proj.project(overlay)
          File archivePath = overlay.tasks.findByName('jar')?.archivePath
          if(archivePath)
            overlayJars.add(archivePath)
          addOverlayJars(overlay) // recursion
        }
    }
    addOverlayJars(project)
    return overlayJars
  }

  static File findFileInOutput(Project project, Pattern filePattern, boolean searchOverlays = true) {
    def findInDir
    findInDir = { File dir ->
      dir.listFiles().findResult {
        if(it.isFile())
          it.path =~ filePattern ? it : null
        else
          findInDir(it)
      }
    }
    def findIt
    findIt = { Project proj ->
      File result = proj.sourceSets.main.output.files.findResult(findInDir)
      if(result) {
        log.debug 'findFileInOutput filePattern: {}, result: {}', filePattern, result
        return result
      }
      if(searchOverlays && proj.extensions.findByName('gretty'))
        for(String overlay in proj.gretty.overlays.reverse()) {
          result = findIt(proj.project(overlay))
          if(result) {
            log.debug 'findFileInOutput filePattern: {}, result: {}', filePattern, result
            return result
          }
        }
    }
    findIt(project)
  }

  static String getContextPath(Project project) {
    String contextPath = project.gretty.contextPath
    if(!contextPath)
      for(def overlay in project.gretty.overlays.reverse()) {
        overlay = project.project(overlay)
        if(overlay.extensions.findByName('gretty')) {
          if(overlay.gretty.contextPath) {
            contextPath = overlay.gretty.contextPath
            break
          }
        } else
          log.warn 'Project {} is not gretty-enabled, could not extract it\'s context path', overlay
      }
    return contextPath
  }

  static Set<URL> getClassPath(Project project, boolean inplace) {
    Set<URL> urls = new LinkedHashSet()
    if(inplace) {
      def addProjectClassPath
      addProjectClassPath = { Project proj ->
        urls.addAll proj.sourceSets.main.output.files.collect { it.toURI().toURL() }
        urls.addAll proj.configurations.runtime.files.collect { it.toURI().toURL() }
        // ATTENTION: order of overlay classpath is important!
        if(proj.extensions.findByName('gretty'))
          for(String overlay in proj.gretty.overlays.reverse())
            addProjectClassPath(proj.project(overlay))
      }
      addProjectClassPath(project)
      for(File overlayJar in collectOverlayJars(project))
        if(urls.remove(overlayJar.toURI().toURL()))
          log.debug '{} is overlay jar, exclude from classpath', overlayJar
      for(File grettyConfigJar in project.configurations.grettyHelperConfig.files)
        if(urls.remove(grettyConfigJar.toURI().toURL()))
          log.debug '{} is gretty-config jar, exclude from classpath', grettyConfigJar
    }
    for(URL url in urls)
      log.debug 'classpath URL: {}', url
    return urls
  }

  static File getFinalWarPath(Project project) {
    project.ext.properties.containsKey('finalWarPath') ? project.ext.finalWarPath : project.tasks.war.archivePath
  }

  static Map getInitParameters(Project project) {
    Map initParams = [:]
    for(def overlay in project.gretty.overlays) {
      overlay = project.project(overlay)
      if(overlay.extensions.findByName('gretty')) {
        for(def e in overlay.gretty.initParameters) {
          def paramValue = e.value
          if(paramValue instanceof Closure)
            paramValue = paramValue()
          initParams[e.key] = paramValue
        }
      } else
        log.warn 'Project {} is not gretty-enabled, could not extract it\'s init parameters', overlay
    }
    for(def e in project.gretty.initParameters) {
      def paramValue = e.value
      if(paramValue instanceof Closure)
        paramValue = paramValue()
      initParams[e.key] = paramValue
    }
    return initParams
  }

  static RealmInfo getRealmInfo(Project project) {
    String realm = project.gretty.realm
    String realmConfigFile = project.gretty.realmConfigFile
    if(realmConfigFile && !new File(realmConfigFile).isAbsolute())
      realmConfigFile = "${project.webAppDir.absolutePath}/${realmConfigFile}"
    if(!realm || !realmConfigFile)
      for(def overlay in project.gretty.overlays.reverse()) {
        overlay = project.project(overlay)
        if(overlay.extensions.findByName('gretty')) {
          if(overlay.gretty.realm && overlay.gretty.realmConfigFile) {
            realm = overlay.gretty.realm
            realmConfigFile = overlay.gretty.realmConfigFile
            if(realmConfigFile && !new File(realmConfigFile).isAbsolute())
              realmConfigFile = "${overlay.webAppDir.absolutePath}/${realmConfigFile}"
            break
          }
        } else
          log.warn 'Project {} is not gretty-enabled, could not extract it\'s realm', overlay
      }
    return new RealmInfo(realm: realm, realmConfigFile: realmConfigFile)
  }

  static String getJettyXml(Project project) {
    String jettyHome = System.getenv('JETTY_HOME')
    if(!jettyHome)
      jettyHome = System.getProperty('jetty.home')
    if(jettyHome != null) {
      File file = new File(jettyHome, 'etc/jetty.xml')
      if(file.exists())
        return file
    }
    return resolveFileProperty(project, 'jettyXmlFile', 'jetty.xml')
  }

  static String getJettyEnvXml(Project project) {
    return resolveFileProperty(project, 'jettyEnvXmlFile', 'jetty-env.xml')
  }

  static void prepareInplaceWebAppFolder(Project project) {
    // ATTENTION: overlay copy order is important!
    for(String overlay in project.gretty.overlays)
      project.copy {
        from project.project(overlay).webAppDir
        into "${project.buildDir}/inplaceWebapp"
      }
    project.copy {
      from project.webAppDir
      into "${project.buildDir}/inplaceWebapp"
    }
  }

  static void prepareExplodedWebAppFolder(Project project) {
    // ATTENTION: overlay copy order is important!
    for(String overlay in project.gretty.overlays) {
      def overlayProject = project.project(overlay)
      project.copy {
        from overlayProject.zipTree(getFinalWarPath(overlayProject))
        into "${project.buildDir}/explodedWebapp"
      }
    }
    project.copy {
      from project.zipTree(project.tasks.war.archivePath)
      into "${project.buildDir}/explodedWebapp"
    }
  }

  private static String resolveFileProperty(Project project, String propertyName, defaultValue = null) {
    def file = project.gretty[propertyName]
    if(file == null)
      file = defaultValue
    if(file != null) {
      if(!(file instanceof File))
        file = new File(file)
      if(file.isAbsolute())
        return file.absolutePath
      File f = new File(project.projectDir, file.path)
      if(f.exists())
        return f.absolutePath
      f = new File(new File(project.webAppDir, 'WEB-INF'), file.path)
      if(f.exists())
        return f.absolutePath
      f = findFileInOutput(project, Pattern.compile(file.path), false)
      if(f != null && f.exists())
        return f.absolutePath
    }
    for(def overlay in project.gretty.overlays.reverse()) {
      overlay = project.project(overlay)
      if(overlay.extensions.findByName('gretty')) {
        File f = resolveFileProperty(overlay, propertyName, defaultValue)
        if(f)
          return file
      } else
        log.warn 'Project {} is not gretty-enabled, could not extract \'{}\'', overlay, propertyName
    }
    if(project.gretty[propertyName] != null && project.gretty[propertyName] != defaultValue)
      log.warn 'Could not find file \'{}\' specified in \'{}\'', file.path, propertyName
    return null
  }
}
