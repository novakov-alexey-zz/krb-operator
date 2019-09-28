package io.github.novakovalexey.krboperator.service

import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ExecListener, ExecWatch, Execable}
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.service.Kadmin._
import io.github.novakovalexey.krboperator.{Krb, KrbOperatorCfg, Principal}
import okhttp3.Response

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Random

class Kadmin(client: KubernetesClient, cfg: KrbOperatorCfg)(implicit ec: ExecutionContext) extends LazyLogging {
  private val listener = new ExecListener {
    override def onOpen(response: Response): Unit =
      logger.info(s"on open: ${response.body().string()}")

    override def onFailure(t: Throwable, response: Response): Unit =
      logger.error(s"Failure on 'pod exec': ${response.body().string()}", t)

    override def onClose(code: Int, reason: String): Unit =
      logger.info(s"listener closed with code '$code', reason: $reason")
  }

  def initKerberos(meta: Metadata, krb: Krb, adminPwd: String): Future[String] =
    Future {
      client
        .pods()
        .inNamespace(meta.namespace)
        .withLabel("deploymentconfig", meta.name)
        .list()
        .getItems
        .asScala
        .headOption
    }.flatMap {
      case Some(p) =>
        Future {
          logger.debug(s"Waiting for POD in ${meta.namespace} namespace to be ready")
          //TODO: wait is blocking operation, need to write own wait function
          client.resource(p).inNamespace(meta.namespace).waitUntilReady(1, TimeUnit.MINUTES)
          logger.debug(s"POD '${p.getMetadata.getName}' is ready")
          addKeytabs(meta, krb, adminPwd, p.getMetadata.getName)
          logger.info("keytabs added")
          p.getMetadata.getName
        }
      case None =>
        logger.error(s"Failed to init Kerberos for $meta")
        Future.failed(new RuntimeException("No KDC POD found"))
    }

  private def addKeytabs(meta: Metadata, krb: Krb, adminPwd: String, podName: String): Unit = {
    val exe = client
      .pods()
      .inNamespace(meta.namespace)
      .withName(podName)
      .inContainer(cfg.kadminContainer)
      .readingInput(System.in)
      .writingOutput(System.out)
      .writingError(System.err)
      .withTTY()
      .usingListener(listener)

    krb.principals.map { p =>
      createPrincipal(krb, adminPwd, exe, p)
      createKeytab(krb, adminPwd, exe, p)
    }
    ()
  }

  private def createKeytab(krb: Krb, adminPwd: String, exe: Execable[String, ExecWatch], p: Principal) = {
    val path = keytabToPath(p.keytab)
    val keytabCmd = cfg.addKeytabCmd
      .replaceAll("\\$realm", krb.realm)
      .replaceAll("\\$path", path)
      .replaceAll("\\$username", p.name)
    val addKeytab = s"echo '$adminPwd' | $keytabCmd"
    exe.exec("bash", "-c", addKeytab)
  }

  private def createPrincipal(krb: Krb, adminPwd: String, exe: Execable[String, ExecWatch], p: Principal) = {
    val addCmd = cfg.addPrincipalCmd
      .replaceAll("\\$realm", krb.realm)
      .replaceAll("\\$username", p.name)
      .replaceAll("\\$password", if (p.password == "random") randomString else p.value)
    val addPrincipal = s"echo '$adminPwd' | $addCmd"
    exe.exec("bash", "-c", addPrincipal)
  }

  private def randomString =
    Random.alphanumeric.take(10).mkString
}

object Kadmin {
  def keytabToPath(k: String): String =
    s"/tmp/$k"
}
