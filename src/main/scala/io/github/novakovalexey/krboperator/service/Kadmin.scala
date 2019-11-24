package io.github.novakovalexey.krboperator.service

import java.io.{ByteArrayOutputStream, File}
import java.nio.file.{Path, Paths}
import java.util.concurrent.TimeUnit

import cats.effect.Sync
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ExecListener, ExecWatch, Execable}
import io.github.novakovalexey.k8soperator.Metadata
import io.github.novakovalexey.krboperator.service.Kadmin._
import io.github.novakovalexey.krboperator.{KrbOperatorCfg, Principal}
import okhttp3.Response

import scala.jdk.CollectionConverters._
import scala.util.{Random, Using}

final case class KerberosState(podName: String, keytabs: List[KeytabMeta])
final case class KadminContext(realm: String, meta: Metadata, adminPwd: String, keytabPrefix: String)
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
        F.delay {
          logger.debug(s"Waiting for POD in ${context.meta.namespace} namespace to be ready")
          //TODO: wait is blocking operation, need to write own wait function
          client.resource(p).inNamespace(context.meta.namespace).waitUntilReady(1, TimeUnit.MINUTES)
          logger.debug(s"POD '$podName' is ready")

          val groupedByKeytab = principals.groupBy(_.keytab)
          lazy val uniquePrefix = Random.alphanumeric.take(10).mkString

          //TODO: this should be re-done via cats Validated
          groupedByKeytab.foldLeft(Either.right[String, List[KeytabMeta]](List.empty)) {
            case (acc, (keytab, principals)) =>
              addKeytab(context, uniquePrefix, keytab, principals, podName)
                .flatMap(path => acc.map(_ :+ KeytabMeta(keytab, path)))
          }
        }.flatMap {
          case Right(paths) =>
            logger.info(s"keytab files added: $paths")
            F.pure(KerberosState(podName, paths))
          case Left(e) =>
            F.raiseError(new RuntimeException(s"Failed to create keytab(s) via 'kadmin', reason: $e"))
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
    val exe = client
      .pods()
      .inNamespace(context.meta.namespace)
      .withName(podName)
      .inContainer(cfg.kadminContainer)
      .readingInput(System.in)
      .writingOutput(System.out)
      .writingError(errStream)
      .withTTY()
      .usingListener(listener)

    val keytabPath = keytabToPath(prefix, keytab)
    principals.foreach { p =>
      runCommand(List("mkdir", new File(keytabPath).getParent), exe)
      createPrincipal(context.realm, context.adminPwd, exe, p)
      createKeytab(context.realm, context.adminPwd, exe, p.name, keytabPath)
    }

    val errors = errStream.toByteArray
    if (errors.nonEmpty) {
      val errStr = new String(errors)
      logger.error(s"Error occurred: $errStr")
      Left(errStr)
    } else {
      Right(Paths.get(keytabPath))
    }
  }

  private def runCommand(cmd: List[String], exe: Execable[String, ExecWatch]): Unit =
    Using.resource(exe.exec(cmd: _*)) { _ =>
      ()
    }

  private def createKeytab(
    realm: String,
    adminPwd: String,
    exe: Execable[String, ExecWatch],
    principal: String,
    keytab: KeytabPath
  ): Unit = {
    val keytabCmd = cfg.addKeytabCmd
      .replaceAll("\\$realm", realm)
      .replaceAll("\\$path", keytab)
      .replaceAll("\\$username", principal)
    val addKeytab = s"echo '$adminPwd' | $keytabCmd"
    runCommand(List("bash", "-c", addKeytab), exe)
  }

  private def createPrincipal(realm: String, adminPwd: String, exe: Execable[String, ExecWatch], p: Principal): Unit = {
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
