package io.github.novakovalexey.krboperator.service

import java.io.ByteArrayInputStream
import java.nio.file.{Path, Paths}

import cats.effect.{Sync, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.Metadata
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.client.OpenShiftClient
import io.github.novakovalexey.krboperator.service.Template._
import io.github.novakovalexey.krboperator.{Krb, KrbOperatorCfg}

import scala.concurrent.duration._
import scala.io.Source
import scala.util.{Random, Using}

object Template {
  val PrefixParam = "PREFIX"
  val AdminPwdParam = "ADMIN_PWD"
  val KdcServerParam = "KDC_SERVER"
  val KrbRealmParam = "KRB5_REALM"
  val Krb5Image = "KRB5_IMAGE"
  val DeploymentSelector = "deployment"

  val deploymentTimeout: FiniteDuration = 1.minute
}

trait DeploymentResource[T] {
  def delete(client: OpenShiftClient, resource: T): Boolean

  def findDeployment(client: OpenShiftClient, meta: Metadata): Option[T]

  def createOrReplace(client: OpenShiftClient, is: ByteArrayInputStream, meta: Metadata): T

  def isDeploymentReady(resource: T): Boolean

  val deploymentSpecName: String
}

class K8sDeploymentResource extends DeploymentResource[Deployment] {
  override def delete(client: OpenShiftClient, d: Deployment): Boolean =
    client.apps().deployments().inNamespace(d.getMetadata.getNamespace).delete(d)

  override def findDeployment(client: OpenShiftClient, meta: Metadata): Option[Deployment] =
    Option(client.apps().deployments().inNamespace(meta.namespace).withName(meta.name).get())

  override def createOrReplace(client: OpenShiftClient, is: ByteArrayInputStream, meta: Metadata): Deployment = {
    val dc = client.apps().deployments().load(is)
    client.apps().deployments().inNamespace(meta.namespace).createOrReplace(dc.get())
  }

  override val deploymentSpecName: String = "krb5-deployment.yaml"

  override def isDeploymentReady(resource: Deployment): Boolean =
    Readiness.isDeploymentReady(resource)
}

object DeploymentResource {

  implicit val k8sDeployment: DeploymentResource[Deployment] = new K8sDeploymentResource

  implicit val openShiftDeployment: DeploymentResource[DeploymentConfig] = new DeploymentResource[DeploymentConfig] {
    override def delete(client: OpenShiftClient, d: DeploymentConfig): Boolean =
      client.deploymentConfigs().inNamespace(d.getMetadata.getNamespace).delete(d)

    override def findDeployment(client: OpenShiftClient, meta: Metadata): Option[DeploymentConfig] =
      Option(client.deploymentConfigs().inNamespace(meta.namespace).withName(meta.name).get())

    override def createOrReplace(
      client: OpenShiftClient,
      is: ByteArrayInputStream,
      meta: Metadata
    ): DeploymentConfig = {
      val dc = client.deploymentConfigs().load(is)
      client.deploymentConfigs().inNamespace(meta.namespace).createOrReplace(dc.get())
    }

    override val deploymentSpecName: String = "krb5-deploymentconfig.yaml"

    override def isDeploymentReady(resource: DeploymentConfig): Boolean =
      Readiness.isDeploymentConfigReady(resource)
  }
}

class Template[F[_], T <: HasMetadata](client: OpenShiftClient, secret: Secrets[F], cfg: KrbOperatorCfg)(
  implicit F: Sync[F],
  T: Timer[F],
  resource: DeploymentResource[T]
) extends LazyLogging
    with WaitUtils {

  val adminSecretSpec: String = replaceParams(
    Paths.get(cfg.k8sSpecsDir, "krb5-admin-secret.yaml"),
    Map(PrefixParam -> cfg.k8sResourcesPrefix, AdminPwdParam -> randomPassword)
  )

  private def deploymentSpec(kdcName: String, krbRealm: String) = replaceParams(
    Paths.get(cfg.k8sSpecsDir, resource.deploymentSpecName),
    Map(
      KdcServerParam -> kdcName,
      KrbRealmParam -> krbRealm,
      Krb5Image -> cfg.krb5Image,
      PrefixParam -> cfg.k8sResourcesPrefix
    )
  )

  private def serviceSpec(kdcName: String) =
    replaceParams(Paths.get(cfg.k8sSpecsDir, "krb5-service.yaml"), Map(KdcServerParam -> kdcName))

  private def replaceParams(pathToFile: Path, params: Map[String, String]): String =
    Using.resource(
      Source
        .fromFile(pathToFile.toFile)
    ) {
      _.getLines().map { l =>
        params.view.foldLeft(l) {
          case (acc, (k, v)) =>
            acc.replaceAll("\\$\\{" + k + "\\}", v)
        }
      }.toList
        .mkString("\n")
    }

  private def randomPassword = Random.alphanumeric.take(10).mkString

  def delete(krb: Krb, meta: Metadata): F[Unit] =
    Sync[F].delay {
      val deleteDeployment = findDeployment(meta).fold(false)(d => resource.delete(client, d))
      val deleteService = findService(meta).fold(false)(client.services().inNamespace(meta.namespace).delete(_))
      val deleteAdminSecret =
        secret.findAdminSecret(meta).fold(false)(client.secrets().inNamespace(meta.namespace).delete(_))
      val found = if (deleteDeployment || deleteService || deleteAdminSecret) "yes" else "no"
      logger.info(s"Found resources to delete: $found")
    }.onError {
      case e: Throwable =>
        Sync[F].delay(logger.error("Failed to delete", e))
    }

  def waitForDeployment(metadata: Metadata): F[Unit] = {
    F.delay(logger.info(s"Going to wait for deployment until ready: $deploymentTimeout")) *>
      waitFor[F](deploymentTimeout) {
        F.delay(findDeployment(metadata).exists(resource.isDeploymentReady))
      }.flatMap { ready =>
        if (ready) {
          F.delay(logger.info(s"deployment is ready: $metadata"))
        } else F.raiseError(new RuntimeException("Failed to wait for deployment is ready"))
      }
  }

  def findDeployment(meta: Metadata): Option[T] =
    resource.findDeployment(client, meta)

  def findService(meta: Metadata): Option[Service] =
    Option(client.services().inNamespace(meta.namespace).withName(meta.name).get())

  def createService(meta: Metadata): F[Unit] =
    F.delay {
      val is = new ByteArrayInputStream(serviceSpec(meta.name).getBytes())
      val s = client.services().load(is).get()
      client.services().inNamespace(meta.namespace).createOrReplace(s)
    }.void.recoverWith {
      case e =>
        for {
          missing <- F.delay(findService(meta)).map(_.isEmpty)
          error <- F.whenA(missing)(F.raiseError(e))
        } yield error
    }

  def createDeployment(meta: Metadata, realm: String): F[Unit] =
    F.delay {
      val content = deploymentSpec(meta.name, realm)
      logger.debug(s"Creating new deployment for KDC: ${meta.name}")
      val is = new ByteArrayInputStream(content.getBytes)
      resource.createOrReplace(client, is, meta)
    }
}
