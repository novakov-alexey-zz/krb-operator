package io.github.novakovalexey.krboperator

import java.util.Base64
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.dsl.ExecListener
import io.fabric8.openshift.client.OpenShiftClient
import io.github.novakovalexey.k8soperator4s.CrdOperator
import io.github.novakovalexey.k8soperator4s.common.{CrdConfig, Metadata}
import io.github.novakovalexey.krboperator.KrbOperator._
import okhttp3.Response

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

object KrbOperator {
  private val Prefix = "operator"
}

class KrbOperator(client: OpenShiftClient, cfg: CrdConfig[Krb], operatorCfg: KrbOperatorCfg, template: Template)(
  implicit ec: ExecutionContext
) extends CrdOperator[Krb](cfg)
    with LazyLogging {

  private val listener = new ExecListener {
    override def onOpen(response: Response): Unit =
      logger.info(s"on open: ${response.body().string()}")

    override def onFailure(t: Throwable, response: Response): Unit =
      logger.error(s"Failure on pod exec: ${response.body().string()}", t)

    override def onClose(code: Int, reason: String): Unit =
      logger.info(s"listener closed with code '$code', reason: $reason")
  }

  override def onAdd(krb: Krb, meta: Metadata): Unit = {
    logger.info(s"add event: $krb, $meta")

    exists(meta).map { e =>
      if (!e) {
        val r = for {
          _ <- createOrReplace(krb, meta)
          _ <- waitForDeployment(meta)
          p <- getAdminPwd(meta)
          _ <- initKerberos(meta, krb, p)
        } yield ()

        r match {
          case Right(()) => logger.info(s"new instance $meta has been created")
          case Left(e) =>
            logger.error("Failed to create", e)
            throw e
        }
      } else {
        logger.info(s"Krb instance $meta already exists so ignoring this event")
        ()
      }
    }
  }

  private def getAdminPwd(meta: Metadata): Either[Throwable, String] = {
    val secret = s"$Prefix-krb-admin-secret"
    Try(Option(client.secrets().inNamespace(meta.namespace).withName(secret).get())).toEither match {
      case Right(Some(s)) =>
        val pwd = Option(s.getData).flatMap(_.asScala.toMap.get("krb5_pass"))
        pwd match {
          case Some(p) =>
            logger.info(s"Found admin password for $meta")
            val decoded = Base64.getDecoder.decode(p)
            Right[Throwable, String](new String(decoded))
          case None =>
            Left(new RuntimeException("Failed to get admin password"))
        }

      case Right(None) =>
        Left(new RuntimeException(s"Failed to find a secret '$secret'"))

      case Left(e) => Left(e)
    }
  }

  private def waitForDeployment(metadata: Metadata): Either[Throwable, Unit] =
    Try {
      val deployment = getDeploymentConfig(metadata).get()
      client.resource(deployment).waitUntilReady(60, TimeUnit.SECONDS)
    }.toEither.map { _ =>
      logger.info(s"deployment is ready: $metadata")
      ()
    }.left.map(e => new RuntimeException(s"Failed to wait for deployment: $metadata", e))

  private def initKerberos(meta: Metadata, krb: Krb, adminPwd: String): Either[Throwable, Unit] = {
    val pod = Try {
      client
        .pods()
        .inNamespace(meta.namespace)
        .withLabel("deploymentconfig", meta.name)
        .list()
        .getItems
        .asScala
        .headOption
    }

    pod.flatMap {
      case Some(p) =>
        client.resource(p).inNamespace(meta.namespace).waitUntilReady(60, TimeUnit.SECONDS)
        logger.debug(s"POD '${p.getMetadata.getName}' is ready")

        addKeytabs(meta, krb, adminPwd, p.getMetadata.getName)
        logger.info(s"Kerberos has been initialized successfully. $krb")
        Success(())
      case None =>
        Failure(new RuntimeException("No KDC POD found"))
    }.toEither
  }

  private def addKeytabs(meta: Metadata, krb: Krb, adminPwd: String, podName: String): Unit = {
    val exe = client
      .pods()
      .inNamespace(meta.namespace)
      .withName(podName)
      .inContainer("kadmin")
      .readingInput(System.in)
      .writingOutput(System.out)
      .writingError(System.err)
      .withTTY()
      .usingListener(listener)

    krb.principals.foreach { p =>
      val addPrincipal =
        s"""echo '$adminPwd' | ${Kadmin.addPrincipal(krb.realm, p.name, p.value)}"""
      exe.exec("bash", "-c", addPrincipal)

      val addKeytab =
        s"""echo '$adminPwd' | ${Kadmin.addKeytab(krb.realm, p.name)}"""
      exe.exec("bash", "-c", addKeytab)
    }
  }

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

object Kadmin {
  def addPrincipal(realm: String, username: String, password: String): String =
    s"""kadmin -r $realm -p admin/admin@$realm -q \"addprinc -pw $password -requires_preauth $username@$realm\""""

  def addKeytab(realm: String, username: String): String =
    s"""kadmin -r $realm -p admin/admin@$realm -q \"ktadd -kt /tmp/$username.keytab $username@$realm\""""
}
