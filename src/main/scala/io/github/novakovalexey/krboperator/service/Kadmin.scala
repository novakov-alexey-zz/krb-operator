package io.github.novakovalexey.krboperator.service

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

import com.typesafe.scalalogging.LazyLogging
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ExecListener, ExecWatch, Execable}
import io.github.novakovalexey.k8soperator4s.common.Metadata
import io.github.novakovalexey.krboperator.service.Kadmin._
import io.github.novakovalexey.krboperator.{KrbOperatorCfg, Principal}
import okhttp3.Response

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Random

final case class KerberosState(podName: String, keytabPaths: List[KeytabMeta])
final case class KadminContext(realm: String, meta: Metadata, adminPwd: String, keytabPrefix: String)

object Kadmin {
  type KeytabPath = String

  def keytabToPath(prefix: String, name: String): String =
    s"/tmp/$prefix/$name"
}

case class KeytabMeta(name: String, path: KeytabPath)

class Kadmin(client: KubernetesClient, cfg: KrbOperatorCfg)(implicit ec: ExecutionContext) extends LazyLogging {
  private val listener = new ExecListener {
    override def onOpen(response: Response): Unit =
      logger.info(s"on open: ${response.body().string()}")

    override def onFailure(t: Throwable, response: Response): Unit =
      logger.error(s"Failure on 'pod exec': ${response.body().string()}", t)

    override def onClose(code: Int, reason: String): Unit =
      logger.info(s"listener closed with code '$code', reason: $reason")
  }

  def createPrincipalsAndKeytabs(principals: List[Principal], context: KadminContext): Future[KerberosState] =
    Future {
      client
        .pods()
        .inNamespace(context.meta.namespace)
        .withLabel("deploymentconfig", context.meta.name)
        .list()
        .getItems
        .asScala
        .headOption
    }.flatMap {
      case Some(p) =>
        val podName = p.getMetadata.getName
        Future {
          logger.debug(s"Waiting for POD in ${context.meta.namespace} namespace to be ready")
          //TODO: wait is blocking operation, need to write own wait function
          client.resource(p).inNamespace(context.meta.namespace).waitUntilReady(1, TimeUnit.MINUTES)
          logger.debug(s"POD '$podName' is ready")

          val groupedByKeytab = principals.groupBy(_.keytab)
          lazy val keytabsPrefix = Random.alphanumeric.take(10).mkString

          //TODO: this should be re-done via cats Validated
          groupedByKeytab.foldLeft(Right(List.empty[KeytabMeta]): Either[String, List[KeytabMeta]]) {
            case (acc, (keytab, principals)) =>
              addKeytab(context, keytabsPrefix, keytab, principals, podName)
                .flatMap(path => acc.map(l => l :+ KeytabMeta(keytab, path)))
          }
        }.flatMap {
          case Right(paths) =>
            logger.info(s"keytabs added: $paths")
            Future.successful(KerberosState(podName, paths))
          case Left(e) =>
            Future.failed(new RuntimeException(s"Failed to create keytab(s) via 'kadmin', reason: $e"))
        }
      case None =>
        val msg = s"No KDC POD found for ${context.meta}"
        logger.error(msg)
        Future.failed(new RuntimeException(msg))
    }

  private def addKeytab(
    context: KadminContext,
    prefix: String,
    keytab: String,
    principals: List[Principal],
    podName: String
  ): Either[String, KeytabPath] = {
    val errStream = new ByteArrayOutputStream()
    val watch = client
      .pods()
      .inNamespace(context.meta.namespace)
      .withName(podName)
      .inContainer(cfg.kadminContainer)
      .readingInput(System.in)
      .writingOutput(System.out)
      .writingError(errStream)
      .withTTY()
      .usingListener(listener)

    val errors = new String(errStream.toByteArray)

    if (errors.nonEmpty) {
      logger.error(s"Error occurred: $errors")
      Left(errors)
    } else {
      val keytabPath = keytabToPath(prefix, keytab)
      principals.foreach { p =>
        //TODO: check whether Watch needs to be closed every time
        createPrincipal(context.realm, context.adminPwd, watch, p)
        createKeytab(context.realm, context.adminPwd, watch, p.name, keytabPath)
      }
      Right(keytabPath)
    }
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
    exe.exec("bash", "-c", addKeytab).close()
  }

  private def createPrincipal(realm: String, adminPwd: String, exe: Execable[String, ExecWatch], p: Principal): Unit = {
    val addCmd = cfg.addPrincipalCmd
      .replaceAll("\\$realm", realm)
      .replaceAll("\\$username", p.name)
      .replaceAll("\\$password", if (isRandomPassword(p.password)) randomString else p.value)
    val addPrincipal = s"echo '$adminPwd' | $addCmd"
    exe.exec("bash", "-c", addPrincipal).close()
  }

  private def isRandomPassword(password: String): Boolean =
    password == null || password == "random"

  private def randomString =
    Random.alphanumeric.take(10).mkString
}
