package io.github.novakovalexey.krboperator

import buildinfo.BuildInfo
import cats.Parallel
import cats.effect.{ExitCode, IO, IOApp, Timer}
import ch.qos.logback.classic.ClassicConstants.CONFIG_FILE_PROPERTY
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import freya.Operator
import freya.Retry.Times
import org.slf4j.LoggerFactory

import java.io.File
import scala.concurrent.duration._

object Main extends IOApp {
  implicit val ioPar: Parallel[IO] = cats.effect.IO.ioParallel

  override def run(args: List[String]): IO[ExitCode] = for {
    _ <- IO(initializeLogback())
    mod <- IO(new Module[IO](IO(Module.defaultClient)))
    server = withConfig(mod.serverOperator, mod.operatorCfg.reconcilerInterval)
    principals = withConfig(mod.principalsOperator, mod.operatorCfg.reconcilerInterval)
    start <- IO {
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
      println(s"version: ${BuildInfo.version}, build time: ${BuildInfo.builtAtString}")
    } *> IO.race(server, principals).map(_.merge)
  } yield start

  private def withConfig[F[_], T, U](o: Operator[F, T, U], reconcilerInterval: FiniteDuration)(implicit T: Timer[F]) =
    o.withReconciler(reconcilerInterval)
      .withRestart(Times(maxRetries = 2, delay = 1.second, multiplier = 3))

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
