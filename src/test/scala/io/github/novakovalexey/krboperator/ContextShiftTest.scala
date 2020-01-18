package io.github.novakovalexey.krboperator

import cats.effect.{ContextShift, IO, Timer}

import scala.concurrent.ExecutionContext

trait ContextShiftTest {
  protected implicit def CS(implicit ec: ExecutionContext): ContextShift[IO] =
    IO.contextShift(ec)

  protected implicit def timer(implicit ec: ExecutionContext): Timer[IO] =
    IO.timer(ec)
}