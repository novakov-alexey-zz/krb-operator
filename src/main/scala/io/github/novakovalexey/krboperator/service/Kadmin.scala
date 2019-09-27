package io.github.novakovalexey.krboperator.service

import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.ExecListener
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.Krb
import io.github.novakovalexey.krboperator.service.Kadmin._
import okhttp3.Response

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class Kadmin(client: KubernetesClient)(implicit ec: ExecutionContext) extends LazyLogging {
  private val listener = new ExecListener {
    override def onOpen(response: Response): Unit =
      logger.info(s"on open: ${response.body().string()}")

    override def onFailure(t: Throwable, response: Response): Unit =
      logger.error(s"Failure on pod exec: ${response.body().string()}", t)

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
          client.resource(p).inNamespace(meta.namespace).waitUntilReady(60, TimeUnit.SECONDS)
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
      .inContainer("kadmin")
      .readingInput(System.in)
      .writingOutput(System.out)
      .writingError(System.err)
      .withTTY()
      .usingListener(listener)

    krb.principals.map { p =>
      val addPrincipal = s"""echo '$adminPwd' | ${addprinc(krb.realm, p.name, p.value)}"""
      exe.exec("bash", "-c", addPrincipal)

      val path = keytabToPath(p.keytab)
      val addKeytab = s"""echo '$adminPwd' | ${ktadd(krb.realm, p.name, path)}"""
      exe.exec("bash", "-c", addKeytab)
    }
    ()
  }
}

object Kadmin {
  def keytabToPath(k: String): String =
    s"/tmp/$k"

  def addprinc(realm: String, username: String, password: String): String =
    s"""kadmin -r $realm -p admin/admin@$realm -q \"addprinc -pw $password -clearpolicy -requires_preauth $username@$realm\""""

  def ktadd(realm: String, username: String, path: String): String =
    s"""kadmin -r $realm -p admin/admin@$realm -q \"ktadd -kt $path $username@$realm\""""
}
