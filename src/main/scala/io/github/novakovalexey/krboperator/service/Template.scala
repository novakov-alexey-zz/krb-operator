package io.github.novakovalexey.krboperator.service

import java.io.File

import io.fabric8.kubernetes.api.model.KubernetesList
import io.fabric8.openshift.client.OpenShiftClient
import io.github.novakovalexey.krboperator.KrbOperatorCfg

import scala.jdk.CollectionConverters._

class Template(client: OpenShiftClient, operatorCfg: KrbOperatorCfg) {

  private val template = new File(operatorCfg.templatePath)

  private def params(kdcName: String, realm: String) = Map(
    "KRB5_IMAGE" -> operatorCfg.krb5Image,
    "PREFIX" -> operatorCfg.k8sResourcesPrefix,
    "KDC_SERVER" -> kdcName,
    "KRB5_REALM" -> realm
  )

  def resources(kdcName: String, realm: String): KubernetesList = {
    client.templates
      .load(template)
      .process(params(kdcName, realm).asJava)
  }
}
