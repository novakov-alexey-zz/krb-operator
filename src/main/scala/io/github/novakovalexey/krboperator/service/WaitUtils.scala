package io.github.novakovalexey.krboperator.service

import cats.effect.{Sync, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.github.novakovalexey.krboperator.service.WaitUtils._
import io.github.novakovalexey.krboperator.Utils._

import scala.concurrent.duration._

object WaitUtils {
  val defaultDelay: FiniteDuration = 500.millis
}

trait WaitUtils extends LazyLogging {

  def waitFor[F[_]](namespace: String, waitTime: FiniteDuration)(action: F[Boolean])(implicit F: Sync[F], T: Timer[F]): F[Boolean] =
    waitFor[F, Nothing](namespace, waitTime, _ => F.unit, defaultDelay)(action.map(_ -> None)).map {
      case (status, _) => status
    }

  def waitFor[F[_], S](namespace: String, waitTime: FiniteDuration, peek: Option[S] => F[Unit], delay: FiniteDuration = defaultDelay)(
    action: F[(Boolean, Option[S])]
  )(implicit F: Sync[F], T: Timer[F]): F[(Boolean, Option[S])] = {
    val debug = logDebugWithNamespace(logger)

    def loop(spent: FiniteDuration, waitTime: FiniteDuration): F[(Boolean, Option[S])] =
      action.flatMap {
        case (true, state) => (true, state).pure[F]
        case (false, state) if spent < waitTime =>
          F.whenA(spent.toMillis != 0 && spent.toMillis % 5000 == 0) {
            peek(state) *> F.delay(debug(namespace, s"Already spent time: ${spent.toSeconds} secs / $waitTime"))
          } *> T.sleep(delay) *> loop(spent + delay, waitTime)
        case (_, state) =>
          F.delay(debug(namespace, s"Was waiting for ${spent.toMinutes} mins")) *> (true, state).pure[F]
      }

    loop(0.millisecond, waitTime)
  }
}
