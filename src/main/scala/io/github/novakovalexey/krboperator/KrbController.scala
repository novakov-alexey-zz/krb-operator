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

object KrbController {
  val checkMark: String = "\u2714"
}

class KrbController[F[_]: Parallel: ConcurrentEffect](
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

  override def onAdd(krb: CustomResource[Krb, Status]): F[NewStatus[Status]] = {
    logger.info(s"'add' event: ${krb.spec}, ${krb.metadata}")
    onApply(krb.spec, krb.metadata)
  }

  override def onModify(krb: CustomResource[Krb, Status]): F[NewStatus[Status]] = {
    logger.info(s"'modify' event: ${krb.spec}, ${krb.metadata}")
    onApply(krb.spec, krb.metadata)
  }

  override def reconcile(krb: CustomResource[Krb, Status]): F[NewStatus[Status]] = {
    logger.debug(s"reconcile event: ${krb.spec}, ${krb.metadata}")
    onApply(krb.spec, krb.metadata)
  }

  override def onDelete(krb: CustomResource[Krb, Status]): F[Unit] =
    for {
      _ <- F.delay(logger.info(s"delete event: ${krb.spec}, ${krb.metadata}"))
      _ <- template.delete(krb.spec, krb.metadata)
      _ <- secret.deleteSecrets(krb.metadata.namespace)
    } yield ()

  private def onApply(krb: Krb, meta: Metadata) = {
    for {
      _ <- template.findService(meta) match {
        case Some(_) =>
          logger.debug(s"$checkMark [${meta.name}] Service is found, so skipping its creation")
          F.unit
        case None =>
          for {
            _ <- template.createService(meta)
            _ = logger.info(s"$checkMark Service ${meta.name} created")
          } yield ()
      }
      _ <- secret.findAdminSecret(meta) match {
        case Some(_) =>
          logger.debug(s"$checkMark [${meta.name}] Admin Secret is found, so skipping its creation")
          F.unit
        case None =>
          for {
            _ <- secret.createAdminSecret(meta, template.adminSecretSpec)
            _ = logger.info(s"$checkMark Admin secret ${meta.name} created")
          } yield ()
      }
      _ <- template.findDeployment(meta) match {
        case Some(_) =>
          logger.debug(s"$checkMark [${meta.name}] Deployment is found, so skipping its creation")
          F.unit
        case None =>
          for {
            _ <- template.createDeployment(meta, krb.realm)
            _ <- template.waitForDeployment(meta)
            _ = logger.info(s"$checkMark deployment ${meta.name} created")
          } yield ()
      }

      missingSecrets <- secret.findMissing(meta, krb.principals.map(_.secret.name).toSet)
      created <- missingSecrets.toList match {
        case Nil =>
          F.delay(logger.debug(s"There are no missing secrets")) *> F.pure(List.empty[Unit])
        case _ =>
          F.delay(logger.info(s"There are ${missingSecrets.size} missing secrets, name(s): $missingSecrets")) *> createSecrets(
            krb,
            meta,
            missingSecrets
          )
      }
      _ <- F.whenA(created.nonEmpty)(F.delay(logger.info(s"${created.length} secrets created")))
    } yield Status(processed = true, created.length, krb.principals.length).some
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
              _ <- F.delay(logger.debug(s"Creating secret: $secretName"))
              state <- kadmin.createPrincipalsAndKeytabs(principals, context)
              statuses <- copyKeytabs(meta.namespace, state)
              _ <- checkStatuses(statuses)
              _ <- secret.createSecret(meta.namespace, state.principals, secretName)
              _ = logger.info(s"$checkMark Keytab secret $secretName created in ${meta.namespace}")
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
        logger.debug(s"Copying keytab '$path' from $namespace/${state.podName} POD")
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
