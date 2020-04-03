import NativeImage._
import com.typesafe.sbt.SbtNativePackager.autoImport.NativePackagerHelper._
import sbt.Keys.streams
import sbtrelease.ReleaseStateTransformations._
import sbt.Keys.streams
import sbtrelease.ReleaseStateTransformations._

import scala.sys.process._

import scala.sys.process._

name := "kerberos-operator"
scalaVersion := "2.13.2"
ThisBuild / organization := "io.github.novakov-alexey"
ThisBuild / turbo := true
Global / onChangedBuildSource := ReloadOnSourceChanges

resolvers += "Local Maven Repository".at("file://" + Path.userHome.absolutePath + "/.ivy2/local")

lazy val dockerRepo = Some("alexeyn")

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
          janino,
          pureConfig,
          scalaTest % Test,
          scalaCheck % Test,
          scalaTestCheck % Test,
          osServerMock % Test,
          jacksonJsonSchema % Test,
          jacksonScala % Test
        ),
    dockerBaseImage := "openjdk:8-jre-alpine",
    dockerRepository in Docker := dockerRepo,
    javaOptions in Universal ++= Seq("-Dlogback.configurationFile=/opt/conf/logback.xml"),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoOptions += BuildInfoOption.BuildTime,
    assemblyMergeStrategy in assembly := {
        case PathList("javax", "servlet", _*) => MergeStrategy.last
        case PathList("javax", "activation", _*) => MergeStrategy.last
        case PathList("javax", "xml", _*) => MergeStrategy.last
        case PathList(ps @ _*) if ps.last.endsWith(".html") => MergeStrategy.first
        case "application.conf" => MergeStrategy.concat
        case "META-INF/versions/9/javax/xml/bind/ModuleUtil.class" => MergeStrategy.first
        case "module-info.class" => MergeStrategy.discard
        case "META-INF/jandex.idx" => MergeStrategy.discard
        case x =>
          val oldStrategy = (assemblyMergeStrategy in assembly).value
          oldStrategy(x)
      },
    test in assembly := {},
    dockerRepositoryNative := dockerRepo
  )
  .enablePlugins(BuildInfoPlugin)
  .enablePlugins(AshScriptPlugin, NativeImage)

mappings in Universal ++= directory("src/main/resources")

Revolver.enableDebugging(port = 5050, suspend = true)
envVars in reStart := Map("NAMESPACE" -> "test")

releaseProcess :=
  Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    setReleaseVersion,
    releaseStepCommandAndRemaining("docker:publish"),
    releaseStepTask(publishDockerNativeImage),
    commitReleaseVersion,
    tagRelease,
    inquireVersions,
    setNextVersion,
    commitNextVersion,
    pushChanges
  )