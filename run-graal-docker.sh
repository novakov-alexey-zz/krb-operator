#!/usr/bin/env bash
docker run \
  -e APP_CONFIG_PATH=/opt/docker/resources/application.conf \
  -e LOGBACK_CONFIG_FILE=/opt/conf/logback.xml \
  -e KUBERNETES_MASTER=https://kss-dev-env-adv-dns-a3625708.hcp.northeurope.azmk8s.io \
  -v /home/an/.kube/config:/root/.kube/config \
  -v ~/dev/git/krb-operator/src/main/resources:/opt/docker/resources \
  -v ~/dev/git/krb-operator/src/main/resources/logback.xml:/opt/conf/logback.xml \
  -e K8S_SPECS_DIR=/opt/docker/resources \
  kerberos-operator:0.4.10-SNAPSHOT-graal-native
