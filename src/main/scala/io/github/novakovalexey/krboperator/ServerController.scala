package io.github.novakovalexey.krboperator

import cats.Parallel
import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.Controller
import freya.models.{CustomResource, Metadata, NewStatus}
import io.fabric8.kubernetes.api.model.HasMetadata
import io.github.novakovalexey.krboperator.ServerController._
import io.github.novakovalexey.krboperator.Utils._
import io.github.novakovalexey.krboperator.service._

object ServerController {
  val checkMark: String = "\u2714"
}

class ServerController[F[_]: Parallel](template: Template[F, _ <: HasMetadata], secret: Secrets[F])(implicit F: Sync[F])
    extends Controller[F, KrbServer, KrbServerStatus]
    with LazyLogging {

  private val debug = logDebugWithNamespace(logger)
  private val info = logInfoWithNamespace(logger)

  override def onAdd(krb: CustomResource[KrbServer, KrbServerStatus]): F[NewStatus[KrbServerStatus]] = {
    info(krb.metadata.namespace, s"'add' event: ${krb.spec}, ${krb.metadata}")
    onApply(krb.spec, krb.metadata)
  }

  override def onModify(krb: CustomResource[KrbServer, KrbServerStatus]): F[NewStatus[KrbServerStatus]] = {
    info(krb.metadata.namespace, s"'modify' event: ${krb.spec}, ${krb.metadata}")
    onApply(krb.spec, krb.metadata)
  }

  override def reconcile(krb: CustomResource[KrbServer, KrbServerStatus]): F[NewStatus[KrbServerStatus]] = {
    debug(krb.metadata.namespace, s"reconcile event: ${krb.spec}, ${krb.metadata}")
    onApply(krb.spec, krb.metadata)
  }

  override def onDelete(krb: CustomResource[KrbServer, KrbServerStatus]): F[Unit] =
    F.delay(info(krb.metadata.namespace, s"delete event: ${krb.spec}, ${krb.metadata}")) *> template.delete(
      krb.metadata
    )

  private def onApply(krb: KrbServer, meta: Metadata) = {
    for {
      _ <- template.findService(meta) match {
        case Some(_) =>
          debug(meta.namespace, s"$checkMark [${meta.name}] Service is found, so skipping its creation")
          F.unit
        case None =>
          for {
            _ <- template.createService(meta)
            _ = info(meta.namespace, s"$checkMark Service ${meta.name} created")
          } yield ()
      }
      _ <- secret.findAdminSecret(meta) match {
        case Some(_) =>
          debug(meta.namespace, s"$checkMark [${meta.name}] Admin Secret is found, so skipping its creation")
          F.unit
        case None =>
          for {
            _ <- secret.createAdminSecret(meta, template.adminSecretSpec)
            _ = info(meta.namespace, s"$checkMark Admin secret ${meta.name} created")
          } yield ()
      }
      _ <- template.findDeployment(meta) match {
        case Some(_) =>
          debug(meta.namespace, s"$checkMark [${meta.name}] Deployment is found, so skipping its creation")
          F.unit
        case None =>
          for {
            _ <- template.createDeployment(meta, krb.realm)
            _ <- template.waitForDeployment(meta)
            _ = info(meta.namespace, s"$checkMark deployment ${meta.name} created")
          } yield ()
      }
    } yield KrbServerStatus(processed = true).some
  }.handleErrorWith { e =>
    F.delay(logger.error(s"[${meta.namespace}] Failed to handle create/apply event: $krb, $meta", e)) *>
      F.pure(Some(KrbServerStatus(processed = false, e.getMessage)))
  }
}
