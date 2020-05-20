package io.github.novakovalexey.krboperator

import java.io.File

import cats.Parallel
import cats.effect.{ExitCode, IO, IOApp}
import freya.Retry.Times
import cats.syntax.apply._
import ch.qos.logback.classic.ClassicConstants.CONFIG_FILE_PROPERTY
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object Main extends IOApp {
  implicit val ioPar: Parallel[IO] = cats.effect.IO.ioParallel

  override def run(args: List[String]): IO[ExitCode] = {
    initializeLogback()
    val mod = new Module[IO](IO(Module.defaultClient))
    IO {
      println("""
          | _  __     _    _____    ____                       _
          || |/ /    | |  | ____|  / __ \                     | |
          || ' / _ __| |__| |__   | |  | |_ __   ___ _ __ __ _| |_ ___  _ __
          ||  < | '__| '_ \___ \  | |  | | '_ \ / _ \ '__/ _` | __/ _ \| '__|
          || . \| |  | |_) |__) | | |__| | |_) |  __/ | | (_| | || (_) | |
          ||_|\_\_|  |_.__/____/   \____/| .__/ \___|_|  \__,_|\__\___/|_|
          |                              | |
          |                              |_|
          |""".stripMargin)
      println(s"version: ${buildinfo.BuildInfo.version}, build time: ${buildinfo.BuildInfo.builtAtString}")
    } *> mod.operator
      .withReconciler(mod.operatorCfg.reconcilerInterval)
      .withRestart(Times(maxRetries = 2, delay = 1.second, multiplier = 3))
  }

  /*
  * needed for GraalVM native image build
  */
  def initializeLogback(): Unit = {
    val path = sys.env.getOrElse("LOGBACK_CONFIG_FILE", "src/main/resources/logback.xml")
    System.setProperty(CONFIG_FILE_PROPERTY, path)
    val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    loggerContext.reset()
    val configurator = new JoranConfigurator()
    configurator.setContext(loggerContext)

    val logbackFile = new File(path).getAbsolutePath
    configurator.doConfigure(logbackFile)
  }
}
