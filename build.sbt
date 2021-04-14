import com.typesafe.sbt.SbtNativePackager.autoImport.NativePackagerHelper._
import sbtrelease.ReleaseStateTransformations._

name := "kerberos-operator"
scalaVersion := "2.13.5"
ThisBuild / organization := "io.github.novakov-alexey"
ThisBuild / turbo := true
Global / onChangedBuildSource := ReloadOnSourceChanges

resolvers += "Local Maven Repository".at("file://" + Path.userHome.absolutePath + "/.ivy2/local")

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
    Docker / dockerRepository := Some("alexeyn"),
    Universal / javaOptions ++= Seq("-Dlogback.configurationFile=/opt/conf/logback.xml"),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoOptions += BuildInfoOption.BuildTime,
    assembly / assemblyMergeStrategy := {
      case PathList("javax", "servlet", _*) => MergeStrategy.last
      case PathList("javax", "activation", _*) => MergeStrategy.last
      case PathList("javax", "xml", _*) => MergeStrategy.last
      case PathList("org", "apache", "commons", _*) => MergeStrategy.last
      case PathList("io", "sundr", _*) => MergeStrategy.last
      case PathList("res", _*) => MergeStrategy.discard
      case PathList("android", "os", _*) => MergeStrategy.first
      case PathList("android", _*) => MergeStrategy.discard
      case PathList(ps @ _*) if Assembly.isReadme(ps.last) || Assembly.isLicenseFile(ps.last) => MergeStrategy.discard
      case PathList(ps @ _*) if ps.last.endsWith(".html") => MergeStrategy.first
      case PathList(ps @ _*) if ps.last.endsWith(".aut") => MergeStrategy.discard
      case "application.conf" => MergeStrategy.concat
      case "META-INF/versions/9/javax/xml/bind/ModuleUtil.class" => MergeStrategy.first
      case "schema/kube-schema.json" => MergeStrategy.first
      case "kube-validation-schema.json" => MergeStrategy.first
      case "validation-schema.json" => MergeStrategy.first
      case "schema/kube-validation-schema.json" => MergeStrategy.first
      case "schema/validation-schema.json" => MergeStrategy.first
      case "module-info.class" => MergeStrategy.discard
      case "resources.arsc" => MergeStrategy.discard
      case "META-INF/jandex.idx" => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
    assembly / test := {}
  )
  .enablePlugins(BuildInfoPlugin, AshScriptPlugin)

Universal / mappings ++= directory("src/main/resources")

Revolver.enableDebugging(port = 5050, suspend = true)
reStart / envVars := Map("NAMESPACE" -> "test")

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
