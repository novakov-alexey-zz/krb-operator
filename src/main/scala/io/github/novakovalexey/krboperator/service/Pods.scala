package io.github.novakovalexey.krboperator.service

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

import cats.effect.{Sync, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.models.Metadata
import io.fabric8.kubernetes.api.model.{Pod, Status}
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ExecListener, ExecWatch, Execable}
import io.fabric8.kubernetes.client.internal.readiness.Readiness
import io.fabric8.kubernetes.client.utils.Serialization
import io.github.novakovalexey.krboperator.service.Kadmin.ExecInPodTimeout
import okhttp3.Response
import io.github.novakovalexey.krboperator.Utils._

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
  private val debug = logDebugWithNamespace(logger)
  private val info = logInfoWithNamespace(logger)
  private val error = logErrorWithNamespace(logger)
  private def listener(namespace: String, closed: AtomicBoolean) = new ExecListener {
    override def onOpen(response: Response): Unit =
      debug(namespace, s"on open: ${response.body().string()}")

    override def onFailure(t: Throwable, response: Response): Unit =
      error(namespace, s"Failure on 'pod exec': ${response.body().string()}", t)

    override def onClose(code: Int, reason: String): Unit = {
      debug(namespace, s"listener closed with code '$code', reason: $reason")
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
            .usingListener(listener(namespace, isClosed))

        val watchers = commands(execablePod)

        for {
          _ <- F.delay(debug(namespace, s"Waiting for Pod exec listener to be closed for $ExecInPodTimeout"))
          closed <- waitFor[F](namespace, ExecInPodTimeout)(F.delay(isClosed.get()))
          _ <- F
            .raiseError[Unit](new RuntimeException(s"Failed to close POD exec listener within $ExecInPodTimeout"))
            .whenA(!closed)
          _ <- closeExecWatchers(namespace, watchers: _*)
          r <- F.delay {
            val ec = getExitCode(errChannelStream)
            val errStreamArr = errStream.toByteArray
            (ec, errStreamArr)
          }
        } yield r
      }
      checked <- checkExitCode(namespace, exitCode, errStreamArr)
    } yield checked
  }

  private def getExitCode(errChannelStream: ByteArrayOutputStream): Either[String, Int] = {
    val status = Serialization.unmarshal(errChannelStream.toString, classOf[Status])
    Option(status.getStatus) match {
      case Some("Success") => Right(0)
      case None => Left("Status is null")
      case Some(s) => Left(status.getMessage)
    }
  }

  private def checkExitCode(namespace: String, exitCode: Either[String, Int], errStreamArr: Array[Byte]): F[Unit] = {
    exitCode match {
      case Left(e) => F.raiseError[Unit](new RuntimeException(e))
      case _ if errStreamArr.nonEmpty =>
        val e = new String(errStreamArr)
        val t = new RuntimeException(e)
        error(namespace, s"Got error from error stream", t)
        F.raiseError[Unit](t)
      case _ => F.unit
    }
  }

  private def closeExecWatchers(namespace: String, execs: ExecWatch*): F[Unit] = F.delay {
    val closedCount = execs.foldLeft(0) {
      case (acc, ew) =>
        Using.resource(ew) { _ =>
          acc + 1
        }
    }
    debug(namespace, s"Closed execWatcher(s): $closedCount")
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
      _ <- F.delay(info(meta.namespace, s"Going to wait for Pod in namespace ${meta.namespace} until ready: $duration"))
      (ready, pod) <- waitFor[F, Pod](meta.namespace, duration, previewPod) {
        for {
          p <- findPod
          ready <- p match {
            case Some(pod) => F.delay(Readiness.isPodReady(pod))
            case None => false.pure[F]
          }
        } yield (ready, p)
      }
      _ <- F.whenA(ready)(F.delay(info(meta.namespace, s"POD in namespace ${meta.namespace} is ready ")))
    } yield pod
  }
}
