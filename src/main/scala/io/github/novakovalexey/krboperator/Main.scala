package io.github.novakovalexey.krboperator

import buildinfo.BuildInfo
import cats.effect.{ExitCode, IO, IOApp}
import freya.Operator
import freya.Retry.Times

import scala.concurrent.duration._

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = for {
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

  private def withConfig[F[_], T, U](o: Operator[F, T, U], reconcilerInterval: FiniteDuration) =
    o.withReconciler(reconcilerInterval)
      .withRestart(Times(maxRetries = 2, delay = 1.second, multiplier = 3))
}
