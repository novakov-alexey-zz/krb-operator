-- use local imports for development or local operator repo
-- let k8s = ./manifest/k8s.dhall
-- use remote import when dhall files from GitHub
let k8s = https://raw.githubusercontent.com/novakov-alexey/krb-operator/master/manifest/k8s.dhall

let schemas = k8s.schemas

let union = k8s.union

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
