package io.github.novakovalexey.krboperator

import cats.Parallel
import cats.effect.{ConcurrentEffect, Timer}
import com.typesafe.scalalogging.LazyLogging
import freya.Configuration.CrdConfig
import freya.K8sNamespace.{AllNamespaces, CurrentNamespace, Namespace}
import freya._
import freya.json.circe._
import io.circe.generic.extras.auto._
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.client.{DefaultOpenShiftClient, OpenShiftClient, OpenShiftConfigBuilder}
import io.github.novakovalexey.krboperator.service.{KeytabPathAlg, _}

object Module {
  def defaultClient: OpenShiftClient =
    new DefaultOpenShiftClient(
      new OpenShiftConfigBuilder()
        .withWebsocketTimeout(30 * 1000)
        .withConnectionTimeout(30 * 1000)
        .withRequestTimeout(30 * 1000)
        .withRollingTimeout(30 * 1000)
        .withScaleTimeout(30 * 1000)
        .withBuildTimeout(30 * 1000)
        .build()
    )
}

class Module[F[_]: ConcurrentEffect: Parallel: Timer: PodsAlg](client: F[KubernetesClient])(
  implicit pathGen: KeytabPathAlg
) extends Codecs {
  val operatorCfg: KrbOperatorCfg = AppConfig.load().fold(e => sys.error(s"failed to load config: $e"), identity)
  val cfg: CrdConfig = CrdConfig(
    NamespaceHelper.getNamespace,
    "io.github.novakov-alexey",
    additionalPrinterColumns = List(
      AdditionalPrinterColumn(name = "Realm", columnType = "string", jsonPath = ".spec.realm"),
      AdditionalPrinterColumn(name = "Age", columnType = "date", jsonPath = ".metadata.creationTimestamp")
    )
  )

  def openShiftTemplate(client: OpenShiftClient, secrets: Secrets[F]): Template[F, DeploymentConfig] =
    new Template[F, DeploymentConfig](client, secrets, operatorCfg)

  def k8sTemplate(client: OpenShiftClient, secrets: Secrets[F])(
    implicit resource: DeploymentResource[Deployment]
  ): Template[F, Deployment] =
    new Template[F, Deployment](client, secrets, operatorCfg)

  def controller(h: CrdHelper[F, Krb, Status]): KubernetesClient => KrbController[F] = (client: KubernetesClient) => {
    val secrets = new Secrets[F](client, operatorCfg)
    val kadmin = new Kadmin[F](client, operatorCfg)
    val openShiftClient = client.asInstanceOf[OpenShiftClient]
    val template: Template[F, _ <: HasMetadata] =
      if (h.context.isOpenShift.getOrElse(false))
        openShiftTemplate(openShiftClient, secrets)
      else k8sTemplate(openShiftClient, secrets)
    controllerFor(openShiftClient, template, secrets, kadmin)
  }

  def controllerFor(
    client: OpenShiftClient,
    template: Template[F, _ <: HasMetadata],
    secrets: Secrets[F],
    kadmin: Kadmin[F],
    parallelSecret: Boolean = true
  ): KrbController[F] =
    new KrbController[F](client, cfg, operatorCfg, template, kadmin, secrets, parallelSecret)

  lazy val operator = Operator.ofCrd[F, Krb, Status](cfg, client)(controller)
}

object NamespaceHelper {
  val AllNamespacesValue = "ALL"

  def getNamespace: K8sNamespace = {
    val namespace = sys.env.getOrElse("NAMESPACE", AllNamespacesValue)
    namespace match {
      case AllNamespacesValue => AllNamespaces
      case _ if (namespace == "CURRENT") => CurrentNamespace
      case _ => Namespace(namespace)
    }
  }
}
