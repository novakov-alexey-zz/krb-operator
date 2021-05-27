package io.github.novakovalexey.krboperator

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import freya.models.Metadata
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

import java.util.Base64
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import freya.models

class KrbTest extends AnyPropSpec with BeforeAndAfter with Matchers with ScalaCheckPropertyChecks {

  val testPod = "test-pod"
  val tempDir = "tempDir"

  object expectations {
    def setup(
      server: OpenShiftServer,
      meta: Metadata,
      podName: String,
      cfg: KrbOperatorCfg,
      principals: Principals,
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
                .withStatus(
                  new PodStatusBuilder()
                    .withConditions(
                      new PodConditionBuilder()
                        .withType("Ready")
                        .withStatus("True")
                        .build()
                    )
                    .build()
                )
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

      mockDeployments(server, meta)
      mockSecrets(server, meta, cfg, principals.list.map(_.secret.name))
      mockPodExec(
        server,
        principals.list,
        (p: Principal) =>
          s"/api/v1/namespaces/${meta.namespace}/pods/$podName/exec?command=sh&command=-c&command=cat+%2Ftmp%2F$tempDir%2F${p.keytab}%7Cbase64&container=${cfg.kadminContainer}&stdout=true"
      )
      mockPodExec(
        server,
        principals.list,
        (_: Principal) =>
          s"/api/v1/namespaces/${meta.namespace}/pods/$podName/exec?command=mkdir&command=%2Ftmp%2F$tempDir&container=${cfg.kadminContainer}&tty=true&stdin=true&stdout=true&stderr=true"
      )
    }

    private def mockPodExec(server: OpenShiftServer, principals: List[Principal], path: Principal => String) = {
      principals.foreach { p =>
        val mockPath = path(p)
        server
          .expect()
          .withPath(mockPath)
          .andUpgradeToWebSocket()
          .open(new OutputStreamMessage(""))
          .done()
          .always()
      }
    }

    private def mockSecrets(server: OpenShiftServer, meta: Metadata, cfg: KrbOperatorCfg, secretNames: List[String]) = {
      val secrets = secretNames.map { n =>
        new SecretBuilder()
          .withNewMetadata()
          .withName(n)
          .withLabels(principalSecretLabel.asJava)
          .endMetadata()
          .withType("opaque")
          .build()
      }
      server
        .expect()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/secrets?labelSelector=${Utils
          .toUrlEncoded(s"${Secrets.principalSecretLabel.map { case (k, v) => s"$k=$v" }.mkString("")}")}")
        .andReturn(200, new SecretListBuilder().addToItems(secrets: _*).build())
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
            .addToData(cfg.adminPwd.secretKey, Base64.getEncoder.encodeToString("fsdfsdfdsf".getBytes))
            .build()
        )
        .always()

      server
        .expect()
        .post()
        .withPath(s"/api/v1/namespaces/${meta.namespace}/secrets")
        .andReturn(200, new SecretBuilder().build())
        .always()
    }

    private def mockDeployments(server: OpenShiftServer, meta: Metadata) = {
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
            .addToData(cfg.adminPwd.secretKey, Base64.getEncoder.encodeToString("fsdfsdfdsf".getBytes))
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
    forAll(Generators.customResource, arbitrary[Boolean]) { case ((krb, principals), isAdd) =>
      //given
      val server = startServer
      val mod = createModule(krb.metadata, server)

      expectations.setup(server, krb.metadata, testPod, mod.operatorCfg, principals.spec, tempDir)
      val (serverController, principalsController) = createController(mod, server.getOpenshiftClient)

      // when
      val res = if (isAdd) {
        serverController.onAdd(krb) *> principalsController.onAdd(principals)
      } else {
        serverController.onModify(krb) *> principalsController.onModify(principals)
      }
      //then
      res.unsafeRunSync()

      stopServer(server)
    }
  }

  property("delete Kerberos and principals") {
    forAll(Generators.customResource, arbitrary[Boolean]) { case ((krb, principals), _) =>
      //given
      val server = startServer
      val mod = createModule(krb.metadata, server)

      expectations.forDelete(server, krb.metadata, mod.operatorCfg)
      val (serverController, principalsController) = createController(mod, server.getOpenshiftClient)

      // when
      serverController.onDelete(krb).unsafeRunSync()
      principalsController.onDelete(principals).unsafeRunSync()

      stopServer(server)
    }
  }

  private def createController(mod: Module[IO], client: KubernetesClient) = {
    implicit val resource: DeploymentResource[Deployment] = mockDeployment
    val secrets = new Secrets[IO](client, mod.operatorCfg)
    implicit val pathGen: KeytabPathAlg = (_: String, name: String) => s"/tmp/$tempDir/$name"

    val kadmin = new Kadmin[IO](client, mod.operatorCfg)
    val openShiftClient = client.asInstanceOf[OpenShiftClient]

    val serverController =
      mod.serverControllerFor(mod.k8sTemplate(openShiftClient, secrets), secrets)

    val principalsController =
      new PrincipalsController(null, openShiftClient, secrets, kadmin, mod.operatorCfg, false) {
        override private[krboperator] def getKrbServer(
          meta: Metadata
        ): IO[models.CustomResource[KrbServer, KrbServerStatus]] =
          IO(models.CustomResource(Metadata("test-server", "test", Map.empty, "123", "uuid"), KrbServer("test"), None))
      }
    (serverController, principalsController)
  }

  private def createModule(meta: Metadata, server: OpenShiftServer) = {
    implicit val pods: Pods[IO] = mockPods(testPod, meta)
    new Module[IO](IO(server.getOpenshiftClient))
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
