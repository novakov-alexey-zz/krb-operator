package io.github.novakovalexey.krboperator.service

import java.io.File
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.KubernetesList
import io.fabric8.openshift.client.OpenShiftClient
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.{Krb, KrbOperatorCfg}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

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
    val t = Future {
      val resources = resourceList(meta.name, krb.realm)
      lazy val count = Option(resources.getItems).map(_.size()).getOrElse(0)
      logger.info(s"number of resources to delete: $count")

      val deleteByTemplate = client
        .resourceList(resources)
        .inNamespace(meta.namespace)
        .delete()

      val deleteDeployment = findDeploymentConfig(meta).delete()
      val deleteService = findService(meta).delete()
      val deleteImageStream = findImageStream(meta).delete()

      logger.info(
        s"Found resources to delete? ${deleteByTemplate || deleteDeployment || deleteService || deleteImageStream}"
      )
      ()
    }

    t.failed.foreach(e => logger.error("Failed to delete", e))
    t
  }

  def waitForDeployment(metadata: Metadata): Future[Unit] = {
    val f = Future {
      val deployment = findDeploymentConfig(metadata).get()
      val duration = (1, TimeUnit.MINUTES)
      logger.info(s"Going to wait for deployment until ready: $duration")
      client.resource(deployment).waitUntilReady(duration._1, duration._2)
      logger.info(s"deployment is ready: $metadata")
    }
    f.failed.foreach(e => new RuntimeException(s"Failed to wait for deployment: $metadata", e))
    f
  }

  def isIncomplete(meta: Metadata): Future[Boolean] = Future {
    Try {
      LazyList(
        Option(findDeploymentConfig(meta).get()),
        Option(findService(meta).get()),
        Option(findImageStream(meta).get())
      ).exists(_.isEmpty)
    } match {
      case Success(b) => b
      case Failure(e) =>
        logger.error("Failed to get current deployment config, so assuming it does not exist", e)
        false
    }
  }

  private def findDeploymentConfig(meta: Metadata) =
    client.deploymentConfigs().inNamespace(meta.namespace).withName(meta.name)

  private def findService(meta: Metadata) =
    client.services().inNamespace(meta.namespace).withName(meta.name)

  private def findImageStream(meta: Metadata) =
    client.imageStreams().inNamespace(meta.namespace).withName(meta.name)
}
