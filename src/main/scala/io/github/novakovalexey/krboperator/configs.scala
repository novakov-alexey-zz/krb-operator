package io.github.novakovalexey.krboperator

import java.io.File

import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import com.typesafe.scalalogging.StrictLogging
import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.ProductHint
import pureconfig.generic.auto._
import pureconfig.{CamelCase, ConfigFieldMapping, loadConfig}

import scala.concurrent.duration.FiniteDuration

final case class KrbOperatorCfg(
  krb5Image: String,
  k8sSpecsDir: String,
  adminPrincipal: String,
  commands: Commands,
  kadminContainer: String,
  k8sResourcesPrefix: String,
  adminPwd: AdminPassword,
  reconcilerInterval: FiniteDuration
)
final case class KeytabCommand(randomKey: String, noRandomKey: String)
final case class Commands(addPrincipal: String, addKeytab: KeytabCommand)
final case class AdminPassword(secretName: String, secretKey: String)

object AppConfig extends StrictLogging {
  private val parseOptions = ConfigParseOptions.defaults().setAllowMissing(false)

  private val cfgPath: String = sys.env.getOrElse("APP_CONFIG_PATH", "src/main/resources/application.conf")

  implicit def hint[T]: ProductHint[T] = ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

  def load(path: String = cfgPath): Either[ConfigReaderFailures, KrbOperatorCfg] = {
    val config = ConfigFactory.parseFile(new File(path), parseOptions).resolve()
    logger.info(s"loading config file at $path")

    loadConfig[KrbOperatorCfg](config, "operator")
  }
}
