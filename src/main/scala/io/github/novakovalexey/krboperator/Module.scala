package io.github.novakovalexey.krboperator

import cats.Parallel
import cats.effect.{ConcurrentEffect, Timer}
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
import io.github.novakovalexey.krboperator.Module.OperatorApi
import io.github.novakovalexey.krboperator.service.{KeytabPathAlg, _}

object Module {
  val OperatorApi = "io.github.novakov-alexey"
  def defaultClient: KubernetesClient =
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

class Module[F[_]: ConcurrentEffect: Parallel: Timer: PodsAlg](client: F[KubernetesClient])(implicit
  pathGen: KeytabPathAlg
) extends Codecs {
  val operatorCfg: KrbOperatorCfg = AppConfig.load.fold(e => sys.error(s"failed to load config: $e"), identity)
  val cfg: CrdConfig = CrdConfig(
    NamespaceHelper.getNamespace,
    OperatorApi,
    additionalPrinterColumns = List(
      AdditionalPrinterColumn(name = "Realm", columnType = "string", jsonPath = ".spec.realm"),
      AdditionalPrinterColumn(name = "Age", columnType = "date", jsonPath = ".metadata.creationTimestamp")
    )
  )

  def openShiftTemplate(client: OpenShiftClient, secrets: Secrets[F]): Template[F, DeploymentConfig] =
    new Template[F, DeploymentConfig](client, secrets, operatorCfg)

  def k8sTemplate(client: OpenShiftClient, secrets: Secrets[F])(implicit
    resource: DeploymentResource[Deployment]
  ): Template[F, Deployment] =
    new Template[F, Deployment](client, secrets, operatorCfg)

  def serverController(h: CrdHelper[F, KrbServer, KrbServerStatus]): KubernetesClient => ServerController[F] =
    (client: KubernetesClient) => {
      val secrets = new Secrets[F](client, operatorCfg)
      val openShiftClient = client.asInstanceOf[OpenShiftClient]
      val template: Template[F, _ <: HasMetadata] =
        if (h.context.isOpenShift.getOrElse(false))
          openShiftTemplate(openShiftClient, secrets)
        else k8sTemplate(openShiftClient, secrets)
      serverControllerFor(template, secrets)
    }

  val principalsController
    : CrdHelper[F, PrincipalList, PrincipalListStatus] => KubernetesClient => PrincipalsController[F] = _ =>
    client => {
      val secrets = new Secrets[F](client, operatorCfg)
      val kadmin = new Kadmin[F](client, operatorCfg)
      val openShiftClient = client.asInstanceOf[OpenShiftClient]
      new PrincipalsController[F](openShiftClient, secrets, kadmin, operatorCfg, false)
    }

  def serverControllerFor(template: Template[F, _ <: HasMetadata], secrets: Secrets[F]): ServerController[F] =
    new ServerController[F](template, secrets)

  lazy val serverOperator: Operator[F, KrbServer, KrbServerStatus] =
    Operator.ofCrd[F, KrbServer, KrbServerStatus](cfg, client)(serverController)

  lazy val principalsOperator: Operator[F, PrincipalList, PrincipalListStatus] =
    Operator.ofCrd[F, PrincipalList, PrincipalListStatus](cfg, client)(principalsController)
}

object NamespaceHelper {
  val AllNamespacesValue = "ALL"

  def getNamespace: K8sNamespace = {
    val namespace = sys.env.getOrElse("NAMESPACE", AllNamespacesValue)
    namespace match {
      case AllNamespacesValue => AllNamespaces
      case _ if namespace == "CURRENT" => CurrentNamespace
      case _ => Namespace(namespace)
    }
  }
}
