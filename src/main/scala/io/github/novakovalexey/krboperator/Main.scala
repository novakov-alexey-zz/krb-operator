package io.github.novakovalexey.krboperator

import cats.Parallel
import cats.effect.{ExitCode, IO, IOApp}
import io.github.novakovalexey.k8soperator.Retry

object Main extends IOApp {
  implicit val ioPar: Parallel[IO] = cats.effect.IO.ioParallel

  override def run(args: List[String]): IO[ExitCode] = {
    val mod = new Module[IO]
    mod.operator.withRestart(Retry(3))
  }
}
