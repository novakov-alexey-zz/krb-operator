package io.github.novakovalexey.krboperator

import java.io.File

import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import com.typesafe.scalalogging.LazyLogging
import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.ProductHint
import pureconfig.generic.auto._
import pureconfig.{CamelCase, ConfigFieldMapping, ConfigSource}

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

final case class KrbOperatorCfg(
  krb5Image: String,
  k8sSpecsDir: String,
  adminPrincipal: String,
  commands: Commands,
  kadminContainer: String,
  k8sResourcesPrefix: String,
  adminPwd: AdminPassword,
  reconcilerInterval: FiniteDuration,
  operatorPrefix: String
)
final case class KeytabCommand(randomKey: String, noRandomKey: String)
final case class Commands(addPrincipal: String, addKeytab: KeytabCommand)
final case class AdminPassword(secretName: String, secretKey: String)

object AppConfig extends LazyLogging {
  private lazy val parseOptions = ConfigParseOptions.defaults().setAllowMissing(false)

  private def cfgPath: String = sys.env.getOrElse("APP_CONFIG_PATH", "src/main/resources/application.conf")

  implicit def hint[T]: ProductHint[T] = ProductHint[T](ConfigFieldMapping(CamelCase, CamelCase))

  def load: Either[ConfigReaderFailures, KrbOperatorCfg] = {
    val path = cfgPath
    val config = ConfigFactory
      .parseFile(new File(path), parseOptions)
      .withFallback(ConfigFactory.parseMap(sys.env.asJava))
      .resolve()
    logger.info(s"loading config file at $path")

    ConfigSource.fromConfig(config).at("operator").load[KrbOperatorCfg]
  }
}
