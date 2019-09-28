package io.github.novakovalexey.krboperator.service

import java.nio.file.{Files, Path, Paths}
import java.util.Base64

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.{KrbOperatorCfg, Principal}

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
          .withName(operatorCfg.secretForAdminPwd)
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
        Future.failed(new RuntimeException(s"Failed to find a secret '${operatorCfg.secretForAdminPwd}'"))
    }
    f.failed.foreach(t => logger.error("Failed to get admin password", t))
    f
  }

  def createSecrets(namespace: String, principals: List[Principal], keytabToPath: String => String): Future[Int] =
    Future {
      val secretToKeytabs = principals
        .groupBy(_.secret)
        .view
        .mapValues(_.map(_.keytab).toSet)
        .toMap

      secretToKeytabs.foldLeft(Right(0): Either[Throwable, Int]) {
        case (acc, (secret, keytabs)) =>
          replaceOrCreateSecret(namespace, secret, keytabs.map(p => (p, Paths.get(keytabToPath(p)))))
            .flatMap(_ => acc.map(_ + 1))
      }
    }.flatMap(r => Future.fromTry(r.toTry))

  private def replaceOrCreateSecret(
    namespace: String,
    secretName: String,
    keytabs: Set[(String, Path)],
  ): Either[Throwable, Unit] = {
    Try {
      val builder = new SecretBuilder()
        .withNewMetadata()
        .withName(secretName)
        .endMetadata()
        .withType("opaque")

      val secret = keytabs
        .foldLeft(builder) {
          case (acc, (key, path)) =>
            val byteArray = Files.readAllBytes(path)
            acc.addToData(key, Base64.getEncoder.encodeToString(byteArray))

        }
        .build()
      client.secrets().inNamespace(namespace).createOrReplace(secret)
      logger.info(s"Secret $secretName has been created in $namespace")
    }.toEither
  }
}
