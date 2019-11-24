package io.github.novakovalexey.krboperator

import cats.Parallel
import cats.effect.{ConcurrentEffect, Timer}
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.client.{DefaultOpenShiftClient, OpenShiftConfigBuilder}
import io.github.novakovalexey.k8soperator.common.CrdOperator
import io.github.novakovalexey.k8soperator.{AllNamespaces, CrdConfig, Operator}
import io.github.novakovalexey.krboperator.service.{Kadmin, SecretService, Template}

class Module[F[_]: ConcurrentEffect: Parallel: Timer] {
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
  val kadmin = new Kadmin[F](client, operatorCfg)
  val cfg = CrdConfig(classOf[Krb], AllNamespaces, "io.github.novakov-alexey")

  def controller(op: CrdOperator[F, Krb]): KrbController[F] = {
    val template =
      if (op.isOpenShift)
        new Template[F, DeploymentConfig](client, secret, operatorCfg)
      else
        new Template[F, Deployment](client, secret, operatorCfg)
    new KrbController[F](client, cfg, operatorCfg, template, kadmin, secret)
  }

  val operator: Operator[F, Krb] = Operator.ofCrd[F, Krb](cfg, client)(controller)
}
