package io.github.novakovalexey.krboperator

import cats.Parallel
import cats.effect.{ExitCode, IO, IOApp}
import freya.Retry.Infinite

import scala.concurrent.duration._

object Main extends IOApp {
  implicit val ioPar: Parallel[IO] = cats.effect.IO.ioParallel

  override def run(args: List[String]): IO[ExitCode] = {
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
      .withRestart(Infinite(maxDelay = 10.seconds))
  }
}
