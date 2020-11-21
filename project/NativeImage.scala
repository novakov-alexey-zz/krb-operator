import sbt.Keys._
import sbt.{AutoPlugin, Def, File, taskKey, _}
import sbtassembly.AssemblyKeys._
import sbtassembly.AssemblyPlugin

import scala.sys.process._

object NativeImage extends AutoPlugin {
  lazy val dockerRepositoryNative = settingKey[Option[String]]("Docker Repository")
  lazy val nativeImageAgentLocal =
    taskKey[File]("Run GraalVM Native Image Agent to analyze reflection")

  lazy val nativeImageLocal =
    taskKey[File]("Build a standalone executable on this machine using GraalVM Native Image")

  lazy val nativeImageAgent =
    taskKey[File]("Run Native Image agent inside the Docker container")

  lazy val nativeImage =
    taskKey[File]("Build a standalone Linux executable using GraalVM Native Image")

  lazy val publishDockerNativeImage =
    taskKey[Unit]("Build and push Kerberos Operator Docker image using GraalVM Native Image")

  override def requires: AssemblyPlugin.type = sbtassembly.AssemblyPlugin
  override def trigger = allRequirements

  override def projectSettings: Seq[Def.Setting[_]] = Seq[Def.Setting[_]](
    nativeImageAgentLocal := runNativeImageAgent.value,
    nativeImageLocal := buildLocal.value,
    nativeImageAgent := runAgentInDocker.value,
    nativeImage := build.value,
    publishDockerNativeImage := publishDockerGraalNative.value
  )

  def runNativeImageAgent = {
    Def.taskDyn {
      val assemblyFatJar = assembly.value
      val assemblyFatJarPath = assemblyFatJar.getAbsolutePath

      val outputPath = (baseDirectory.value / "out").getAbsolutePath
      val cmd = s"java -agentlib:native-image-agent=config-output-dir=out/ -jar $assemblyFatJarPath"

      val log = streams.value.log
      log.info(s"Running native image agent for $assemblyFatJarPath")
      log.debug(cmd)
      val result = cmd.!(log)

      Def.task {
        if (result == 0) file(s"$outputPath")
        else {
          log.error(s"Local native image agent command failed:\n ${cmd}")
          throw new Exception("Local native image agent command failed")
        }
      }
    }
  }

  def buildLocal =
    Def.taskDyn {
      val assemblyFatJar = assembly.value
      val assemblyFatJarPath = assemblyFatJar.getAbsolutePath
      val outputName = "kerberos-operator.local"
      val outputPath = (baseDirectory.value / "out" / outputName).getAbsolutePath
      val cmd = s"""native-image --verbose --static
     | -jar $assemblyFatJarPath
     | $outputPath""".stripMargin.filter(_ != '\n')

      val log = streams.value.log
      log.info(s"Building local native image from ${assemblyFatJarPath}")
      log.debug(cmd)
      val result = (cmd.!(log))

      Def.task {
        if (result == 0) file(s"$outputPath")
        else {
          log.error(s"Local native image command failed:\n ${cmd}")
          throw new Exception("Local native image command failed")
        }
      }
    }

  lazy val kubeCfg: String = new File(sys.props("user.home"), ".kube/config").getAbsolutePath

  def runAgentInDocker = Def.taskDyn {
    val assemblyFatJar = assembly.value
    val assemblyFatJarPath = assemblyFatJar.getParent
    val assemblyFatJarName = assemblyFatJar.getName
    val outputPath = (baseDirectory.value / "out").getAbsolutePath
    val resources = (baseDirectory.value / "src" / "main" / "resources").getAbsolutePath
    val nativeImageDocker = "graalvm-native-image:20.0.0-java11"

    val cmd = s"""docker run
     | --volume $assemblyFatJarPath:/opt/assembly
     | --volume $outputPath:/opt/native-image
     | --volume $resources:/opt/native-image/src/main/resources
     | --volume $kubeCfg:/root/.kube/config
     | --entrypoint java
     | $nativeImageDocker
     | -agentlib:native-image-agent=config-output-dir=/opt/native-image
     | -jar /opt/assembly/$assemblyFatJarName""".stripMargin.filter(_ != '\n')

    val log = streams.value.log
    log.info(s"Running native image agent for ${assemblyFatJarName} in Docker")
    log.debug(cmd)
    val result = cmd.!(log)
    Def.task {
      if (result == 0) file(s"$outputPath")
      else {
        log.error(s"Native image agent command in Docker failed:\n $cmd")
        throw new Exception("Native image agent in Docker command failed")
      }
    }
  }

  def build = Def.taskDyn {
    val assemblyFatJar = assembly.value
    val assemblyFatJarPath = assemblyFatJar.getParent
    val assemblyFatJarName = assemblyFatJar.getName
    val outputPath = (baseDirectory.value / "out").getAbsolutePath
    val resources = (baseDirectory.value / "src" / "main" / "resources").getAbsolutePath
    val outputName = "kerberos-operator"
    val nativeImageDocker = "graalvm-native-image:20.0.0-java11"

    val cmd = s"""docker run
     | --volume $assemblyFatJarPath:/opt/assembly
     | --volume $outputPath:/opt/native-image
     | --volume $resources:/opt/native-image/src/main/resources
     | --volume $kubeCfg:/root/.kube/config
     | $nativeImageDocker
     | -jar /opt/assembly/$assemblyFatJarName
     | $outputName""".stripMargin.filter(_ != '\n')

    val log = streams.value.log
    log.info(s"Building native image from ${assemblyFatJarName}")
    log.debug(cmd)
    val result = cmd.!(log)

    Def.task {
      if (result == 0) file(s"$outputPath/$outputName")
      else {
        log.error(s"Native image command failed:\n $cmd")
        throw new Exception("Native image command failed")
      }
    }
  }

  def publishDockerGraalNative = Def.taskDyn {
    val imageName = s"${name.value}:${version.value}-graal-native"
    val jarPath = baseDirectory.value.getAbsoluteFile.toPath.relativize(assembly.value.getAbsoluteFile.toPath).toString
    val repoName = dockerRepositoryNative.value.getOrElse("alexeyn")
    val dockerBuild = s"docker build -f docker/Dockerfile_builder -t $imageName --build-arg KRB_OPERATOR_JAR_PATH=$jarPath ."
    val dockerTag = s"docker tag $imageName $repoName/$imageName"
    val dockerPush = s"docker push $repoName/$imageName"

    val log = streams.value.log
    log.info(s"Building Kerberos Operator Docker image using GraalVM native image of the operator")
    log.debug(dockerBuild)
    log.debug(dockerTag)
    log.debug(dockerPush)
    dockerBuild.#&&(dockerTag).#&&(dockerPush).!(log)
    Def.task {
      ()
    }
  }
}
