package io.github.novakovalexey.krboperator

import io.fabric8.openshift.client.{DefaultOpenShiftClient, OpenShiftConfigBuilder}
import io.github.novakovalexey.k8soperator4s.Scheduler
import io.github.novakovalexey.k8soperator4s.common.{AllNamespaces, CrdConfig}
import io.github.novakovalexey.krboperator.service.{Kadmin, SecretService, Template}

import scala.concurrent.ExecutionContext.Implicits.global

class Module {
  val operatorCfg: KrbOperatorCfg = AppConfig.load().fold(e => sys.error(s"failed to load config: $e"), identity)
  val client = new DefaultOpenShiftClient(
    new OpenShiftConfigBuilder()
      .withWebsocketTimeout(30 * 1000)
      .withConnectionTimeout(30 * 1000)
      .withRequestTimeout(30 * 1000)
      .withRollingTimeout(30 * 1000)
      .withScaleTimeout(30 * 1000)
      .withBuildTimeout(30 * 1000)
      .build()
  )

  val secret = new SecretService(client, operatorCfg)
  val template = new Template(client, secret, operatorCfg)
  val kadmin = new Kadmin(client, operatorCfg)
  val cfg = CrdConfig(classOf[Krb], AllNamespaces, "io.github.novakov-alexey")
  val operator = new KrbOperator(client, cfg, operatorCfg, template, kadmin, secret)
  val scheduler = new Scheduler[Krb](operator)
}
