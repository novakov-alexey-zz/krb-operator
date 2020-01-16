package io.github.novakovalexey.krboperator

import java.util.Base64

import cats.effect.IO
import freya.Metadata
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.api.model.apps.{Deployment, DeploymentBuilder}
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.dsl.{ExecWatch, Execable}
import io.fabric8.kubernetes.client.server.mock.OutputStreamMessage
import io.fabric8.kubernetes.client.utils.Utils
import io.fabric8.openshift.client.server.mock.OpenShiftServer
import io.github.novakovalexey.krboperator.Password.Static
import io.github.novakovalexey.krboperator.Secret.Keytab
import io.github.novakovalexey.krboperator.service.Secrets.principalSecretLabel
import io.github.novakovalexey.krboperator.service._
import org.scalatest.BeforeAndAfter
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

class KrbTest extends AnyFlatSpec with BeforeAndAfter with Matchers with ContextShiftTest {

  val server = new OpenShiftServer(false, false)

  before {
    server.before()
  }

  after {
    server.after()
  }

  object expectations {
    def toSuccess(
      meta: Metadata,
      podName: String,
      adminPwd: AdminPassword,
      keytabName: String,
      containerName: String,
      krb: Krb
    ): Unit = {
      server
        .expect()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/pods?labelSelector=${Utils
          .toUrlEncoded(s"${Template.DeploymentSelector}=${meta.name}")}")
        .andReturn(
          200,
          new PodListBuilder()
            .withItems(
              new PodBuilder()
                .withNewMetadata()
                .withName(podName)
                .endMetadata()
                .build()
            )
            .build()
        )
        .once()

      server
        .expect()
        .post()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/services")
        .andReturn(200, new ServiceBuilder().build())
        .once()

      server
        .expect()
        .post()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/secrets")
        .andReturn(200, new SecretBuilder().build())
        .once()

      server
        .expect()
        .post()
        .withPath(s"/apis/extensions/v1beta1/namespaces/${meta.namespace}/deployments")
        .andReturn(200, new DeploymentBuilder().build())
        .once()

      server
        .expect()
        .withPath(s"/apis/apps/v1/namespaces/${meta.namespace}/deployments/${meta.name}")
        .andReturn(404, "")
        .once()

      server
        .expect()
        .withPath(s"/apis/apps/v1/namespaces/${meta.namespace}/deployments/${meta.name}")
        .andReturn(200, new DeploymentBuilder().build())
        .always()

      server
        .expect()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/secrets?labelSelector=${Utils
          .toUrlEncoded(s"${Secrets.principalSecretLabel.map { case (k, v) => s"$k=$v" }.mkString("")}")}")
        .andReturn(200, new SecretListBuilder().build())
        .once()

      server
        .expect()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/secrets/${adminPwd.secretName}")
        .andReturn(
          200,
          new SecretBuilder()
            .withNewMetadata()
            .withName(adminPwd.secretName)
            .withLabels(principalSecretLabel.asJava)
            .endMetadata()
            .withType("opaque")
            .addToStringData(adminPwd.secretKey, Base64.getEncoder.encodeToString("fsdfsdfdsf".getBytes))
            .build()
        )
        .always()

      server
        .expect()
        .withPath(
          s"/api/v1/namespaces/${meta.namespace}/pods/$podName/exec?command=sh&command=-c&command=cat+%2Ftmp%2FtempDir%2F$keytabName%7Cbase64&container=$containerName&stdout=true"
        )
        .andUpgradeToWebSocket()
        .open(new OutputStreamMessage(""))
        .done()
        .once()

    }
  }

  it should "create Kerberos and principals" in {
    //given
    val keytabName = "test.keytab"
    val krb = Krb("EXAMPLE.COM", Principal("user1", Static("mypass"), keytabName, Keytab("test-secret")) :: Nil)
    val meta = Metadata("test-krb", "test")
    val podName = "test-pod"
    val containerName = "kadmin"

    val testPod = "test-pod"
    val tempDir = "tempDir"

    implicit val pods: Pods[IO] = new Pods[IO] {
      override def waitForPod(client: KubernetesClient)(
        metadata: Metadata,
        previewPod: Option[Pod] => IO[Unit],
        findPod: IO[Option[Pod]],
        duration: FiniteDuration
      ): IO[Option[Pod]] =
        IO {
          metadata should ===(meta)
          Some(new PodBuilder().withNewMetadata().withName(testPod).and().build())
        }

      override def executeInPod(client: KubernetesClient, containerName: String)(ns: String, podName: String)(
        commands: Execable[String, ExecWatch] => List[ExecWatch]
      ): IO[Unit] =
        IO {
          ns should ===(meta.namespace)
          podName should ===(testPod)
        }
    }

    implicit val pathGen: KeytabPathAlg = (_: String, name: String) => s"/tmp/$tempDir/$name"

    val client = server.getOpenshiftClient
    val mod = new Module[IO](client)

    expectations.toSuccess(meta, podName, mod.operatorCfg.adminPwd, keytabName, containerName, krb)

    implicit val resource: DeploymentResource[Deployment] = new K8sDeploymentResource {
      override def isDeploymentReady(resource: Deployment): Boolean = true
    }
    val controller = mod.controllerFor(mod.k8sTemplate)

    // when
    val res = controller.onAdd(krb, meta)
    //then
    res.unsafeRunSync()
  }
}
