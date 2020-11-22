#!/usr/bin/env bash

kubectl create ns test || kubectl create ns test2

command=$1
echo "command: $command"
kubectl $command -f my-krb-1.yaml -n test &
kubectl $command -f my-krb-2.yaml -n test2