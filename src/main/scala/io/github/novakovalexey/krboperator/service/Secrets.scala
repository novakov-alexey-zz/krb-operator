package io.github.novakovalexey.krboperator.service

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.Base64

import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.models.Metadata
import io.fabric8.kubernetes.api.model.{Secret, SecretBuilder}
import io.fabric8.kubernetes.client.KubernetesClient
import io.github.novakovalexey.krboperator.{KeytabAndPassword, KrbOperatorCfg}
import io.github.novakovalexey.krboperator.service.Secrets._
import io.github.novakovalexey.krboperator.Utils._

import scala.jdk.CollectionConverters._

object Secrets {
  val principalSecretLabel: Map[String, String] = Map("app" -> "krb")
}

class Secrets[F[_]](client: KubernetesClient, cfg: KrbOperatorCfg)(implicit F: Sync[F]) extends LazyLogging {
  private val debug = logDebugWithNamespace(logger)
  private val info = logInfoWithNamespace(logger)
  private val error = logErrorWithNamespace(logger)

  def getAdminPwd(meta: Metadata): F[String] =
    F.delay {
      Option(
        client
          .secrets()
          .inNamespace(meta.namespace)
          .withName(cfg.adminPwd.secretName)
          .get()
      )
    }.flatMap {
      case Some(s) =>
        val pwd = Option(s.getData).flatMap(_.asScala.toMap.get(cfg.adminPwd.secretKey))
        pwd match {
          case Some(p) =>
            info(meta.namespace, s"Found admin password for $meta")
            val decoded = Base64.getDecoder.decode(p)
            F.pure(new String(decoded))
          case None =>
            F.raiseError[String](
              new RuntimeException(s"Failed to get an admin password at key: ${cfg.adminPwd.secretKey}")
            )
        }
      case None =>
        F.raiseError[String](new RuntimeException(s"Failed to find a secret '${cfg.adminPwd.secretName}'"))
    }.onError {
      case t: Throwable =>
        F.delay(error(meta.namespace, "Failed to get an admin password", t))
    }

  def create(namespace: String, principals: List[PrincipalsWithKey], secretName: String): F[Unit] =
    F.delay {
      val keytabs = principals.map(_.keytabMeta)
      debug(namespace, s"Creating secret for [${keytabs.mkString(",")}] keytabs")
      val builder = new SecretBuilder()
        .withNewMetadata()
        .withName(secretName)
        .withLabels(principalSecretLabel.asJava)
        .endMetadata()
        .withType("opaque")

      val secret = principals
        .foldLeft(builder) {
          case (acc, principals) =>
            val bytes = Files.readAllBytes(principals.keytabMeta.path)
            acc.addToData(principals.keytabMeta.name, Base64.getEncoder.encodeToString(bytes))

            val credentialsWithPassword = principals.credentials
              .filter(_.secret match {
                case KeytabAndPassword(_) => true
                case _ => false
              })

            credentialsWithPassword
              .foldLeft(builder) {
                case (acc, c) =>
                  acc.addToData(c.username, Base64.getEncoder.encodeToString(c.password.getBytes()))
              }
        }
        .build()
      client.secrets().inNamespace(namespace).createOrReplace(secret)
    }

  def delete(namespace: String): F[Unit] =
    F.delay(client.secrets().inNamespace(namespace).withLabels(principalSecretLabel.asJava).delete())

  def findMissing(meta: Metadata, expectedSecrets: Set[String]): F[Set[String]] = {
    debug(meta.namespace, s"Expected secrets to find: ${expectedSecrets.mkString(",")}")

    F.delay(Option(client.secrets().inNamespace(meta.namespace).withLabels(principalSecretLabel.asJava).list()))
      .flatMap {
        case Some(l) =>
          val foundSecrets = Option(l.getItems).map(_.asScala).getOrElse(List.empty).map(_.getMetadata.getName).toSet
          F.pure(expectedSecrets -- foundSecrets)
        case None =>
          F.pure(expectedSecrets)
      }
  }

  def findAdminSecret(meta: Metadata): Option[Secret] =
    Option(client.secrets().inNamespace(meta.namespace).withName(cfg.adminPwd.secretName).get())

  def createAdminSecret(meta: Metadata, adminSecretSpec: String): F[Unit] =
    F.delay {
      val s = client.secrets().load(new ByteArrayInputStream(adminSecretSpec.getBytes)).get()
      client.secrets().inNamespace(meta.namespace).createOrReplace(s)
    }
}
