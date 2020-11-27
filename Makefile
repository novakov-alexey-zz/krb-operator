NAMESPACE=test

release:
	sbt -mem 2048 compile test 'release with-defaults'

run-docker:
	docker run \
	  -e APP_CONFIG_PATH=/opt/docker/resources/application.conf \
	  -v ~/.kube:/home/demiourgos728/.kube \
	  -e K8S_SPECS_DIR=/opt/docker/resources \
	  alexeyn/kerberos-operator:0.4.12

run-graal-docker:
	docker run \
	  -e APP_CONFIG_PATH=/opt/docker/resources/application.conf \
	  -e LOGBACK_CONFIG_FILE=/opt/conf/logback.xml \
	  -v /home/an/.kube/config:/root/.kube/config \
	  -v ~/dev/git/krb-operator/src/main/resources:/opt/docker/resources \
	  -v ~/dev/git/krb-operator/src/main/resources/logback.xml:/opt/conf/logback.xml \
	  -e K8S_SPECS_DIR=/opt/docker/resources \
	  kerberos-operator:0.4.11-graal-native

docker-local-build:
	sbt docker:publishLocal

create-krb-server:
	kubectl create -f examples/my-krb-server-1.yaml -n $(NAMESPACE)
delete-krb-server:
	kubectl delete -f examples/my-krb-server-1.yaml -n $(NAMESPACE)
create-krb-principals:
	kubectl create -f examples/my-principals-1.yaml -n $(NAMESPACE)
delete-krb-principals:
	kubectl delete -f examples/my-principals-1.yaml -n $(NAMESPACE)