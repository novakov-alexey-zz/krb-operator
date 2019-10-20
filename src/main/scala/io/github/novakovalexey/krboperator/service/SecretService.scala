package io.github.novakovalexey.krboperator.service

import java.nio.file.Files
import java.util.Base64

import cats.syntax.option._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.KrbOperatorCfg

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Try

class SecretService(client: KubernetesClient, operatorCfg: KrbOperatorCfg)(implicit ec: ExecutionContext)
    extends LazyLogging {

  def getAdminPwd(meta: Metadata): Future[String] = {
    val f = Future {
      Option(
        client
          .secrets()
          .inNamespace(meta.namespace)
          .withName(operatorCfg.secretNameForAdminPwd)
          .get()
      )
    }.flatMap {
      case Some(s) =>
        val pwd = Option(s.getData).flatMap(_.asScala.toMap.get(operatorCfg.secretKeyForAdminPwd))
        pwd match {
          case Some(p) =>
            logger.info(s"Found admin password for $meta")
            val decoded = Base64.getDecoder.decode(p)
            Future.successful(new String(decoded))
          case None =>
            Future.failed(new RuntimeException("Failed to get admin password"))
        }
      case None =>
        Future.failed(new RuntimeException(s"Failed to find a secret '${operatorCfg.secretNameForAdminPwd}'"))
    }
    f.failed.foreach(t => logger.error("Failed to get admin password", t))
    f
  }

  def createSecret(namespace: String, keytabPath: List[KeytabMeta], secretName: String): Future[Unit] =
    Future {
      logger.debug(s"Creating secret for [$keytabPath] keytabs")
      val builder = new SecretBuilder()
        .withNewMetadata()
        .withName(secretName)
        .endMetadata()
        .withType("opaque")

      val secret = keytabPath
        .foldLeft(builder) {
          case (acc, keytab) =>
            val bytes = Files.readAllBytes(keytab.path)
            acc.addToData(keytab.name, Base64.getEncoder.encodeToString(bytes))
        }
        .build()
      client.secrets().inNamespace(namespace).createOrReplace(secret)
      logger.info(s"Secret $secretName has been created in $namespace")
    }

  def findMissing(meta: Metadata, secretNames: Set[String]): Future[Set[String]] = {
    val secrets = secretNames.map { name =>
      Future(
        Option(client.secrets().inNamespace(meta.namespace).withName(name).get())
          .fold(name.some)(_ => None)
      )
    }
    Future.sequence(secrets).map(_.flatten)
  }
}
