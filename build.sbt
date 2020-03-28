import com.typesafe.sbt.SbtNativePackager.autoImport.NativePackagerHelper._
import sbtrelease.ReleaseStateTransformations._
import sbt.Keys.streams
import scala.sys.process._

name := "kerberos-operator"
scalaVersion := "2.13.2"
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
    dockerRepository := Some("alexeyn"),
    javaOptions in Universal ++= Seq("-Dlogback.configurationFile=/opt/conf/logback.xml"),
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoOptions += BuildInfoOption.BuildTime,
    assemblyMergeStrategy in assembly := {
        case PathList("javax", "servlet", xs @ _*) => MergeStrategy.last
        case PathList("javax", "activation", xs @ _*) => MergeStrategy.last
        case PathList("javax", "xml", xs @ _*) => MergeStrategy.last
        case PathList(ps @ _*) if ps.last.endsWith(".html") => MergeStrategy.first
        case "application.conf" => MergeStrategy.concat
        case "META-INF/versions/9/javax/xml/bind/ModuleUtil.class" => MergeStrategy.first
        case "module-info.class" => MergeStrategy.discard
        case "META-INF/jandex.idx" => MergeStrategy.discard
        case x =>
          val oldStrategy = (assemblyMergeStrategy in assembly).value
          oldStrategy(x)
      },
    test in assembly := {}
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

lazy val nativeImageAgent =
  taskKey[File]("Run GraalVM Native Image Agent to analyze reflection")

nativeImageAgent := {
  val assemblyFatJar = assembly.value
  val assemblyFatJarPath = assemblyFatJar.getAbsolutePath
  val outputPath = (baseDirectory.value / "out").getAbsolutePath
  val cmd = s"java -agentlib:native-image-agent=config-output-dir=out/ -jar $assemblyFatJarPath"

  val log = streams.value.log
  log.info(s"Running native image agent for $assemblyFatJarPath")
  log.debug(cmd)
  val result = (cmd.!(log))

  if (result == 0) file(s"$outputPath")
  else {
    log.error(s"Local native image agent command failed:\n ${cmd}")
    throw new Exception("Local native image agent command failed")
  }
}

lazy val nativeImageLocal =
  taskKey[File]("Build a standalone executable on this machine using GraalVM Native Image")

nativeImageLocal := {
  val assemblyFatJar = assembly.value
  val assemblyFatJarPath = assemblyFatJar.getAbsolutePath
  val outputName = "krb5-operator.local"
  val outputPath = (baseDirectory.value / "out" / outputName).getAbsolutePath
  val cmd = s"""native-image
     | -jar $assemblyFatJarPath
     | $outputPath""".stripMargin.filter(_ != '\n')

  val log = streams.value.log
  log.info(s"Building local native image from ${assemblyFatJarPath}")
  log.debug(cmd)
  val result = (cmd.!(log))

  if (result == 0) file(s"${outputPath}")
  else {
    log.error(s"Local native image command failed:\n ${cmd}")
    throw new Exception("Local native image command failed")
  }
}

lazy val nativeImage =
  taskKey[File]("Build a standalone Linux executable using GraalVM Native Image")

nativeImage := {
  val assemblyFatJar = assembly.value
  val assemblyFatJarPath = assemblyFatJar.getParent
  val assemblyFatJarName = assemblyFatJar.getName
  val outputPath = (baseDirectory.value / "out").getAbsolutePath
  val outputName = "krb5-operator"
  val nativeImageDocker = "graalvm-native-image"

  val cmd = s"""docker run
     | --volume ${assemblyFatJarPath}:/opt/assembly
     | --volume ${outputPath}:/opt/native-image
     | ${nativeImageDocker}
     | --static
     | -jar /opt/assembly/${assemblyFatJarName}
     | ${outputName}""".stripMargin.filter(_ != '\n')

  val log = streams.value.log
  log.info(s"Building native image from ${assemblyFatJarName}")
  log.debug(cmd)
  val result = (cmd.!(log))

  if (result == 0) file(s"${outputPath}/${outputName}")
  else {
    log.error(s"Native image command failed:\n ${cmd}")
    throw new Exception("Native image command failed")
  }
}
