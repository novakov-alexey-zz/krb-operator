package io.github.novakovalexey.krboperator

import cats.Parallel
import cats.effect.ConcurrentEffect
import io.fabric8.openshift.client.{DefaultOpenShiftClient, OpenShiftConfigBuilder}
import io.github.novakovalexey.k8soperator.{AllNamespaces, CrdConfig}
import io.github.novakovalexey.k8soperator.Operator
import io.github.novakovalexey.krboperator.service.{Kadmin, SecretService, Template}

class Module[F[_]: ConcurrentEffect: Parallel] {
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

  val secret = new SecretService[F](client, operatorCfg)
  val template = new Template[F](client, secret, operatorCfg)
  val kadmin = new Kadmin[F](client, operatorCfg)
  val cfg = CrdConfig(classOf[Krb], AllNamespaces, "io.github.novakov-alexey")
  val controller = new KrbOperator[F](client, cfg, operatorCfg, template, kadmin, secret)
  val operator = Operator.ofCrd[F, Krb](cfg, client, controller)
}
