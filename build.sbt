import Dependencies.autoImport.{scalaCheck, scalaTestCheck}
import com.typesafe.sbt.SbtNativePackager.autoImport.NativePackagerHelper._
import sbtrelease.ReleaseStateTransformations._

name := "kerberos-operator"
scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"
ThisBuild / turbo := true

resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.ivy2/local"

lazy val root = (project in file("."))
  .settings(
    addCompilerPlugin(betterMonadicFor),
    libraryDependencies ++= Seq(
      freya,
      codecs,
      osClient,
      scalaLogging,
      logbackClassic,
      pureConfig,
      scalaTest % Test,
      scalaCheck % Test,
      scalaTestCheck % Test,
      osServerMock % Test,
      jacksonJsonSchema % Test
    ),
    dockerBaseImage := "openjdk:8-jre-alpine",
    dockerRepository := Some("alexeyn"),
    javaOptions in Universal ++= Seq("-Dlogback.configurationFile=/opt/conf/logback.xml"),
  )
  .enablePlugins(AshScriptPlugin)

mappings in Universal ++= directory("src/main/resources")

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