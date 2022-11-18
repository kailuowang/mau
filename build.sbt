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

lazy val libs = org.typelevel.libraries.add("cats-effect", "3.4.1")

lazy val mau = project
  .in(file("core"))
  .settings(
    name := "mau",
    rootSettings,
    libs.dependencies("cats-effect"),
    libs.testDependencies("scalatest"),
    libs.testDependencies("cats-effect-testing-scalatest"),
    Test / scalacOptions --= Seq("-Xlint:-unused,_", "-Ywarn-unused:imports")
  )

lazy val buildSettings = sharedBuildSettings(gh, libs)

lazy val commonSettings = sharedCommonSettings ++ Seq(
  organization := "com.kailuowang",
  Test / parallelExecution := false,
  scalaVersion := libs.vers("scalac_2.13"),
  crossScalaVersions := Seq(
    scalaVersion.value,
    libs.vers("scalac_2.12"),
    "3.2.0"
  ),
  developers := List(mainDev)
)

lazy val publishSettings = sharedPublishSettings(gh) ++ credentialSettings ++ sharedReleaseProcess

lazy val rootSettings = buildSettings ++ commonSettings ++ publishSettings
