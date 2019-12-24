package io.github.novakovalexey.krboperator.service

import cats.effect.{Sync, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration._

trait WaitUtils extends LazyLogging {

  def waitFor[F[_]](maxDuration: FiniteDuration)(action: => Boolean)(implicit F: Sync[F], T: Timer[F]): F[Boolean] = {
    def loop(spent: FiniteDuration, maxDuration: FiniteDuration): F[Boolean] =
      F.delay {
        action
      }.flatMap {
        case true if spent >= maxDuration =>
          false.pure[F]
        case false =>
          val pause = 500.milliseconds
          F.delay(
            if (spent.toMillis != 0 && spent.toMillis % 5000 == 0)
              logger.debug(s"Already spent time: ${spent.toSeconds} secs / $maxDuration")
            else ()
          ) *>
            T.sleep(pause) *> loop(spent + pause, maxDuration)
        case _ =>
          F.delay(logger.debug(s"Was waiting for ${spent.toMinutes} mins")) *> true.pure[F]
      }

    loop(0.millisecond, maxDuration)
  }
}
