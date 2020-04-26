import com.typesafe.sbt.SbtNativePackager.autoImport.NativePackagerHelper._
import sbtrelease.ReleaseStateTransformations._

name := "kerberos-operator"
scalaVersion := "2.13.2"
ThisBuild / organization := "io.github.novakov-alexey"
ThisBuild / turbo := true
Global / onChangedBuildSource := ReloadOnSourceChanges

resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.ivy2/local"

lazy val root = (project in file("."))
  .settings(
    addCompilerPlugin(betterMonadicFor),
    libraryDependencies ++= Seq(
      freya,
      freyaCirce,
      circeCore,
      circeExtra,
      codecs,
      osClient,
      scalaLogging,
      logbackClassic,
      pureConfig,
      scalaTest % Test,
      scalaCheck % Test,
      scalaTestCheck % Test,
      osServerMock % Test,
      jacksonJsonSchema % Test,
      jacksonScala % Test
    ),
    dockerBaseImage := "openjdk:8-jre-alpine",
    dockerRepository := Some("alexeyn"),
    javaOptions in Universal ++= Seq("-Dlogback.configurationFile=/opt/conf/logback.xml"),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoOptions += BuildInfoOption.BuildTime
  )
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(AshScriptPlugin)

mappings in Universal ++= directory("src/main/resources")

Revolver.enableDebugging(port = 5050, suspend = true)
envVars in reStart := Map("NAMESPACE" -> "test")

releaseProcess :=
  Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    setReleaseVersion,
    releaseStepCommandAndRemaining("docker:publish"),
    commitReleaseVersion,
    tagRelease,
    inquireVersions,
    setNextVersion,
    commitNextVersion,
    pushChanges
  )