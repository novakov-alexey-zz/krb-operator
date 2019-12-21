#!/usr/bin/env bash
docker run \
  -e APP_CONFIG_PATH=/opt/docker/resources/application.conf \
  -e KRB5_TEMPLATE_PATH=/opt/docker/resources/krb5-openshift-template.yaml \
  -v ~/.kube:/home/demiourgos728/.kube \
  -e K8S_SPECS_DIR=/opt/docker/resources \
  krb-operator:0.1