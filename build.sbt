import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

addCommandAlias(
  "gitSnapshots",
  ";set version in ThisBuild := git.gitDescribedVersion.value.get + \"-SNAPSHOT\""
)

val apache2 = "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.html")
val gh = GitHubSettings(
  org = "kailuowang",
  proj = "mau",
  publishOrg = "com.kailuowang",
  license = apache2
)

val mainDev =
  Developer(
    "Kai(luo) Wang",
    "@kailuowang",
    "me@kwang.us",
    new java.net.URL("http://github.com/kailuowang")
  )

lazy val libs = org.typelevel.libraries.add("cats-effect", "3.3.7")

lazy val mau = project
  .in(file("."))
  .settings(rootSettings, noPublishSettings)
  .aggregate(core.jvm, core.js)

lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("core"))
  .settings(
    name := "mau",
    rootSettings,
    libs.dependencies("cats-effect"),
    libs.testDependencies("scalatest"),
    Test / scalacOptions --= Seq("-Xlint:-unused,_", "-Ywarn-unused:imports")
  )
  .jsSettings(
    scalaJSStage in Global := FastOptStage
  )

lazy val buildSettings = sharedBuildSettings(gh, libs)

lazy val commonSettings = addCompilerPlugins(libs, "kind-projector") ++ sharedCommonSettings ++ Seq(
  organization := "com.kailuowang",
  Test / parallelExecution := false,
  scalaVersion := libs.vers("scalac_2.13"),
  crossScalaVersions := Seq(
    scalaVersion.value,
    libs.vers("scalac_2.12")
  ),
  developers := List(mainDev)
)

lazy val publishSettings = sharedPublishSettings(gh) ++ credentialSettings ++ sharedReleaseProcess

lazy val scoverageSettings = sharedScoverageSettings(60)

lazy val rootSettings = buildSettings ++ commonSettings ++ publishSettings ++ scoverageSettings
