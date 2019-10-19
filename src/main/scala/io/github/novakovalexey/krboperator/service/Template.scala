package io.github.novakovalexey.krboperator.service

import java.io.ByteArrayInputStream
import java.nio.file.{Path, Paths}
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.dsl.{Resource, ServiceResource}
import io.fabric8.openshift.api.model.{DeploymentConfig, DoneableDeploymentConfig}
import io.fabric8.openshift.client.OpenShiftClient
import io.fabric8.openshift.client.dsl.DeployableScalableResource
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.service.Template._
import io.github.novakovalexey.krboperator.{Krb, KrbOperatorCfg}

import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Random, Try, Using}

object Template {
  val PrefixParam = "PREFIX"
  val AdminPwdParam = "ADMIN_PWD"
  val KdcServerParam = "KDC_SERVER"
  val KrbRealmParam = "KRB5_REALM"
  val Krb5Image = "KRB5_IMAGE"
}

class Template(client: OpenShiftClient, cfg: KrbOperatorCfg)(implicit ec: ExecutionContext) extends LazyLogging {

  private val adminSecretSpec = replaceParams(
    Paths.get(cfg.k8sSpecsDir, "krb5-admin-secret.yaml"),
    Map(PrefixParam -> cfg.k8sResourcesPrefix, AdminPwdParam -> randomPassword)
  )

  private def deploymentConfigSpec(kdcName: String, krbRealm: String) = replaceParams(
    Paths.get(cfg.k8sSpecsDir, "krb5-deployment-config.yaml"),
    Map(KdcServerParam -> kdcName, KrbRealmParam -> krbRealm, Krb5Image -> cfg.krb5Image)
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

  def delete(krb: Krb, meta: Metadata): Future[Unit] = {
    val f = Future {
      val deleteDeployment = findDeploymentConfig(meta).fold(false)(_.delete())
      val deleteService = findService(meta).fold(false)(_.delete())
      val deleteAdminSecret = findAdminSecret(meta).fold(false)(_.delete())
      logger.info(s"Found resources to delete? ${deleteDeployment || deleteService || deleteAdminSecret}")
    }
    f.failed.foreach(e => logger.error("Failed to delete", e))
    f
  }

  def waitForDeployment(metadata: Metadata): Future[Unit] = {
    val f = Future(findDeploymentConfig(metadata)).flatMap {
      case Some(d) =>
        val duration = (1, TimeUnit.MINUTES)
        logger.info(s"Going to wait for deployment until ready: $duration")
        client.resource(d.get()).waitUntilReady(duration._1, duration._2)
        logger.info(s"deployment is ready: $metadata")
        Future.successful(())
      case None =>
        Future.failed(new RuntimeException("Failed to get deployment config"))
    }
    f.failed.map(e => new RuntimeException(s"Failed to wait for deployment: $metadata", e))
  }

  def findDeploymentConfig(
    meta: Metadata
  ): Option[DeployableScalableResource[DeploymentConfig, DoneableDeploymentConfig]] =
    Try(client.deploymentConfigs().inNamespace(meta.namespace).withName(meta.name)).toOption

  def findService(meta: Metadata): Option[ServiceResource[Service, DoneableService]] =
    Try(client.services().inNamespace(meta.namespace).withName(meta.name)).toOption

  def findAdminSecret(meta: Metadata): Option[Resource[Secret, DoneableSecret]] =
    Try(client.secrets().inNamespace(meta.namespace).withName(cfg.secretNameForAdminPwd)).toOption

  def createService(kdcName: String): Future[Unit] =
    Future {
      val is = new ByteArrayInputStream(serviceSpec(kdcName).getBytes())
      val s = client.services().load(is).get()
      client.services().createOrReplace(s)
    }

  def createDeploymentConfig(kdcName: String, realm: String): Future[Unit] =
    Future {
      val is = new ByteArrayInputStream(deploymentConfigSpec(kdcName, realm).getBytes)
      val dc = client.deploymentConfigs().load(is).get()
      client.deploymentConfigs().createOrReplace(dc)
    }

  def createAdminSecret(meta: Metadata): Future[Unit] =
    Future {
      val s = client.secrets().load(new ByteArrayInputStream(adminSecretSpec.getBytes)).get()
      client.secrets().inNamespace(meta.namespace).createOrReplace(s)
    }
}
