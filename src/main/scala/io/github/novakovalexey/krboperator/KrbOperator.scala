package io.github.novakovalexey.krboperator

import java.nio.file.Path

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.openshift.client.OpenShiftClient
import io.github.novakovalexey.k8soperator4s.CrdOperator
import io.github.novakovalexey.k8soperator4s.common.{CrdConfig, Metadata}
import io.github.novakovalexey.krboperator.service._

import scala.concurrent.{ExecutionContext, Future}

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

    for {
      _ <- template.findService(meta) match {
        case Some(_) =>
          logger.info(s"[${meta.name}] Service is found, so skipping its creation")
          Future.successful(())
        case None =>
          for {
            _ <- template.createService(meta)
            _ = logger.info(s"Service ${meta.name} created")
          } yield ()
      }
      _ <- secret.findAdminSecret(meta) match {
        case Some(_) =>
          logger.info(s"[${meta.name}] Admin Secret is found, so skipping its creation")
          Future.successful(())
        case None =>
          for {
            _ <- secret.createAdminSecret(meta, template.adminSecretSpec)
            _ = logger.info(s"Admin secret ${meta.name} created")
          } yield ()
      }
      _ <- template.findDeploymentConfig(meta) match {
        case Some(_) =>
          logger.info(s"[${meta.name}] Deployment is found, so skipping its creation")
          Future.successful(())
        case None =>
          for {
            _ <- template.createDeploymentConfig(meta, krb.realm)
            _ <- template.waitForDeployment(meta)
            _ = logger.info(s"deployment ${meta.name} created")
          } yield ()
      }

      missingSecrets <- secret.findMissing(meta, krb.principals.map(_.secret).toSet)
      _ <- {
        logger.info(s"There are ${missingSecrets.size} missing secrets")

        lazy val adminPwd = secret.getAdminPwd(meta)
        val r = missingSecrets.map(s => (s, krb.principals.filter(_.secret == s))).map {
          case (secretName, ps) =>
            for {
              pwd <- adminPwd
              state <- kadmin.createPrincipalsAndKeytabs(ps, KadminContext(krb.realm, meta, pwd, secretName))
              statuses <- copyKeytabs(meta.namespace, state)
              _ <- if (statuses.forall(_._2 == true))
                Future.successful(())
              else
                Future.failed(
                  new RuntimeException(s"Failed to upload keytabs ${statuses.filter(_._2 == false).map(_._1)} into POD")
                )
              _ <- secret.createSecret(meta.namespace, state.keytabs, secretName)
              _ = logger.info(s"Keytab secret $secretName created")
            } yield ()
        }
        Future.sequence(r)
      }
    } yield ()
  }

  private def copyKeytabs(namespace: String, state: KerberosState): Future[List[(Path, Boolean)]] =
    Future(state.keytabs.foldLeft(List.empty[(Path, Boolean)]) {
      case (acc, keytab) =>
        logger.debug(s"Copying keytab '$keytab' into $namespace:${state.podName} POD")
        acc :+ (keytab.path, client.pods
          .inNamespace(namespace)
          .withName(state.podName)
          .inContainer(operatorCfg.kadminContainer)
          .file(keytab.path.toString)
          .copy(keytab.path))
    })

  override def onDelete(krb: Krb, meta: Metadata): Unit = {
    logger.info(s"delete event: $krb, $meta")
    for {
      _ <- template.delete(krb, meta)
      _ <- secret.deleteSecrets(meta.namespace)
    } yield ()
  }
}
