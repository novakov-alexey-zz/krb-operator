package io.github.novakovalexey.krboperator.service

import java.io.ByteArrayInputStream
import java.nio.file.{Path, Paths}
import java.util.concurrent.TimeUnit

import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.api.model.apps.Deployment
import io.fabric8.openshift.api.model.DeploymentConfig
import io.fabric8.openshift.client.OpenShiftClient
import io.github.novakovalexey.k8soperator.Metadata
import io.github.novakovalexey.krboperator.service.Template._
import io.github.novakovalexey.krboperator.{Krb, KrbOperatorCfg}

import scala.io.Source
import scala.util.{Random, Using}

object Template {
  val PrefixParam = "PREFIX"
  val AdminPwdParam = "ADMIN_PWD"
  val KdcServerParam = "KDC_SERVER"
  val KrbRealmParam = "KRB5_REALM"
  val Krb5Image = "KRB5_IMAGE"
  val DeploymentSelector = "deploymentconfig"
}

trait DeploymentResource[T] {
  def delete(client: OpenShiftClient, resource: T): Boolean

  def findDeployment(client: OpenShiftClient, meta: Metadata): Option[T]

  def createOrReplace(client: OpenShiftClient, is: ByteArrayInputStream, meta: Metadata): T

  val deploymentSpecName: String
}

object DeploymentResource {

  implicit val deployment: DeploymentResource[Deployment] = new DeploymentResource[Deployment] {
    override def delete(client: OpenShiftClient, d: Deployment): Boolean =
      client.apps().deployments().delete(d)

    override def findDeployment(client: OpenShiftClient, meta: Metadata): Option[Deployment] =
      Option(client.apps().deployments().inNamespace(meta.namespace).withName(meta.name).get())

    override def createOrReplace(client: OpenShiftClient, is: ByteArrayInputStream, meta: Metadata): Deployment = {
      val dc = client.apps().deployments().load(is)
      client.apps().deployments().inNamespace(meta.namespace).createOrReplace(dc.get())
    }

    override val deploymentSpecName: String = "krb5-deployment.yaml"
  }

  implicit val deploymentConfig: DeploymentResource[DeploymentConfig] = new DeploymentResource[DeploymentConfig] {
    override def delete(client: OpenShiftClient, d: DeploymentConfig): Boolean =
      client.deploymentConfigs().delete(d)

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
  }
}

class Template[F[_], T <: HasMetadata](client: OpenShiftClient, secret: SecretService[F], cfg: KrbOperatorCfg)(
  implicit F: Sync[F],
  D: DeploymentResource[T]
) extends LazyLogging {

  val adminSecretSpec: String = replaceParams(
    Paths.get(cfg.k8sSpecsDir, "krb5-admin-secret.yaml"),
    Map(PrefixParam -> cfg.k8sResourcesPrefix, AdminPwdParam -> randomPassword)
  )

  private def deploymentSpec(kdcName: String, krbRealm: String) = replaceParams(
    Paths.get(cfg.k8sSpecsDir, D.deploymentSpecName),
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
      val deleteDeployment = findDeployment(meta).fold(false)(d => D.delete(client, d))
      val deleteService = findService(meta).fold(false)(client.services().delete(_))
      val deleteAdminSecret = secret.findAdminSecret(meta).fold(false)(client.secrets().delete(_))
      logger.info(s"Found resources to delete? ${deleteDeployment || deleteService || deleteAdminSecret}")
    }.onError {
      case e: Throwable =>
        Sync[F].delay(logger.error("Failed to delete", e))
    }

  def waitForDeployment(metadata: Metadata): F[Unit] =
    F.delay(findDeployment(metadata)).flatMap {
      case Some(d) =>
        val duration = (1, TimeUnit.MINUTES)
        logger.info(s"Going to wait for deployment until ready: $duration")
        //TODO: wait is blocking operation
        client.resource(d).waitUntilReady(duration._1, duration._2)
        logger.info(s"deployment is ready: $metadata")
        F.unit
      case None =>
        F.raiseError(new RuntimeException("Failed to get deployment"))
    }

  def findDeployment(meta: Metadata): Option[T] =
    D.findDeployment(client, meta)

  def findService(meta: Metadata): Option[Service] =
    Option(client.services().inNamespace(meta.namespace).withName(meta.name).get())

  def createService(meta: Metadata): F[Unit] =
    F.delay {
      val is = new ByteArrayInputStream(serviceSpec(meta.name).getBytes())
      val s = client.services().load(is).get()
      client.services().inNamespace(meta.namespace).createOrReplace(s)
    }

  def createDeployment(meta: Metadata, realm: String): F[Unit] =
    F.delay {
      val content = deploymentSpec(meta.name, realm)
      logger.debug(s"Creating new deployment for KDC: ${meta.name}")
      val is = new ByteArrayInputStream(content.getBytes)
      D.createOrReplace(client, is, meta)
    }
}
