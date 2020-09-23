# Kerberos Operator

![](https://github.com/novakov-alexey/krb-operator/workflows/Scala%20CI/badge.svg?branch=master)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/a82d2fa75a3d45828c98b11499d8be95)](https://www.codacy.com/manual/novakov.alex/krb-operator?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=novakov-alexey/krb-operator&amp;utm_campaign=Badge_Grade)
[![Docker Hub](https://img.shields.io/docker/v/alexeyn/kerberos-operator?color=blue&label=tag)]()
  
This operator deployes KDC, Kadmin servers and creates principals and their keytabs as secrets.
Developed using [Freya](https://github.com/novakov-alexey/freya) Scala library.

## Operator use cases

Why to use this Operator?

-   Your [SPNEGO](https://en.wikipedia.org/wiki/SPNEGO) authentication requires keytab mounted to a POD: 
deploy this operator with required principals to get automatically created secrets with keytabs inside
    
-   Rapid application development having KDC running inside the K8s cluster: deploy this operator and use 
automatically created service to call KDC or Kadmin servers

-   Principals and keytabs management using K8s custom resources: deploy this operator using Krb resource
with required list of principals and their predefined or random passwords 

## How to install

Define namespace as ENV variable:

```bash
NAMESPACE=<put desired namespace>
```

### On Kubernetes

```bash
# install RBAC
wget -O- -q https://raw.githubusercontent.com/novakov-alexey/krb-operator/master/manifest/rbac.yaml | \
 	sed  -e "s:{{NAMESPACE}}:${NAMESPACE}:g" | kubectl create -n ${NAMESPACE} -f -

# install operator
kubectl create \
    -f https://raw.githubusercontent.com/novakov-alexey/krb-operator/master/manifest/kube-deployment.yaml \
    -n ${NAMESPACE}
```

### On OpenShift

```bash
# install RBAC
wget -O- -q https://raw.githubusercontent.com/novakov-alexey/krb-operator/master/manifest/rbac.yaml | \
    sed  -e "s:{{NAMESPACE}}:${NAMESPACE}:g" |  oc create -n ${NAMESPACE} -f -

# install operator
oc create \
    -f https://raw.githubusercontent.com/novakov-alexey/krb-operator/master/manifest/openshift-deployment.yaml \
    -n ${NAMESPACE}
```

### Deploy Specific Operator Version

In ordet to to deploy specific version, clone above manifest files and change image tag for krb-operator container. For example:

```diff
-image: alexeyn/kerberos-operator:0.4.10
+image: alexeyn/kerberos-operator:0.4.11
```


### GraalVM Native Image 

There is also a parallel build of Kerberos Operator based GraalVM Native Image. Use Docker Image tag: "&lt;version&gt;-graal-native".
For example: `kerberos-operator:0.4.11-graal-native`.

Use this tag in Kubernetes or OpenShift manifests (see above).

## How to uninstall

```bash
wget -O- -q https://raw.githubusercontent.com/novakov-alexey/krb-operator/master/manifest/rbac.yaml | \
    sed  -e "s:{{NAMESPACE}}:${NAMESPACE}:g" | kubectl delete -n ${NAMESPACE} -f -
	
kubectl delete -f https://raw.githubusercontent.com/novakov-alexey/krb-operator/master/manifest/kube-deployment.yaml -n ${NAMESPACE}
kubectl delete crd krbs.io.github.novakov-alexey
```

## Custom Resource Definition

Below `Krb` CRD creates:

-   KDC and Kadmin servers running as two separate containers running in a single POD
-   Principals and their keytabs based on the principal list 

```yaml
apiVersion: io.github.novakov-alexey/v1
kind: Krb
metadata:
  name: my-krb
  namespace: test
spec:
  realm: EXAMPLE.COM
  principals:
    - name: client1
      password:
        type: static
        value: mypass
      keytab: cluster.keytab
      secret:
        type: Keytab
        name: cluster-keytab
    - name: user2
      keytab: cluster.keytab
      secret:
        type: KeytabAndPassword
        name: cluster-keytab
```

## Krb Spec

-   `realm` - Kerberos realm where all principals will be created
-   `principals` - array of principals 

Principal properties:

-   `name` - principal name without realm in it. Realm will be added automatically using value of `spec.realm` property

-   `password` - a property with two different types. 

    `static`: with password in the value field. 
    
    `random`: operator generates random password. it does not require password property in the resource at all.    
    
    Missing password property or default value is `random`.     

-   `keytab` - it is key in the secret object. Secret can have more than one data keys, i.e. more than one keytab files

-   `secret` - a property with two different types. 
    
    `Keytab` - create keytab as K8s Secret, `name` is the Secret name.
    
    `KeytabAndPassword` - create keytab with separate password entry as K8s Secret, `name` is the Secret name, 
    `principal[i].name` is a key of a secret for principal password
     
     K8s secret name. Every principal in the array can have its own secret name, so that multiple secrets will be created

## Kubernetes objects

Above spec will produce the following objects in the metadata.namespace, i.e. `test` namespace:

### Secret

Containing Kerberos keytab as secret data:

```bash
kubectl describe secret cluster-keytab  -n test
Name:         cluster-keytab
Namespace:    test
Labels:       app=krb
Annotations:  <none>

Type:  opaque

Data
====
cluster.keytab:  274 bytes
user2:           10 bytes
```

Property `principals.secret` in the `Krb` spec can be different, so that it will lead to multiple/different 
secrets created by the Kerberos Operator.

### Service

A Service for KDC, kadmin, kpasswd with their TCP/UDP ports:  

```bash
my-krb   ClusterIP   172.30.37.134  <none>  88/TCP,88/UDP,464/UDP,749/UDP,749/TCP
```

### POD

A POD for KDC, kadmin, kpasswd servers with two containers:

```bash
kubectl get pod my-krb-1-gk52x -n test

NAME             READY   STATUS    RESTARTS   AGE
my-krb-1-gk52x   2/2     Running   0          24m
```

POD is deployed as part of Deployment:

```bash
NAME                     READY   UP-TO-DATE   AVAILABLE   AGE
deployment.apps/my-krb   1/1     1            1           26s
```

## Create or Update resource

Examples:

```bash
kubectl create -f examples/my-krb-1.yaml
# or
kubectl apply -f examples/my-krb-1.yaml
```

Create or Update resource events are handled in the same way and will create:

-   Deployment, Service, POD, if some of them is missing

-   Kerberos principal, if its `spec.principals[i].secret` is missing. 
    Changes in values other than `secret` are ignored (current limitation). In order to add new principal to the 
    `spec.principals` either put new/not-existing `secret` name and desired new principal name. Otherwise, delete Krb resource and create new one with 
    the desired `principals`.   

## Delete resource

Delete events deletes all objects created by create or apply events: Deployment, Service, POD and Secrets(s)

```bash
kubectl delete -f examples/my-krb-1.yaml
```
