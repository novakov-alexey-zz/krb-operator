kubectl create ns test || kubectl create ns test2 

kubectl delete -f my-krb-1.yaml -n test &
kubectl delete -f my-krb-2.yaml -n test2