package io.github.novakovalexey.krboperator.service

import java.io.{ByteArrayOutputStream, File}
import java.nio.file.{Path, Paths}
import java.util.concurrent.TimeUnit

import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.Metadata
import io.fabric8.kubernetes.api.model.Status
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ExecListener, ExecWatch, Execable}
import io.fabric8.kubernetes.client.utils.Serialization
import io.github.novakovalexey.krboperator.service.Kadmin._
import io.github.novakovalexey.krboperator.{KrbOperatorCfg, Principal}
import okhttp3.Response

import scala.jdk.CollectionConverters._
import scala.util.{Random, Using}

final case class KerberosState(podName: String, keytabs: List[KeytabMeta])
final case class KadminContext(realm: String, meta: Metadata, adminPwd: String)
final case class KeytabMeta(name: String, path: Path)

object Kadmin {
  type KeytabPath = String

  def keytabToPath(prefix: String, name: String): String =
    s"/tmp/$prefix/$name"
}

class Kadmin[F[_]](client: KubernetesClient, cfg: KrbOperatorCfg)(implicit F: Sync[F]) extends LazyLogging {
  private val listener = new ExecListener {
    override def onOpen(response: Response): Unit =
      logger.debug(s"on open: ${response.body().string()}")

    override def onFailure(t: Throwable, response: Response): Unit =
      logger.error(s"Failure on 'pod exec': ${response.body().string()}", t)

    override def onClose(code: Int, reason: String): Unit =
      logger.debug(s"listener closed with code '$code', reason: $reason")
  }

  def createPrincipalsAndKeytabs(principals: List[Principal], context: KadminContext): F[KerberosState] =
    F.delay {
      client
        .pods()
        .inNamespace(context.meta.namespace)
        .withLabel(Template.DeploymentSelector, context.meta.name)
        .list()
        .getItems
        .asScala
        .headOption
    }.flatMap {
      case Some(p) =>
        val podName = p.getMetadata.getName
        F.fromEither {
          logger.debug(s"Waiting for POD in ${context.meta.namespace} namespace to be ready")
          //TODO: wait is blocking operation, need to write own wait function
          client.resource(p).inNamespace(context.meta.namespace).waitUntilReady(1, TimeUnit.MINUTES)
          logger.debug(s"POD '$podName' is ready")

          val groupedByKeytab = principals.groupBy(_.keytab)
          lazy val uniquePrefix = Random.alphanumeric.take(10).mkString

          val keytabsOrErrors = groupedByKeytab.toList.map {
            case (keytab, principals) =>
              addKeytab(context, uniquePrefix, keytab, principals, podName)
                .map(KeytabMeta(keytab, _))
                .toValidatedNec
          }.sequence

          keytabsOrErrors.toEither.leftMap(e => new RuntimeException(e.toList.mkString(", ")))
        }.map { paths =>
          logger.debug(s"keytab files added: $paths")
          KerberosState(podName, paths)
        }.adaptErr {
          case t =>
            new RuntimeException(s"Failed to create keytab(s) via 'kadmin', reason: $t")
        }

      case None =>
        val msg = s"No KDC POD found for ${context.meta}"
        logger.error(msg)
        F.raiseError(new RuntimeException(msg))
    }

  private def addKeytab(
    context: KadminContext,
    prefix: String,
    keytab: String,
    principals: List[Principal],
    podName: String
  ): Either[String, Path] = {
    val errStream = new ByteArrayOutputStream()
    val errChannelStream = new ByteArrayOutputStream()

    val exe =
      client
        .pods()
        .inNamespace(context.meta.namespace)
        .withName(podName)
        .inContainer(cfg.kadminContainer)
        .readingInput(System.in)
        .writingOutput(System.out)
        .writingError(errStream)
        .writingErrorChannel(errChannelStream)
        .withTTY()
        .usingListener(listener)

    val keytabPath = keytabToPath(prefix, keytab)

    val execs = principals.foldLeft(List.empty[ExecWatch]) {
      case (acc, p) =>
        acc ++ List(
          createWorkingDir(exe, keytabPath),
          createPrincipal(context.realm, context.adminPwd, exe, p),
          createKeytab(context.realm, context.adminPwd, exe, p.name, keytabPath)
        )
    }

    val ec = getExitCode(errChannelStream)
    closeExecWatchers(execs)
    val errStreamArr = errStream.toByteArray

    ec match {
      case Left(e) =>
        logger.error(s"Got error while creating keytab: $e")
        Left(e)
      case _ if errStreamArr.nonEmpty =>
        val e = new String(errStreamArr)
        logger.error(s"Got error from error stream while creating keytab: $e")
        Left(e)
      case Right(_) =>
        Right(Paths.get(keytabPath))
    }
  }

  private def createWorkingDir(exe: Execable[KeytabPath, ExecWatch], keytabPath: String) =
    runCommand(List("mkdir", new File(keytabPath).getParent), exe)

  private def closeExecWatchers(execs: List[ExecWatch]): Unit = {
    val closedCount = execs.foldLeft(0) {
      case (acc, ew) =>
        Using.resource(ew) { _ =>
          acc + 1
        }
    }
    logger.debug(s"Closed execWatcher(s): $closedCount")
  }

  private def getExitCode(errChannelStream: ByteArrayOutputStream): Either[String, Int] = {
    val status = Serialization.unmarshal(errChannelStream.toString, classOf[Status])
    if (status.getStatus.equals("Success")) Right(0)
    else Left(status.getMessage)
  }

  private def runCommand(cmd: List[String], exe: Execable[String, ExecWatch]): ExecWatch =
    exe.exec(cmd: _*)

  private def createKeytab(
    realm: String,
    adminPwd: String,
    exe: Execable[String, ExecWatch],
    principal: String,
    keytab: KeytabPath
  ) = {
    val keytabCmd = cfg.addKeytabCmd
      .replaceAll("\\$realm", realm)
      .replaceAll("\\$path", keytab)
      .replaceAll("\\$username", principal)
    val addKeytab = s"echo '$adminPwd' | $keytabCmd"
    runCommand(List("bash", "-c", addKeytab), exe)
  }

  private def createPrincipal(realm: String, adminPwd: String, exe: Execable[String, ExecWatch], p: Principal) = {
    val addCmd = cfg.addPrincipalCmd
      .replaceAll("\\$realm", realm)
      .replaceAll("\\$username", p.name)
      .replaceAll("\\$password", if (isRandomPassword(p.password)) randomString else p.value)
    val addPrincipal = s"echo '$adminPwd' | $addCmd"
    runCommand(List("bash", "-c", addPrincipal), exe)
  }

  private def isRandomPassword(password: String): Boolean =
    password == null || password == "random"

  private def randomString =
    Random.alphanumeric.take(10).mkString
}
