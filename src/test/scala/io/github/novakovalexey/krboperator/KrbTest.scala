package io.github.novakovalexey.krboperator

import java.util.Base64

import cats.effect.IO
import freya.Metadata
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.api.model.apps.{Deployment, DeploymentBuilder}
import io.fabric8.kubernetes.client.KubernetesClient
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

  it should "create Kerberos and principals" in {
    //given
    val namespace = "test"
    val krb = Krb("EXAMPLE.COM", Principal("user1", Static("mypass"), "test.keytab", Keytab("test-secret")) :: Nil)
    val metadataName = "test-krb"
    val podName = "test-pod"
    server
      .expect()
      .withPath(s"/api/v1/namespaces/$namespace/pods?labelSelector=${Utils
        .toUrlEncoded(s"${Template.DeploymentSelector}=$metadataName")}")
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
      .always()

    server
      .expect()
      .post()
      .withPath(s"/api/v1/namespaces/$namespace/services")
      .andReturn(200, new ServiceBuilder().build())
      .always()

    server
      .expect()
      .post()
      .withPath(s"/api/v1/namespaces/$namespace/secrets")
      .andReturn(200, new SecretBuilder().build())
      .always()

    server
      .expect()
      .post()
      .withPath(s"/apis/extensions/v1beta1/namespaces/$namespace/deployments")
      .andReturn(200, new DeploymentBuilder().build())
      .always()

    server
      .expect()
      .withPath(s"/apis/apps/v1/namespaces/$namespace/deployments/$metadataName")
      .andReturn(404, "")
      .once()

    server
      .expect()
      .withPath(s"/apis/apps/v1/namespaces/$namespace/deployments/$metadataName")
      .andReturn(200, new DeploymentBuilder().build())
      .always()

    server
      .expect()
      .withPath(s"/api/v1/namespaces/$namespace/secrets?labelSelector=${Utils
        .toUrlEncoded(s"${Secrets.principalSecretLabel.map { case (k, v) => s"$k=$v" }.mkString("")}")}")
      .andReturn(200, new SecretListBuilder().build())
      .always()

    val adminSecretName = "operator-krb-admin-pwd"
    val adminSecretKey = "krb5_pass"
    server
      .expect()
      .withPath(s"/api/v1/namespaces/$namespace/secrets/$adminSecretName")
      .andReturn(
        200,
        new SecretBuilder()
          .withNewMetadata()
          .withName(adminSecretName)
          .withLabels(principalSecretLabel.asJava)
          .endMetadata()
          .withType("opaque")
          .addToStringData(adminSecretKey, Base64.getEncoder.encodeToString("fsdfsdfdsf".getBytes))
          .build()
      )
      .always()

    implicit val pods: Pods[IO] = new Pods[IO] {
      override def waitForPod(client: KubernetesClient)(
        meta: Metadata,
        previewPod: Option[Pod] => IO[Unit],
        findPod: IO[Option[Pod]],
        duration: FiniteDuration
      ): IO[Option[Pod]] = IO.pure(Some(new PodBuilder().withNewMetadata().withName("test-pod").and().build()))
    }

    val client = server.getOpenshiftClient
    val mod = new Module[IO](client)
    implicit val resource: DeploymentResource[Deployment] = new K8sDeploymentResource {
      override def isDeploymentReady(resource: Deployment): Boolean = true
    }
    val controller = mod.controllerFor(mod.k8sTemplate)

    // when
    val res = controller.onAdd(krb, Metadata(metadataName, namespace))
    //then
    res.unsafeRunSync()
  }
}
