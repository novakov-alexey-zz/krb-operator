package io.github.novakovalexey.krboperator

import cats.Parallel
import cats.effect.{ExitCode, IO, IOApp}
import freya.Times

object Main extends IOApp {
  implicit val ioPar: Parallel[IO] = cats.effect.IO.ioParallel

  override def run(args: List[String]): IO[ExitCode] = {
    val mod = new Module[IO]
    mod.operator.withRestart(Times(3))
  }
}
