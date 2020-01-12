package io.github.novakovalexey.krboperator

import cats.Parallel
import cats.effect.{ConcurrentEffect, Sync, Timer}
import freya.Configuration.CrdConfig
import freya.K8sNamespace.{AllNamespaces, CurrentNamespace, Namespace}
import freya.{Controller, CrdHelper, K8sNamespace, Operator}
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.client.{DefaultOpenShiftClient, OpenShiftConfigBuilder}
import io.github.novakovalexey.krboperator.service.{Kadmin, Secrets, Template}

class Module[F[_]: ConcurrentEffect: Parallel: Timer] {
  val operatorCfg = AppConfig.load().fold(e => sys.error(s"failed to load config: $e"), identity)
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

  val secret = new Secrets[F](client, operatorCfg)
  val kadmin = new Kadmin[F](client, operatorCfg)
  val cfg = CrdConfig(classOf[Krb], NamespaceHelper.getNamespace, "io.github.novakov-alexey")

  def controller(h: CrdHelper[F, Krb]): Controller[F, Krb] = {
    val template =
      if (h.context.isOpenShift.getOrElse(false))
        new Template[F, DeploymentConfig](client, secret, operatorCfg)
      else
        new Template[F, Deployment](client, secret, operatorCfg)
    new KrbController[F](client, cfg, operatorCfg, template, kadmin, secret)
  }

  val operator = Operator.ofCrd[F, Krb](cfg, Sync[F].pure(client))(controller)
}

object NamespaceHelper {
  val AllNamespacesValue = "ALL"

  def getNamespace: K8sNamespace = {
    val namespace = sys.env.getOrElse("NAMESPACE", AllNamespacesValue)
    if (namespace == AllNamespacesValue)
      AllNamespaces
    else if (namespace == "CURRENT")
      CurrentNamespace
    else
      Namespace(namespace)
  }
}
