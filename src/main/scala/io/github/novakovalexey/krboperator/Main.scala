package io.github.novakovalexey.krboperator

import scala.concurrent.Await
import scala.concurrent.duration._

object Main extends App {
  val mod = new Module
  val f = mod.scheduler.start()
  Await.ready(f, 1.minute)
}
