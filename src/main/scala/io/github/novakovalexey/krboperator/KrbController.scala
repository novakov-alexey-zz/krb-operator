package io.github.novakovalexey.krboperator

import java.nio.file.Path

import cats.Parallel
import cats.effect.{ConcurrentEffect, Sync}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.Configuration.CrdConfig
import freya.models.{CustomResource, NewStatus}
import freya.{Controller, Metadata}
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.openshift.client.OpenShiftClient
import io.github.novakovalexey.krboperator.KrbController._
import io.github.novakovalexey.krboperator.service._
import Utils._ 

object KrbController {
  val checkMark: String = "\u2714"
}

class KrbController[F[_]: Parallel](
  client: OpenShiftClient,
  cfg: CrdConfig,
  operatorCfg: KrbOperatorCfg,
  template: Template[F, _ <: HasMetadata],
  kadmin: Kadmin[F],
  secret: Secrets[F],
  parallelSecret: Boolean = true
)(implicit F: Sync[F])
    extends Controller[F, Krb, Status]
    with LazyLogging {

  private val debug = logDebugWithNamespace(logger)
  private val info = logInfoWithNamespace(logger)

  override def onAdd(krb: CustomResource[Krb, Status]): F[NewStatus[Status]] = {
    info(krb.metadata.namespace, s"'add' event: ${krb.spec}, ${krb.metadata}")
    onApply(krb.spec, krb.metadata)
  }

  override def onModify(krb: CustomResource[Krb, Status]): F[NewStatus[Status]] = {
    info(krb.metadata.namespace, s"'modify' event: ${krb.spec}, ${krb.metadata}")
    onApply(krb.spec, krb.metadata)
  }

  override def reconcile(krb: CustomResource[Krb, Status]): F[NewStatus[Status]] = {
    debug(krb.metadata.namespace, s"reconcile event: ${krb.spec}, ${krb.metadata}")  
    onApply(krb.spec, krb.metadata)
  }

  override def onDelete(krb: CustomResource[Krb, Status]): F[Unit] =
    for {
      _ <- F.delay(info(krb.metadata.namespace, s"delete event: ${krb.spec}, ${krb.metadata}"))
      _ <- template.delete(krb.spec, krb.metadata)
      _ <- secret.deleteSecrets(krb.metadata.namespace)
    } yield ()

  private def onApply(krb: Krb, meta: Metadata) = {
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

      missingSecrets <- secret.findMissing(meta, krb.principals.map(_.secret.name).toSet)
      created <- missingSecrets.toList match {
        case Nil =>
          F.delay(debug(meta.namespace, s"There are no missing secrets")) *> F.pure(List.empty[Unit])
        case _ =>
          F.delay(info(meta.namespace, s" There are ${missingSecrets.size} missing secrets, name(s): $missingSecrets")) *> createSecrets(
            krb,
            meta,
            missingSecrets
          )
      }
      _ <- F.whenA(created.nonEmpty)(F.delay(info(meta.namespace, s"${created.length} secrets created")))
    } yield Status(processed = true, created.length, krb.principals.length).some
  }.handleErrorWith { e =>
    F.delay(logger.error(s"[${meta.namespace}] Failed to handle create/apply event: $krb, $meta", e)) *>
      F.pure(Some(Status(processed = false, 0, krb.principals.length, e.getMessage)))
  }

  private def createSecrets(krb: Krb, meta: Metadata, missingSecrets: Set[String]) =
    for {
      pwd <- secret.getAdminPwd(meta)
      context = KadminContext(krb.realm, meta, pwd)
      created <- {
        val tasks = missingSecrets
          .map(s => (s, krb.principals.filter(_.secret.name == s)))
          .map {
            case (secretName, principals) =>
              for {
                _ <- F.delay(debug(meta.namespace, s"Creating secret: $secretName"))
                state <- kadmin.createPrincipalsAndKeytabs(principals, context)
                statuses <- copyKeytabs(meta.namespace, state)
                _ <- checkStatuses(statuses)
                _ <- secret.createSecret(meta.namespace, state.principals, secretName)
                _ = info(meta.namespace, s"$checkMark Keytab secret '$secretName' created")
                _ <- removeWorkingDirs(meta.namespace, state).handleError { e =>
                  logger
                    .error(
                      s"Failed to delete working directory(s) with keytab(s) in POD ${meta.namespace}/${state.podName}",
                      e
                    )
                }
              } yield ()
          }
          .toList
        if (parallelSecret) tasks.parSequence else tasks.sequence
      }
    } yield created

  private def checkStatuses(statuses: List[(Path, Boolean)]) = {
    val notAllCopied = !statuses.forall { case (_, copied) => copied }
    F.whenA(notAllCopied)(F.raiseError[Unit] {
      val paths = statuses.filter {
        case (_, copied) => !copied
      }.map { case (path, _) => path }
      new RuntimeException(s"Failed to upload keytab(s) $paths into POD")
    })
  }

  private def copyKeytabs(namespace: String, state: KerberosState): F[List[(Path, Boolean)]] =
    F.delay(state.principals.foldLeft(List.empty[(Path, Boolean)]) {
      case (acc, principals) =>
        val path = principals.keytabMeta.path
        debug(namespace, s"Copying keytab '$path' from $namespace/${state.podName} POD")
        val copied = client.pods
          .inNamespace(namespace)
          .withName(state.podName)
          .inContainer(operatorCfg.kadminContainer)
          .file(path.toString)
          .copy(path)

        acc :+ (path, copied)
    })

  private def removeWorkingDirs(namespace: String, state: KerberosState): F[Unit] =
    state.principals.map { p =>
      kadmin.removeWorkingDir(namespace, state.podName, p.keytabMeta.path)
    }.sequence.void
}
