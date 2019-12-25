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
import io.github.novakovalexey.krboperator.{KrbOperatorCfg, Password, Principal}
import okhttp3.Response

import scala.concurrent.duration._
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
    getPod(context).flatMap {
      case Some(p) =>
        val podName = p.getMetadata.getName
        waitForPod(context.meta.namespace, p) *>
          F.defer {
            val groupedByKeytab = principals.groupBy(_.keytab)
            val uniquePrefix = randomString

            val keytabsOrErrors = groupedByKeytab.toList.map {
              case (keytab, principals) =>
                val path = keytabToPath(uniquePrefix, keytab)
                for {
                  _ <- createWorkingDir(context.meta.namespace, podName, Paths.get(path))
                  r <- addKeytab(context, path, principals, podName)
                    .map(KeytabMeta(keytab, _))
                } yield r
            }

            keytabsOrErrors.sequence
          }.map { paths =>
            logger.debug(s"keytab files added: $paths")
            KerberosState(podName, paths)
          }.adaptErr {
            case t =>
              new RuntimeException(s"Failed to create keytab(s) via 'kadmin'", t)
          }

      case None =>
        val msg = s"No KDC POD found for ${context.meta}"
        logger.error(msg)
        F.raiseError(new RuntimeException(msg))
    }

  private def getPod(context: KadminContext) =
    F.delay {
      client
        .pods()
        .inNamespace(context.meta.namespace)
        .withLabel(Template.DeploymentSelector, context.meta.name)
        .list()
        .getItems
        .asScala
        .headOption
    }

  private def waitForPod(namespace: String, p: Pod, duration: FiniteDuration = 1.minute): F[Unit] =
    F.delay(logger.info(s"Going to wait for POD ${p.getMetadata.getName} until ready: $duration")) *>
      waitFor(duration) {
        Readiness.isPodReady(p)
      }.flatMap { ready =>
        if (ready) {
          F.delay(logger.info(s"POD is ready: ${p.getMetadata.getName}"))
        } else F.raiseError(new RuntimeException("Failed to wait for POD is ready"))
      }

  private def addKeytab(
    context: KadminContext,
    keytabPath: KeytabPath,
    principals: List[Principal],
    podName: String
  ): F[Path] = {
    executeInPod(context.meta.namespace, podName) { execable =>
      principals.foldLeft(List.empty[ExecWatch]) {
        case (acc, p) =>
          acc ++ List(
            createPrincipal(context.realm, context.adminPwd, execable, p),
            createKeytab(context.realm, context.adminPwd, execable, p.name, keytabPath)
          )
      }
    }.map(_ => Paths.get(keytabPath))
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
        val execable =
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

        val execWatch = commands(execable)

        val maxWait = 60.seconds
        val closed = waitFor(maxWait)(isClosed.get())
        closed.flatMap { yes =>
          if (yes) F.unit
          else F.raiseError[Unit](new RuntimeException(s"Failed to close POD exec listener within $maxWait"))
        } *> F.delay {
          closeExecWatchers(execWatch: _*)
          val ec = getExitCode(errChannelStream)
          val errStreamArr = errStream.toByteArray
          (ec, errStreamArr)
        }
      }
      r <- exitCode match {
        case Left(e) => F.raiseError[Unit](new RuntimeException(e))
        case _ if errStreamArr.nonEmpty =>
          val e = new String(errStreamArr)
          logger.error(s"Got error from error stream: $e")
          F.raiseError[Unit](new RuntimeException(e))
        case _ => F.unit
      }
    } yield r
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
      .replaceAll("\\$password", getPassword(p.password))
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
