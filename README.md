# Kerberos Operator

This operator deployes test KDC and Kadmin instances for development environment.

Use case:
- Application requires Kerberos authentication
- Kerberos needs to be deployed fast for development or PoC purposes

Below `Kerb` CRD creates:
- KDC and Kadmin servers running in a single POD
- Principals and their keytabs based on the principal list 

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
      password: static
      value: mypass
      keytab: cluster.keytab
      secret: cluster-keytab-secret
    - name: user2
      keytab: cluster.keytab
      secret: cluster-keytab-secret
```

## Kubernetes objects

Above spec will produce the following objects in `test` namespace:
 
### Secret 

Secret with a keytab as data:

```bash
kubectl describe secret cluster-keytab-secret  -n test
Name:         cluster-keytab-secret
Namespace:    test
Labels:       app=krb
Annotations:  <none>

Type:  opaque

Data
====
cluster.keytab:  136 bytes
```

principals.secret in the spec can be different, so that it will lead to multiple/different secrets.

### Service

A Service for KDC, kadmin, kpasswd:  

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