package io.github.novakovalexey.krboperator

import java.nio.file.Paths

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.openshift.client.OpenShiftClient
import io.github.novakovalexey.k8soperator4s.CrdOperator
import io.github.novakovalexey.k8soperator4s.common.{CrdConfig, Metadata}
import io.github.novakovalexey.krboperator.service.{Kadmin, KerberosState, SecretService, Template}

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

    template.isIncomplete(meta).flatMap { yes =>
      if (yes) {
        logger.info("Creating everything from scratch")
        val r = for {
          _ <- template.delete(krb, meta)
          _ <- template.createOrReplace(krb, meta)
          _ <- template.waitForDeployment(meta)
          pwd <- secret.getAdminPwd(meta)
          state <- kadmin.initKerberos(meta, krb, pwd)
          _ <- copyKeytabs(meta.namespace, state)
          n <- secret.createSecrets(meta.namespace, state.principals)
          _ = logger.info(s"$n secret(s) were created in ${meta.namespace}")
          _ = logger.info(s"new instance $meta has been created")
        } yield ()

        r.failed.foreach(t => logger.error("Failed to create", t))
        r
      } else {
        logger.info(s"Krb instance $meta already exists, so ignoring this event")
        Future.successful(())
      }
    }
  }

  private def copyKeytabs(namespace: String, state: KerberosState): Future[Unit] =
    Future(state.principals.foreach {
      case (_, kp) =>
        client.pods
          .inNamespace(namespace)
          .withName(state.podName)
          .inContainer(operatorCfg.kadminContainer)
          .file(kp)
          .copy(Paths.get(kp))
    })

  override def onDelete(krb: Krb, meta: Metadata): Unit = {
    logger.info(s"delete event: $krb, $meta")
    template.delete(krb, meta)
  }
}
