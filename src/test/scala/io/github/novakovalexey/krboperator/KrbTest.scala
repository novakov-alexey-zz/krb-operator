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
import io.fabric8.openshift.client.OpenShiftClient
import io.fabric8.openshift.client.server.mock.OpenShiftServer
import io.github.novakovalexey.krboperator.Generators._
import io.github.novakovalexey.krboperator.service.Secrets.principalSecretLabel
import io.github.novakovalexey.krboperator.service._
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.propspec.AnyPropSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._

class KrbTest
    extends AnyPropSpec
    with BeforeAndAfter
    with Matchers
    with ContextShiftTest
    with ScalaCheckPropertyChecks {

  val testPod = "test-pod"
  val tempDir = "tempDir"

  object expectations {
    def forApply(
      server: OpenShiftServer,
      meta: Metadata,
      podName: String,
      cfg: KrbOperatorCfg,
      krb: Krb,
      tempDir: String
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
        .always()

      server
        .expect()
        .post()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/services")
        .andReturn(200, new ServiceBuilder().build())
        .always()

      server
        .expect()
        .post()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/secrets")
        .andReturn(200, new SecretBuilder().build())
        .always()

      server
        .expect()
        .post()
        .withPath(s"/apis/extensions/v1beta1/namespaces/${meta.namespace}/deployments")
        .andReturn(200, new DeploymentBuilder().build())
        .always()

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
        .always()

      server
        .expect()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/secrets/${cfg.adminPwd.secretName}")
        .andReturn(
          200,
          new SecretBuilder()
            .withNewMetadata()
            .withName(cfg.adminPwd.secretName)
            .withLabels(principalSecretLabel.asJava)
            .endMetadata()
            .withType("opaque")
            .addToStringData(cfg.adminPwd.secretKey, Base64.getEncoder.encodeToString("fsdfsdfdsf".getBytes))
            .build()
        )
        .always()

      krb.principals.foreach { p =>
        server
          .expect()
          .withPath(
            s"/api/v1/namespaces/${meta.namespace}/pods/$podName/exec?command=sh&command=-c&command=cat+%2Ftmp%2F$tempDir%2F${p.keytab}%7Cbase64&container=${cfg.kadminContainer}&stdout=true"
          )
          .andUpgradeToWebSocket()
          .open(new OutputStreamMessage(""))
          .done()
          .always()
      }
    }

    def forDelete(server: OpenShiftServer, meta: Metadata, cfg: KrbOperatorCfg): Unit = {
      server
        .expect()
        .withPath(s"/apis/apps/v1/namespaces/${meta.namespace}/deployments/${meta.name}")
        .andReturn(200, new DeploymentBuilder().build())
        .always()

      server
        .expect()
        .post()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/services")
        .andReturn(200, new ServiceBuilder().build())
        .always()

      server
        .expect()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/secrets?labelSelector=${Utils
          .toUrlEncoded(s"${Secrets.principalSecretLabel.map { case (k, v) => s"$k=$v" }.mkString("")}")}")
        .andReturn(200, new SecretListBuilder().build())
        .always()

      server
        .expect()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/secrets/${cfg.adminPwd.secretName}")
        .andReturn(
          200,
          new SecretBuilder()
            .withNewMetadata()
            .withName(cfg.adminPwd.secretName)
            .withLabels(principalSecretLabel.asJava)
            .endMetadata()
            .withType("opaque")
            .addToStringData(cfg.adminPwd.secretKey, Base64.getEncoder.encodeToString("fsdfsdfdsf".getBytes))
            .build()
        )
        .always()

      server
        .expect()
        .get()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/services/${meta.name}")
        .andReturn(200, new ServiceBuilder().withNewMetadata().withName(meta.name).endMetadata().build())
        .always()

      server
        .expect()
        .delete()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/services/${meta.name}")
        .andReturn(200, new ServiceBuilder().build())
        .always()
    }
  }

  property("create Kerberos and principals") {
    forAll(Generators.krb, Generators.meta, arbitrary[Boolean]) { (krb, meta, isAdd) =>
      //given
      val server = startServer
      val mod = createModule(meta, server)

      expectations.forApply(server, meta, testPod, mod.operatorCfg, krb, tempDir)
      val controller = createController(mod)

      // when
      val res = if (isAdd) controller.onAdd(krb, meta) else controller.onModify(krb, meta)
      //then
      res.unsafeRunSync()

      stopServer(server)
    }
  }

  property("delete Kerberos and principals") {
    forAll(Generators.krb, Generators.meta, arbitrary[Boolean]) { (krb, meta, isAdd) =>
      //given
      val server = startServer
      val mod = createModule(meta, server)

      expectations.forDelete(server, meta, mod.operatorCfg)
      val controller = createController(mod)

      // when
      val res = controller.onDelete(krb, meta)
      //then
      res.unsafeRunSync()

      stopServer(server)
    }
  }

  private def createController(mod: Module[IO]) = {
    implicit val resource: DeploymentResource[Deployment] = mockDeployment
    val controller = mod.controllerFor(mod.k8sTemplate, parallelSecret = false)
    controller
  }

  private def createModule(meta: Metadata, server: OpenShiftServer) = {
    implicit val pods: Pods[IO] = mockPods(testPod, meta)
    implicit val pathGen: KeytabPathAlg = (_: String, name: String) => s"/tmp/$tempDir/$name"

    new Module[IO](server.getOpenshiftClient)
  }

  private def stopServer(server: OpenShiftServer): Unit =
    server.after()

  private def startServer = {
    val server = new OpenShiftServer(false, false)
    server.before()
    server
  }

  private def mockDeployment =
    new K8sDeploymentResource {
      override def isDeploymentReady(resource: Deployment): Boolean = true

      override def delete(client: OpenShiftClient, d: Deployment): Boolean = true
    }

  private def mockPods(testPod: String, meta: Metadata) =
    new Pods[IO] {
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

      override def executeInPod(client: KubernetesClient, containerName: String)(namespace: String, podName: String)(
        commands: Execable[String, ExecWatch] => List[ExecWatch]
      ): IO[Unit] =
        IO {
          namespace should ===(meta.namespace)
          podName should ===(testPod)
        }
    }
}
