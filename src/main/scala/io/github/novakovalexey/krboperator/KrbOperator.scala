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

    exists(meta).map { e =>
      if (!e) {
        val r = for {
          _ <- createOrReplace(krb, meta)
          _ <- waitForDeployment(meta)
          p <- secret.getAdminPwd(meta)
          pod <- kadmin.initKerberos(meta, krb, p)
          _ <- copyKeytabs(meta.namespace, krb.principals, pod)
          _ <- secret.createSecrets(meta.namespace, krb.principals, Kadmin.keytabToPath)
        } yield ()

        r match {
          case Right(_) => logger.info(s"new instance $meta has been created")
          case Left(e) =>
            logger.error("Failed to create", e)
            throw e
        }
      } else {
        logger.info(s"Krb instance $meta already exists, so ignoring this event")
        ()
      }
    }
  }

  private def copyKeytabs(namespace: String, principals: List[Principal], pod: String): Either[Throwable, Unit] =
    Try(
      principals
        .foreach(p => {
          val path = Kadmin.keytabToPath(p.keytab)
          client.pods
            .inNamespace(namespace)
            .withName(pod)
            .inContainer("kadmin")
            .file(path)
            .copy(Paths.get(path))
        })
    ).toEither

  private def waitForDeployment(metadata: Metadata): Either[Throwable, Unit] =
    Try {
      val deployment = getDeploymentConfig(metadata).get()
      client.resource(deployment).waitUntilReady(60, TimeUnit.SECONDS)
    }.toEither.map { _ =>
      logger.info(s"deployment is ready: $metadata")
      ()
    }.left.map(e => new RuntimeException(s"Failed to wait for deployment: $metadata", e))

  private def exists(meta: Metadata): Future[Boolean] = Future {
    Try {
      Option(getDeploymentConfig(meta).get())
    } match {
      case Success(Some(_)) => true
      case Success(None) => false
      case Failure(e) =>
        logger.error("Failed to get current deployment config, so assuming it does not exists", e)
        false
    }
  }

  private def getDeploymentConfig(meta: Metadata) =
    client.deploymentConfigs().inNamespace(meta.namespace).withName(meta.name)

  private def createOrReplace(krb: Krb, meta: Metadata) =
    Try {
      val resources = template.resources(meta.name, krb.realm)
      client
        .resourceList(resources)
        .inNamespace(meta.namespace)
        .createOrReplaceAnd()
        .waitUntilReady(60, TimeUnit.SECONDS)

      logger.info(s"template submitted for: $krb")
      ()
    }.toEither

  override def onDelete(krb: Krb, meta: Metadata): Unit = {
    logger.info(s"delete event: $krb, $meta")
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
  }
}
