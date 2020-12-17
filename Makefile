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

install-rbac:
	dhall-to-yaml < manifest/rbac.dhall | kubectl create -n ${NAMESPACE} -f -
uninstall-rbac:
	dhall-to-yaml < manifest/rbac.dhall | kubectl delete -n ${NAMESPACE} -f -

install: install-rbac	
	dhall-to-yaml < manifest/kube-deployment.dhall | kubectl create -n ${NAMESPACE} -f -	
uninstall: uninstall-rbac
	dhall-to-yaml < manifest/kube-deployment.dhall | kubectl delete -n ${NAMESPACE} -f -	

install-os: install-rbac	
	dhall-to-yaml < manifest/kube-deployment.dhall | kubectl create -n ${NAMESPACE} -f -	
uninstall-os: uninstall-rbac	
	dhall-to-yaml < manifest/kube-deployment.dhall | kubectl delete -n ${NAMESPACE} -f -	

to-dhall:
	yaml-to-dhall '(https://raw.githubusercontent.com/dhall-lang/dhall-kubernetes/a4126b7f8f0c0935e4d86f0f596176c41efbe6fe/types.dhall).ConfigMap' --file ./manifest/openshift-deployment.yaml \
	  | dhall rewrite-with-schemas --schemas 'https://raw.githubusercontent.com/dhall-lang/dhall-kubernetes/a4126b7f8f0c0935e4d86f0f596176c41efbe6fe/schemas.dhall'
