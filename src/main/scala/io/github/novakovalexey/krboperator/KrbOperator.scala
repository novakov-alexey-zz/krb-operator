package io.github.novakovalexey.krboperator

import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.openshift.client.OpenShiftClient
import io.github.novakovalexey.k8soperator4s.CrdOperator
import io.github.novakovalexey.k8soperator4s.common.{CrdConfig, Metadata}
import io.github.novakovalexey.krboperator.service.{Kadmin, SecretService, Template}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class KrbOperator(
  client: OpenShiftClient,
  cfg: CrdConfig[Krb],
  operatorCfg: KrbOperatorCfg,
  template: Template,
  kadmin: Kadmin,
  secret: SecretService
)(implicit ec: ExecutionContext)
    extends CrdOperator[Krb](cfg)
    with LazyLogging {

  override def onAdd(krb: Krb, meta: Metadata): Unit = {
    logger.info(s"add event: $krb, $meta")

    isIncomplete(meta).flatMap { yes =>
      if (yes) {
        logger.info("Creating everything from scratch")
        val r = for {
          _ <- deleteWithTemplate(krb, meta)
          _ <- createOrReplace(krb, meta)
          _ <- waitForDeployment(meta)
          p <- secret.getAdminPwd(meta)
          pod <- kadmin.initKerberos(meta, krb, p)
          _ <- copyKeytabs(meta.namespace, krb.principals, pod)
          n <- secret.createSecrets(meta.namespace, krb.principals, Kadmin.keytabToPath)
          _ = logger.info(s"$n secret(s) were created in ${meta.namespace}")
        } yield ()

        r.map(_ => logger.info(s"new instance $meta has been created"))
        r.failed.foreach(t => logger.error("Failed to create", t))
        r
      } else {
        logger.info(s"Krb instance $meta already exists, so ignoring this event")
        Future.successful(())
      }
    }
  }

  private def copyKeytabs(namespace: String, principals: List[Principal], pod: String): Future[Unit] =
    Future(principals.foreach { p =>
      val path = Kadmin.keytabToPath(p.keytab)
      client.pods
        .inNamespace(namespace)
        .withName(pod)
        .inContainer(operatorCfg.kadminContainer)
        .file(path)
        .copy(Paths.get(path))
    })

  private def waitForDeployment(metadata: Metadata): Future[Unit] = {
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

  private def isIncomplete(meta: Metadata): Future[Boolean] = Future {
    Try {
      LazyList(
        Option(findDeploymentConfig(meta).get()),
        Option(findService(meta).get()),
        Option(findImageStream(meta).get())
      ).exists(_.isEmpty)
    } match {
      case Success(b) => b
      case Failure(e) =>
        logger.error("Failed to get current deployment config, so assuming it does not exists", e)
        false
    }
  }

  private def findDeploymentConfig(meta: Metadata) =
    client.deploymentConfigs().inNamespace(meta.namespace).withName(meta.name)

  private def findService(meta: Metadata) =
    client.services().inNamespace(meta.namespace).withName(meta.name)

  private def findImageStream(meta: Metadata) =
    client.imageStreams().inNamespace(meta.namespace).withName(meta.name)

  private def createOrReplace(krb: Krb, meta: Metadata) =
    Future {
      val resources = template.resources(meta.name, krb.realm)
      client
        .resourceList(resources)
        .inNamespace(meta.namespace)
        .createOrReplaceAnd()
        .waitUntilReady(1, TimeUnit.MINUTES)

      logger.info(s"template submitted for: $krb")
      ()
    }

  override def onDelete(krb: Krb, meta: Metadata): Unit = {
    logger.info(s"delete event: $krb, $meta")
    deleteWithTemplate(krb, meta)
  }

  private def deleteWithTemplate(krb: Krb, meta: Metadata): Future[Unit] = {
    val t = Future {
      val resources = template.resources(meta.name, krb.realm)
      lazy val count = Option(resources.getItems).map(_.size()).getOrElse(0)
      logger.debug(s"resources count to delete $count")

      val deleted = client
        .resourceList(resources)
        .inNamespace(meta.namespace)
        .delete()

      logger.info(s"Found resources to delete? $deleted")
      ()
    }

    t.failed.foreach(e => logger.error("Failed to delete", e))
    t
  }
}
