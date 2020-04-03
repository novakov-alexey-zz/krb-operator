package io.github.novakovalexey.krboperator

import com.typesafe.scalalogging.Logger

object Utils {
  val logDebugWithNamespace: Logger => (String, String) => Unit = logger =>
    (namespace, msg) => {
      logger.debug(s"${AnsiColors.gr}[$namespace]${AnsiColors.xx} $msg")
    }

  val logInfoWithNamespace: Logger => (String, String) => Unit = logger =>
    (namespace, msg) => {
      logger.info(s"${AnsiColors.gr}[$namespace]${AnsiColors.xx} $msg")
    }

  val logErrorWithNamespace: Logger => (String, String, Throwable) => Unit = logger =>
    (namespace, msg, t) => {
      logger.error(s"${AnsiColors.gr}[$namespace]${AnsiColors.xx} $msg", t)
    }
}
