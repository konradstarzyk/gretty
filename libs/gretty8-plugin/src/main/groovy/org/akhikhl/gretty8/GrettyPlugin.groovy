/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty8

import org.gradle.api.Project
import org.akhikhl.gretty.GrettyPluginBase
import org.akhikhl.gretty.ScannerManagerBase

final class GrettyPlugin extends GrettyPluginBase {

  @Override
  ScannerManagerBase createScannerManager() {
    return new ScannerManager()
  }

  @Override
  void injectDependencies(Project project) {
    project.dependencies {
      providedCompile 'javax.servlet:javax.servlet-api:3.0.1'
      grettyHelperConfig 'org.akhikhl.gretty:gretty8-helper:0.0.9'
    }
  }
}
