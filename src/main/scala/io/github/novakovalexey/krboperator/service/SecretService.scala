package io.github.novakovalexey.krboperator.service

import java.nio.file.{Files, Path, Paths}
import java.util.Base64

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.{KrbOperatorCfg, Principal}

import scala.jdk.CollectionConverters._
import scala.util.Try

class SecretService(client: KubernetesClient, operatorCfg: KrbOperatorCfg) extends LazyLogging {

  def getAdminPwd(meta: Metadata): Either[Throwable, String] = {
    Try {
      Option(
        client
          .secrets()
          .inNamespace(meta.namespace)
          .withName(operatorCfg.secretForAdminPwd)
          .get()
      )
    }.toEither match {
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
        Left(new RuntimeException(s"Failed to find a secret '${operatorCfg.secretForAdminPwd}'"))

      case Left(e) => Left(e)
    }
  }

  def createSecrets(
    namespace: String,
    principals: List[Principal],
    keytabToPath: String => String
  ): Either[Throwable, Int] = {
    val secretToKeytabs = principals.groupBy(_.secret).view.mapValues(_.map(_.keytab).toSet).toMap

    secretToKeytabs.foldLeft(Right(0): Either[Throwable, Int]) {
      case (acc, (secret, keytabs)) =>
        replaceOrCreateSecret(namespace, secret, keytabs.map(p => (p, Paths.get(keytabToPath(p)))))
          .flatMap(_ => acc.map(_ + 1))
    }
  }

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
      logger.info(s"Secret $secret has been created")
    }.toEither
  }
}
