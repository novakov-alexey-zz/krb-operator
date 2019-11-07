package io.github.novakovalexey.krboperator.service

import java.io.ByteArrayInputStream
import java.nio.file.{Path, Paths}
import java.util.concurrent.TimeUnit

import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model._
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
}

class Template[F[_]](client: OpenShiftClient, secret: SecretService[F], cfg: KrbOperatorCfg)(implicit F: Sync[F])
    extends LazyLogging {

  val adminSecretSpec: String = replaceParams(
    Paths.get(cfg.k8sSpecsDir, "krb5-admin-secret.yaml"),
    Map(PrefixParam -> cfg.k8sResourcesPrefix, AdminPwdParam -> randomPassword)
  )

  private def deploymentConfigSpec(kdcName: String, krbRealm: String) = replaceParams(
    Paths.get(cfg.k8sSpecsDir, "krb5-deployment-config.yaml"),
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
      val deleteDeployment = findDeploymentConfig(meta).fold(false)(d => client.deploymentConfigs().delete(d))
      val deleteService = findService(meta).fold(false)(client.services().delete(_))
      val deleteAdminSecret = secret.findAdminSecret(meta).fold(false)(client.secrets().delete(_))
      logger.info(s"Found resources to delete? ${deleteDeployment || deleteService || deleteAdminSecret}")
    }.onError {
      case e: Throwable =>
        Sync[F].delay(logger.error("Failed to delete", e))
    }

  def waitForDeployment(metadata: Metadata): F[Unit] =
    F.delay(findDeploymentConfig(metadata)).flatMap {
      case Some(d) =>
        val duration = (1, TimeUnit.MINUTES)
        logger.info(s"Going to wait for deployment until ready: $duration")
        //TODO: wait is blocking operation
        client.resource(d).waitUntilReady(duration._1, duration._2)
        logger.info(s"deployment is ready: $metadata")
        F.unit
      case None =>
        F.raiseError(new RuntimeException("Failed to get deployment config"))
    }

  def findDeploymentConfig(meta: Metadata): Option[DeploymentConfig] =
    Option(client.deploymentConfigs().inNamespace(meta.namespace).withName(meta.name).get())

  def findService(meta: Metadata): Option[Service] =
    Option(client.services().inNamespace(meta.namespace).withName(meta.name).get())

  def createService(meta: Metadata): F[Unit] =
    F.delay {
      val is = new ByteArrayInputStream(serviceSpec(meta.name).getBytes())
      val s = client.services().load(is).get()
      client.services().inNamespace(meta.namespace).createOrReplace(s)
    }

  def createDeploymentConfig(meta: Metadata, realm: String): F[Unit] =
    F.delay {
      val content = deploymentConfigSpec(meta.name, realm)
      logger.debug(s"Creating new config config for KDC: ${meta.name}")
      val is = new ByteArrayInputStream(content.getBytes)
      val dc = client.deploymentConfigs().load(is)
      client.deploymentConfigs().inNamespace(meta.namespace).createOrReplace(dc.get())
    }
}
