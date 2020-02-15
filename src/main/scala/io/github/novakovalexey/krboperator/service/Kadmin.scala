package io.github.novakovalexey.krboperator.service

import java.nio.file.{Path, Paths}

import cats.effect.{Sync, Timer}
import cats.implicits._
import com.typesafe.scalalogging.LazyLogging
import freya.Metadata
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ExecWatch, Execable}
import io.github.novakovalexey.krboperator.{KeytabAndPassword, KrbOperatorCfg, Password, Principal, Secret, Static}

import scala.concurrent.duration._
import scala.util.Random

final case class Credentials(username: String, password: String, secret: Secret) {
  override def toString: String = s"Credentials($username, <hidden>)"
}
final case class PrincipalsWithKey(credentials: List[Credentials], keytabMeta: KeytabMeta)
final case class KeytabMeta(name: String, path: Path)
final case class KerberosState(podName: String, principals: List[PrincipalsWithKey])
final case class KadminContext(realm: String, meta: Metadata, adminPwd: String)

object Kadmin {
  val ExecInPodTimeout: FiniteDuration = 60.seconds
}

trait KeytabPathAlg {
  def keytabToPath(prefix: String, name: String): String
}

object KeytabPathAlg {
  implicit val pathGen: KeytabPathAlg = new KeytabPathGenerator
}

class KeytabPathGenerator extends KeytabPathAlg {
  def keytabToPath(prefix: String, name: String): String =
    s"/tmp/$prefix/$name"
}

class Kadmin[F[_]](client: KubernetesClient, cfg: KrbOperatorCfg)(
  implicit F: Sync[F],
  T: Timer[F],
  pods: PodsAlg[F],
  pathGen: KeytabPathAlg
) extends LazyLogging
    with WaitUtils {

  private val executeInKadmin = pods.executeInPod(client, cfg.kadminContainer) _

  def createPrincipalsAndKeytabs(principals: List[Principal], context: KadminContext): F[KerberosState] =
    (for {
      pod <- waitForPod(context).flatMap(F.fromOption(_, new RuntimeException(s"No Krb POD found for ${context.meta}")))

      podName = pod.getMetadata.getName

      principals <- F.defer {
        val groupedByKeytab = principals.groupBy(_.keytab)
        val keytabsOrErrors = groupedByKeytab.toList.map {
          case (keytab, principals) =>
            val path = Paths.get(pathGen.keytabToPath(randomString, keytab))
            for {
              _ <- createWorkingDir(context.meta.namespace, podName, path.getParent)
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

  private def waitForPod(context: KadminContext, duration: FiniteDuration = 1.minute): F[Option[Pod]] = {
    val previewPod: Option[Pod] => F[Unit] = pod =>
      pod.fold(F.delay(logger.debug("Pod is not available yet")))(
        p => F.delay(logger.debug(s"Pod ${p.getMetadata.getName} is not ready"))
    )

    pods.waitForPod(client)(
      context.meta,
      previewPod,
      F.delay(pods.getPod(client)(context.meta.namespace, Template.DeploymentSelector, context.meta.name))
    )
  }

  private def addKeytab(
    context: KadminContext,
    keytabPath: Path,
    credentials: List[Credentials],
    podName: String
  ): F[Unit] =
    executeInKadmin(context.meta.namespace, podName) { exe =>
      credentials.foldLeft(List.empty[ExecWatch]) {
        case (watchers, c) =>
          watchers ++ List(
            createPrincipal(context.realm, context.adminPwd, exe, c),
            createKeytab(context.realm, context.adminPwd, exe, c, keytabPath)
          )
      }
    }

  private def createWorkingDir(namespace: String, podName: String, keytabDir: Path): F[Unit] =
    executeInKadmin(namespace, podName) { execable =>
      List(runCommand(List("mkdir", keytabDir.toString), execable))
    }

  def removeWorkingDir(namespace: String, podName: String, keytab: Path): F[Unit] =
    executeInKadmin(namespace, podName) { execable =>
      List(runCommand(List("rm", "-r", keytab.getParent.toString), execable))
    }

  private def runCommand(cmd: List[String], execable: Execable[String, ExecWatch]): ExecWatch =
    execable.exec(cmd: _*)

  private def createKeytab(
    realm: String,
    adminPwd: String,
    exe: Execable[String, ExecWatch],
    credentials: Credentials,
    keytab: Path
  ) = {
    val cmd = credentials.secret match {
      case KeytabAndPassword(_) => cfg.commands.addKeytab.noRandomKey
      case _ => cfg.commands.addKeytab.randomKey
    }

    val keytabCmd = cmd
      .replaceAll("\\$realm", realm)
      .replaceAll("\\$path", keytab.toString)
      .replaceAll("\\$username", credentials.username)
    val addKeytab = s"echo '$adminPwd' | $keytabCmd"
    runCommand(List("bash", "-c", addKeytab), exe)
  }

  private def createPrincipal(realm: String, adminPwd: String, exe: Execable[String, ExecWatch], cred: Credentials) = {
    val addCmd = cfg.commands.addPrincipal
      .replaceAll("\\$realm", realm)
      .replaceAll("\\$username", cred.username)
      .replaceAll("\\$password", cred.password)
    val addPrincipal = s"echo '$adminPwd' | $addCmd"
    runCommand(List("bash", "-c", addPrincipal), exe)
  }

  private def getPassword(password: Password): String =
    password match {
      case Static(v) => v
      case _ => randomString
    }

  private def randomString =
    Random.alphanumeric.take(10).mkString
}
