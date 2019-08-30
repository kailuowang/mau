import com.typesafe.sbt.SbtGit.git
import microsites._

import _root_.sbtcrossproject.CrossPlugin.autoImport.CrossType

addCommandAlias("gitSnapshots", ";set version in ThisBuild := git.gitDescribedVersion.value.get + \"-SNAPSHOT\"")

val apache2 = "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")
val gh = GitHubSettings(org = "kailuowang", proj = "mau", publishOrg = "com.kailuowang", license = apache2)

val mainDev =
  Developer("Kai(luo) Wang", "@kailuowang", "me@kwang.us", new java.net.URL("http://github.com/kailuowang"))

val devs = List(Developer)

lazy val libs =  org.typelevel.libraries

lazy val rootSettings = buildSettings ++ commonSettings ++ publishSettings ++ scoverageSettings
lazy val module = mkModuleFactory(gh.proj, mkConfig(rootSettings, commonJvmSettings, commonJsSettings))
lazy val prj = mkPrjFactory(rootSettings)

lazy val mau = project.in(file("."))
  .configure(mkRootConfig(rootSettings,rootJVM))
  .aggregate(rootJVM, rootJS )
  .dependsOn(rootJVM, rootJS)
  .settings(noPublishSettings)

lazy val rootJVM = project
  .configure(mkRootJvmConfig(gh.proj, rootSettings, commonJvmSettings))
  .aggregate(coreJVM, testsJVM, docs)
  .dependsOn(coreJVM, testsJVM)
  .settings(noPublishSettings)

lazy val rootJS = project
  .configure(mkRootJsConfig(gh.proj, rootSettings, commonJsSettings))
  .aggregate(coreJS, testsJS)
  .dependsOn(coreJS, testsJS)
  .settings(noPublishSettings)

lazy val core    = prj(coreM)
lazy val coreJVM = coreM.jvm
lazy val coreJS  = coreM.js
lazy val coreM   = module("core", CrossType.Pure)
  .settings(
    libs.dependencies("cats-effect")
  )

lazy val tests    = prj(testsM)
lazy val testsJVM = testsM.jvm
lazy val testsJS  = testsM.js
lazy val testsM   = module("tests", CrossType.Pure)
  .dependsOn(coreM)
  .settings(
    noPublishSettings,
    libs.testDependencies("scalatest"),
    scalacOptions in Test --= Seq("-Xlint:-unused,_", "-Ywarn-unused:imports")
  )

/** Docs - Generates and publishes the scaladoc API documents and the project web site using sbt-microsite.*/
lazy val docs = project
  .configure(mkDocConfig(gh, rootSettings, Nil, core))
  .enablePlugins(MicrositesPlugin)
  .enablePlugins(ScalaUnidocPlugin)
  .settings(
    crossScalaVersions := Seq(scalaVersion.value),
    scalacOptions in Tut ~= (_.filterNot(Set("-Ywarn-unused:imports"))),
    micrositeSettings(gh, mainDev,  "mau"),
    micrositeDocumentationUrl := "/mau/api/mau/index.html",
    micrositeDocumentationLabelDescription := "API Documentation",
    micrositeGithubOwner := "kailuowang"
  )
lazy val buildSettings = sharedBuildSettings(gh, libs)

lazy val commonSettings = addCompilerPlugins(libs, "kind-projector") ++ sharedCommonSettings ++ scalacAllSettings ++ Seq(
  organization := "com.kailuowang",
  parallelExecution in Test := false,
  crossScalaVersions := Seq(libs.vers("scalac_2.11"), scalaVersion.value, libs.vers("scalac_2.13"))
)

lazy val commonJsSettings = Seq(scalaJSStage in Global := FastOptStage)

lazy val commonJvmSettings = Seq()

lazy val publishSettings = sharedPublishSettings(gh) ++ credentialSettings ++ sharedReleaseProcess

lazy val scoverageSettings = sharedScoverageSettings(60)

