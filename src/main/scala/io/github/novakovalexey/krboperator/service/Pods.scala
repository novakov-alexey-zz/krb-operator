package io.github.novakovalexey.krboperator.service

import java.io.ByteArrayOutputStream
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
import io.github.novakovalexey.krboperator.service.Kadmin.ExecInPodTimeout
import okhttp3.Response

import scala.concurrent.duration.{FiniteDuration, _}
import scala.jdk.CollectionConverters._
import scala.util.Using

trait PodsAlg[F[_]] {
  def executeInPod(client: KubernetesClient, containerName: String)(namespace: String, podName: String)(
    commands: Execable[String, ExecWatch] => List[ExecWatch]
  ): F[Unit]

  def getPod(client: KubernetesClient)(namespace: String, labelKey: String, labelValue: String): Option[Pod]

  def waitForPod(client: KubernetesClient)(
    meta: Metadata,
    previewPod: Option[Pod] => F[Unit],
    findPod: F[Option[Pod]],
    duration: FiniteDuration = 1.minute
  ): F[Option[Pod]]
}

object PodsAlg {
  implicit def k8sPod[F[_]: Sync: Timer]: PodsAlg[F] = new Pods[F]
}

class Pods[F[_]](implicit F: Sync[F], T: Timer[F]) extends LazyLogging with PodsAlg[F] with WaitUtils {

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

  def executeInPod(client: KubernetesClient, containerName: String)(namespace: String, podName: String)(
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
            .inContainer(containerName)
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

  private def getExitCode(errChannelStream: ByteArrayOutputStream): Either[String, Int] = {
    val status = Serialization.unmarshal(errChannelStream.toString, classOf[Status])
    if (status.getStatus.equals("Success")) Right(0)
    else Left(status.getMessage)
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

  def getPod(client: KubernetesClient)(namespace: String, labelKey: String, labelValue: String): Option[Pod] =
    client
      .pods()
      .inNamespace(namespace)
      .withLabel(labelKey, labelValue)
      .list()
      .getItems
      .asScala
      .find(p => Option(p.getMetadata.getDeletionTimestamp).isEmpty)

  def waitForPod(client: KubernetesClient)(
    meta: Metadata,
    previewPod: Option[Pod] => F[Unit],
    findPod: F[Option[Pod]],
    duration: FiniteDuration = 1.minute
  ): F[Option[Pod]] = {
    for {
      _ <- F.delay(logger.info(s"Going to wait for Pod in namespace ${meta.namespace} until ready: $duration"))
      (ready, pod) <- waitFor[F, Pod](duration, previewPod) {
        for {
          p <- findPod
          ready <- p match {
            case Some(pod) => F.delay(Readiness.isPodReady(pod))
            case None => false.pure[F]
          }
        } yield (ready, p)
      }
      _ <- F.whenA(ready)(F.delay(logger.info(s"POD in namespace ${meta.namespace} is ready ")))
    } yield pod
  }
}
