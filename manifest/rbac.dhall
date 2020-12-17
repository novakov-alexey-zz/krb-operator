let schemas =
      https://raw.githubusercontent.com/dhall-lang/dhall-kubernetes/a4126b7f8f0c0935e4d86f0f596176c41efbe6fe/schemas.dhall sha256:7af88fa1a08f6bfafac2a96caa417b924b84eddab589ea715a9d5f333803d771

let union =
      https://raw.githubusercontent.com/dhall-lang/dhall-kubernetes/master/typesUnion.dhall sha256:d7b8c9c574f3c894fa2bca9d9c2bec1fea972bb3acdde90e473bc2d6ee51b5b1

let namespace = env:NAMESPACE as Text ? "test"

let operatorName = "krb-operator"

let sa =
      schemas.ServiceAccount::{
      , metadata = schemas.ObjectMeta::{
        , labels = Some [ { mapKey = "app", mapValue = operatorName } ]
        , name = Some operatorName
        , namespace = Some namespace
        }
      }

let roleBinding =
      schemas.RoleBinding::{
      , kind = "ClusterRoleBinding"
      , metadata = schemas.ObjectMeta::{
        , labels = Some [ { mapKey = "app", mapValue = operatorName } ]
        , name = Some operatorName
        }
      , roleRef = schemas.RoleRef::{
        , apiGroup = "rbac.authorization.k8s.io"
        , kind = "ClusterRole"
        , name = operatorName
        }
      , subjects = Some
        [ schemas.Subject::{
          , kind = "ServiceAccount"
          , name = operatorName
          , namespace = Some namespace
          }
        ]
      }

let clusterRole =
      schemas.ClusterRole::{
      , metadata = schemas.ObjectMeta::{ name = Some operatorName }
      , rules = Some
        [ schemas.PolicyRule::{
          , apiGroups = Some [ "", "authorization.k8s.io", "extensions" ]
          , resources = Some
            [ "pods"
            , "services"
            , "configmaps"
            , "secrets"
            , "pods/exec"
            , "deploymentconfigs"
            ]
          , verbs = [ "get", "watch", "list", "create", "update", "delete" ]
          }
        , schemas.PolicyRule::{
          , apiGroups = Some [ "" ]
          , resources = Some [ "namespaces" ]
          , verbs = [ "get", "watch", "list" ]
          }
        , schemas.PolicyRule::{
          , apiGroups = Some [ "apiextensions.k8s.io" ]
          , resources = Some [ "customresourcedefinitions" ]
          , verbs = [ "get", "watch", "list", "create" ]
          }
        , schemas.PolicyRule::{
          , apiGroups = Some [ "krb-operator.novakov-alexey.github.io" ]
          , resources = Some
            [ "krbservers"
            , "krbservers/status"
            , "principalss"
            , "principalss/status"
            ]
          , verbs = [ "watch", "list", "update" ]
          }
        , schemas.PolicyRule::{
          , apiGroups = Some [ "apps.openshift.io" ]
          , resources = Some [ "deploymentconfigs" ]
          , verbs = [ "get", "create", "list", "delete" ]
          }
        , schemas.PolicyRule::{
          , apiGroups = Some [ "", "apps", "extensions" ]
          , resources = Some [ "deployments" ]
          , verbs = [ "get", "create", "list", "delete" ]
          }
        ]
      }

in  { apiVersion = "v1"
    , kind = "List"
    , items =
      [ union.ServiceAccount sa
      , union.RoleBinding roleBinding
      , union.ClusterRole clusterRole
      ]
    }
