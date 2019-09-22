package io.github.novakovalexey.krboperator

import java.io.File

import io.fabric8.kubernetes.api.model.KubernetesList
import io.fabric8.openshift.client.{OpenShiftClient, ParameterValue}

class Template(client: OpenShiftClient, operatorCfg: KrbOperatorCfg) {

  private val template = new File(operatorCfg.templatePath)

  private def params(kdcName: String, realm: String): List[ParameterValue] = List(
    new ParameterValue("KDC_SERVER", kdcName),
    new ParameterValue("KRB5_IMAGE", operatorCfg.image),
    new ParameterValue("KRB5_REALM", realm),
    new ParameterValue("PREFIX", "operator"),
  )

  def resources(kdcName: String, realm: String): KubernetesList = {
    client.templates
      .load(template)
      .process(params(kdcName, realm): _*)
  }
}
