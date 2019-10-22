package io.github.novakovalexey.krboperator.service

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.{Secret, SecretBuilder}
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.KrbOperatorCfg
import io.github.novakovalexey.krboperator.service.SecretService._

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

object SecretService {
  val principalSecretLabel: Map[String, String] = Map("app" -> "krb")
}

class SecretService(client: KubernetesClient, cfg: KrbOperatorCfg)(implicit ec: ExecutionContext) extends LazyLogging {

  def getAdminPwd(meta: Metadata): Future[String] = {
    val f = Future {
      Option(
        client
          .secrets()
          .inNamespace(meta.namespace)
          .withName(cfg.secretNameForAdminPwd)
          .get()
      )
    }.flatMap {
      case Some(s) =>
        val pwd = Option(s.getData).flatMap(_.asScala.toMap.get(cfg.secretKeyForAdminPwd))
        pwd match {
          case Some(p) =>
            logger.info(s"Found admin password for $meta")
            val decoded = Base64.getDecoder.decode(p)
            Future.successful(new String(decoded))
          case None =>
            Future.failed(new RuntimeException("Failed to get admin password"))
        }
      case None =>
        Future.failed(new RuntimeException(s"Failed to find a secret '${cfg.secretNameForAdminPwd}'"))
    }
    f.failed.foreach(t => logger.error("Failed to get admin password", t))
    f
  }

  def createSecret(namespace: String, keytabPath: List[KeytabMeta], secretName: String): Future[Unit] =
    Future {
      logger.debug(s"Creating secret for [${keytabPath.mkString(",")}] keytabs")
      val builder = new SecretBuilder()
        .withNewMetadata()
        .withName(secretName)
        .withLabels(principalSecretLabel.asJava)
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

  def deleteSecrets(namespace: String): Future[Unit] =
    Future(client.secrets().inNamespace(namespace).withLabels(principalSecretLabel.asJava).delete())

  def findMissing(meta: Metadata, expectedSecrets: Set[String]): Future[Set[String]] = {
    logger.debug(s"Expected secrets to find: ${expectedSecrets.mkString(",")}")

    Future(Option(client.secrets().inNamespace(meta.namespace).withLabels(principalSecretLabel.asJava).list())).flatMap {
      case Some(l) =>
        val foundSecrets = Option(l.getItems).map(_.asScala).getOrElse(List.empty).map(_.getMetadata.getName).toSet
        Future.successful(expectedSecrets -- foundSecrets)
      case None =>
        Future.successful(expectedSecrets)
    }
  }

  def findAdminSecret(meta: Metadata): Option[Secret] =
    Option(client.secrets().inNamespace(meta.namespace).withName(cfg.secretNameForAdminPwd).get())

  def createAdminSecret(meta: Metadata, adminSecretSpec: String): Future[Unit] =
    Future {
      val s = client.secrets().load(new ByteArrayInputStream(adminSecretSpec.getBytes)).get()
      client.secrets().inNamespace(meta.namespace).createOrReplace(s)
    }
}
