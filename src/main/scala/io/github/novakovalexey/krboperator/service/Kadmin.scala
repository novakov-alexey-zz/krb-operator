package io.github.novakovalexey.krboperator.service

import java.nio.file.{Path, Paths}
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.ExecListener
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.Krb
import okhttp3.Response
import Kadmin._

import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

class Kadmin(client: KubernetesClient) extends LazyLogging {
  private val listener = new ExecListener {
    override def onOpen(response: Response): Unit =
      logger.info(s"on open: ${response.body().string()}")

    override def onFailure(t: Throwable, response: Response): Unit =
      logger.error(s"Failure on pod exec: ${response.body().string()}", t)

    override def onClose(code: Int, reason: String): Unit =
      logger.info(s"listener closed with code '$code', reason: $reason")
  }

  def initKerberos(meta: Metadata, krb: Krb, adminPwd: String): Either[Throwable, String] = {
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
        Success(p.getMetadata.getName)
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
