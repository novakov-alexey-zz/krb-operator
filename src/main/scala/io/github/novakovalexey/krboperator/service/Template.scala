package io.github.novakovalexey.krboperator.service

import java.io.File
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.client.dsl.{Resource, ServiceResource}
import io.fabric8.openshift.api.model.{DeploymentConfig, DoneableDeploymentConfig}
import io.fabric8.openshift.client.OpenShiftClient
import io.fabric8.openshift.client.dsl.DeployableScalableResource
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.{Krb, KrbOperatorCfg}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Try

class Template(client: OpenShiftClient, operatorCfg: KrbOperatorCfg)(implicit ec: ExecutionContext)
    extends LazyLogging {

  private val template = new File(operatorCfg.templatePath)

  private def params(kdcName: String, realm: String) = Map(
    "KRB5_IMAGE" -> operatorCfg.krb5Image,
    "PREFIX" -> operatorCfg.k8sResourcesPrefix,
    "KDC_SERVER" -> kdcName,
    "KRB5_REALM" -> realm
  )

  def resourceList(kdcName: String, realm: String): KubernetesList = {
    client.templates
      .load(template)
      .process(params(kdcName, realm).asJava)
  }

  def createOrReplace(krb: Krb, meta: Metadata) =
    Future {
      val resources = resourceList(meta.name, krb.realm)
      client
        .resourceList(resources)
        .inNamespace(meta.namespace)
        .createOrReplaceAnd()
        .waitUntilReady(1, TimeUnit.MINUTES)

      logger.info(s"template submitted for: $krb")
      ()
    }

  def delete(krb: Krb, meta: Metadata): Future[Unit] = {
    val f = Future {
      val resources = resourceList(meta.name, krb.realm)
      lazy val count = Option(resources.getItems).map(_.size()).getOrElse(0)
      logger.info(s"number of resources to delete: $count")

      val deleteByTemplate = client
        .resourceList(resources)
        .inNamespace(meta.namespace)
        .delete()

      val deleteDeployment = findDeploymentConfig(meta).fold(false)(_.delete())
      val deleteService: Boolean = findService(meta).fold(false)(_.delete())
      //val deleteImageStream: lang.Boolean = findImageStream(meta).delete()

      logger.info(s"Found resources to delete? ${deleteByTemplate || deleteDeployment || deleteService}")
      ()
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
    Try(client.secrets().inNamespace(meta.namespace).withName(operatorCfg.secretForAdminPwd)).toOption

  def createService(meta: Metadata): Future[Unit] = ???

  def createDeploymentConfig(meta: Metadata): Future[Unit] = ???

  def createAdminSecret(meta: Metadata): Future[Unit] = ???
}
