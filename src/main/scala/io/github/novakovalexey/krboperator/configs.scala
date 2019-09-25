package io.github.novakovalexey.krboperator

import java.io.File

import com.typesafe.config.{ConfigFactory, ConfigParseOptions}
import com.typesafe.scalalogging.StrictLogging
import pureconfig.error.ConfigReaderFailures
import pureconfig.generic.ProductHint
import pureconfig.generic.auto._
import pureconfig.{loadConfig, CamelCase, ConfigFieldMapping}

final case class KrbOperatorCfg(
  image: String,
  templatePath: String,
  kadminContainer: String,
  k8sResourcesPrefix: String,
  secretForAdminPwd: String
)

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
