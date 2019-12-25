#!/usr/bin/env bash
docker run \
  -e APP_CONFIG_PATH=/opt/docker/resources/application.conf \
  -v ~/.kube:/home/demiourgos728/.kube \
  -e K8S_SPECS_DIR=/opt/docker/resources \
  alexeyn/kerberos-operator:0.1