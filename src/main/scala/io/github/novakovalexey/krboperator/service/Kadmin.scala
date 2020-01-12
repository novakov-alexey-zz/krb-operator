package io.github.novakovalexey.krboperator.service

import java.io.ByteArrayOutputStream
import java.nio.file.{Path, Paths}
import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.{Sync, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.Metadata
import io.fabric8.kubernetes.api.model.{Pod, Status}
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ExecListener, ExecWatch, Execable}
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import io.fabric8.kubernetes.client.utils.Serialization
import io.github.novakovalexey.krboperator.service.Kadmin._
import io.github.novakovalexey.krboperator.{KrbOperatorCfg, Password, Principal, Secret}
import okhttp3.Response

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{Random, Using}

final case class Credentials(username: String, password: String, secret: Secret) {
  override def toString: String = s"Credentials($username, <hidden>)"
}
final case class PrincipalsWithKey(credentials: List[Credentials], keytabMeta: KeytabMeta)
final case class KeytabMeta(name: String, path: Path)
final case class KerberosState(podName: String, principals: List[PrincipalsWithKey])
final case class KadminContext(realm: String, meta: Metadata, adminPwd: String)

object Kadmin {
  def keytabToPath(prefix: String, name: String): String =
    s"/tmp/$prefix/$name"

  val ExecInPodTimeout: FiniteDuration = 60.seconds
}

class Kadmin[F[_]](client: KubernetesClient, cfg: KrbOperatorCfg)(implicit F: Sync[F], T: Timer[F])
    extends LazyLogging
    with WaitUtils {
  private def listener(closed: AtomicBoolean) = new ExecListener {
    override def onOpen(response: Response): Unit =
      logger.debug(s"on open: ${response.body().string()}")

    override def onFailure(t: Throwable, response: Response): Unit =
      logger.error(s"Failure on 'pod exec': ${response.body().string()}", t)

    override def onClose(code: Int, reason: String): Unit = {
      logger.debug(s"listener closed with code '$code', reason: $reason")
      closed.getAndSet(true)
    }
  }

  def createPrincipalsAndKeytabs(principals: List[Principal], context: KadminContext): F[KerberosState] =
    (for {
      pod <- waitForPod(context).flatMap(F.fromOption(_, new RuntimeException(s"No Krb POD found for ${context.meta}")))

      podName = pod.getMetadata.getName

      principals <- F.defer {
        val groupedByKeytab = principals.groupBy(_.keytab)
        val keytabsOrErrors = groupedByKeytab.toList.map {
          case (keytab, principals) =>
            val path = Paths.get(keytabToPath(randomString, keytab))
            for {
              _ <- createWorkingDir(context.meta.namespace, podName, path)
              credentials = principals.map(p => Credentials(p.name, getPassword(p.password), p.secret))
              _ <- addKeytab(context, path, credentials, podName)
            } yield PrincipalsWithKey(credentials, KeytabMeta(keytab, path))
        }
        keytabsOrErrors.sequence
      }
    } yield {
      logger.debug(s"principals created: $principals")
      KerberosState(podName, principals)
    }).adaptErr {
      case t => new RuntimeException(s"Failed to create principal(s) & keytab(s) via 'kadmin'", t)
    }

  private def getPod(context: KadminContext) =
    client
      .pods()
      .inNamespace(context.meta.namespace)
      .withLabel(Template.DeploymentSelector, context.meta.name)
      .list()
      .getItems
      .asScala
      .find(p => Option(p.getMetadata.getDeletionTimestamp).isEmpty)

  private def waitForPod(context: KadminContext, duration: FiniteDuration = 1.minute): F[Option[Pod]] = {
    val previewPod: Option[Pod] => F[Unit] = pod =>
      pod.fold(F.delay(logger.debug("Pod is not available yet")))(
        p => F.delay(logger.debug(s"Pod ${p.getMetadata.getName} is not ready"))
    )

    for {
      _ <- F.delay(logger.info(s"Going to wait for Pod in namespace ${context.meta.namespace} until ready: $duration"))
      (ready, pod) <- waitFor[F, Pod](duration, previewPod) {
        for {
          p <- F.delay(getPod(context))
          ready <- p match {
            case Some(pod) => F.delay(Readiness.isPodReady(pod))
            case None => false.pure[F]
          }
        } yield (ready, p)
      }
      _ <- F.whenA(ready)(F.delay(logger.info(s"POD in namespace ${context.meta.namespace}  is ready ")))
    } yield pod
  }

  private def addKeytab(
    context: KadminContext,
    keytabPath: Path,
    credentials: List[Credentials],
    podName: String
  ): F[Unit] =
    executeInPod(context.meta.namespace, podName) { exe =>
      credentials.foldLeft(List.empty[ExecWatch]) {
        case (watchers, c) =>
          watchers ++ List(
            createPrincipal(context.realm, context.adminPwd, exe, c),
            createKeytab(context.realm, context.adminPwd, exe, c.username, keytabPath)
          )
      }
    }

  private def createWorkingDir(namespace: String, podName: String, keytab: Path): F[Unit] =
    executeInPod(namespace, podName) { execable =>
      List(runCommand(List("mkdir", keytab.getParent.toString), execable))
    }

  def removeWorkingDir(namespace: String, podName: String, keytab: Path): F[Unit] =
    executeInPod(namespace, podName) { execable =>
      List(runCommand(List("rm", "-r", keytab.getParent.toString), execable))
    }

  private def executeInPod(namespace: String, podName: String)(
    commands: Execable[String, ExecWatch] => List[ExecWatch]
  ): F[Unit] = {
    for {
      (exitCode, errStreamArr) <- F.defer {
        val errStream = new ByteArrayOutputStream()
        val errChannelStream = new ByteArrayOutputStream()
        val isClosed = new AtomicBoolean(false)
        val execablePod =
          client
            .pods()
            .inNamespace(namespace)
            .withName(podName)
            .inContainer(cfg.kadminContainer)
            .readingInput(System.in)
            .writingOutput(System.out)
            .writingError(errStream)
            .writingErrorChannel(errChannelStream)
            .withTTY()
            .usingListener(listener(isClosed))

        val watchers = commands(execablePod)

        for {
          _ <- F.delay(logger.debug(s"Waiting for Pod exec listener to be closed for $ExecInPodTimeout"))
          closed <- waitFor[F](ExecInPodTimeout)(F.delay(isClosed.get()))
          _ <- F
            .raiseError[Unit](new RuntimeException(s"Failed to close POD exec listener within $ExecInPodTimeout"))
            .whenA(!closed)
          _ <- closeExecWatchers(watchers: _*)
          r <- F.delay {
            val ec = getExitCode(errChannelStream)
            val errStreamArr = errStream.toByteArray
            (ec, errStreamArr)
          }
        } yield r
      }
      checked <- checkExitCode(exitCode, errStreamArr)
    } yield checked
  }

  private def checkExitCode(exitCode: Either[String, Int], errStreamArr: Array[Byte]): F[Unit] = {
    exitCode match {
      case Left(e) => F.raiseError[Unit](new RuntimeException(e))
      case _ if errStreamArr.nonEmpty =>
        val e = new String(errStreamArr)
        logger.error(s"Got error from error stream: $e")
        F.raiseError[Unit](new RuntimeException(e))
      case _ => F.unit
    }
  }

  private def closeExecWatchers(execs: ExecWatch*): F[Unit] = F.delay {
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

  private def runCommand(cmd: List[String], execable: Execable[String, ExecWatch]): ExecWatch =
    execable.exec(cmd: _*)

  private def createKeytab(
    realm: String,
    adminPwd: String,
    exe: Execable[String, ExecWatch],
    principal: String,
    keytab: Path
  ) = {
    val keytabCmd = cfg.addKeytabCmd
      .replaceAll("\\$realm", realm)
      .replaceAll("\\$path", keytab.toString)
      .replaceAll("\\$username", principal)
    val addKeytab = s"echo '$adminPwd' | $keytabCmd"
    runCommand(List("bash", "-c", addKeytab), exe)
  }

  private def createPrincipal(realm: String, adminPwd: String, exe: Execable[String, ExecWatch], cred: Credentials) = {
    val addCmd = cfg.addPrincipalCmd
      .replaceAll("\\$realm", realm)
      .replaceAll("\\$username", cred.username)
      .replaceAll("\\$password", cred.password)
    val addPrincipal = s"echo '$adminPwd' | $addCmd"
    runCommand(List("bash", "-c", addPrincipal), exe)
  }

  private def getPassword(password: Password): String =
    password match {
      case Password.Static(v) => v
      case _ => randomString
    }

  private def randomString =
    Random.alphanumeric.take(10).mkString
}
