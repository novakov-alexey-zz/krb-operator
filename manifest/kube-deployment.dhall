-- use local imports for development or local operator repo
-- let k8s = ./manifest/k8s.dhall
-- use remote import when dhall files from GitHub
let k8s = https://raw.githubusercontent.com/novakov-alexey/krb-operator/master/manifest/k8s.dhall

let schemas = k8s.schemas

let union = k8s.union

let deploymentName = "krb-operator"

let version = env:OPERATOR_VERSION as Text ? "0.4.17" 

let deployment =
      schemas.Deployment::{
      , metadata = schemas.ObjectMeta::{ name = Some deploymentName }
      , spec = Some schemas.DeploymentSpec::{
        , replicas = Some +1
        , selector = schemas.LabelSelector::{
          , matchLabels = Some
            [ { mapKey = "deployment", mapValue = deploymentName } ]
          }
        , template = schemas.PodTemplateSpec::{
          , metadata = Some schemas.ObjectMeta::{
            , labels = Some
              [ { mapKey = "deployment", mapValue = deploymentName } ]
            }
          , spec = Some schemas.PodSpec::{
            , containers =
              [ schemas.Container::{
                , env = Some
                  [ schemas.EnvVar::{
                    , name = "KRB5_IMAGE"
                    , value = Some "alexeyn/krb5:latest"
                    }
                  , schemas.EnvVar::{
                    , name = "APP_CONFIG_PATH"
                    , value = Some "/opt/docker/resources/application.conf"
                    }
                  , schemas.EnvVar::{
                    , name = "K8S_SPECS_DIR"
                    , value = Some "/opt/docker/resources"
                    }
                  , schemas.EnvVar::{ name = "NAMESPACE", value = Some "ALL" }
                  , schemas.EnvVar::{
                    , name = "LOGBACK_CONFIG_FILE"
                    , value = Some "/opt/conf/logback.xml"
                    }
                  , schemas.EnvVar::{
                    , name = "PARALLEL_SECRET_CREATION"
                    , value = Some "false"
                    }
                  ]
                , image = Some "alexeyn/kerberos-operator:${version}"
                , imagePullPolicy = Some "Always"
                , livenessProbe = Some schemas.Probe::{
                  , exec = Some schemas.ExecAction::{
                    , command = Some [ "pgrep", "-fl", "kerberos-operator" ]
                    }
                  , initialDelaySeconds = Some +20
                  , periodSeconds = Some +20
                  }
                , name = deploymentName
                , volumeMounts = Some
                  [ schemas.VolumeMount::{
                    , mountPath = "/opt/conf/logback.xml"
                    , name = "logback-xml"
                    , subPath = Some "logback.xml"
                    }
                  ]
                }
              ]
            , serviceAccountName = Some deploymentName
            , volumes = Some
              [ schemas.Volume::{
                , configMap = Some schemas.ConfigMapVolumeSource::{
                  , defaultMode = Some +511
                  , name = Some "krb-logback"
                  }
                , name = "logback-xml"
                }
              ]
            }
          }
        }
      }

let logbackCm =
      schemas.ConfigMap::{
      , data = Some
        [ { mapKey = "logback.xml"
          , mapValue =
              ''
                <configuration scan="true" scanPeriod="60 seconds">
                  <contextListener class="ch.qos.logback.classic.jul.LevelChangePropagator">
                      <resetJUL>true</resetJUL>
                  </contextListener>

                  <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                      <withJansi>true</withJansi>
                      <encoder>
                          <pattern>%highlight(%date{yyyy-MM-dd HH:mm:ss.SSSZ, UTC}) [%thread] %highlight(%-5level) %cyan(%logger{15}) - %msg %n</pattern>
                      </encoder>
                  </appender>

                  <appender name="ASYNCSTDOUT" class="ch.qos.logback.classic.AsyncAppender">
                      <appender-ref ref="STDOUT"/>
                  </appender>

                  <logger name="io.fabric8.kubernetes.client" level="ERROR" />
                  <logger name="io.fabric8.kubernetes.client.internal" level="ERROR" />

                  <root level="INFO">
                      <appender-ref ref="ASYNCSTDOUT"/>
                  </root>
                </configuration>
              ''
          }
        ]
      , metadata = schemas.ObjectMeta::{ name = Some "krb-logback" }
      }

in  { apiVersion = "v1"
    , kind = "List"
    , items =
      [ union.ConfigMap logbackCm      
      , union.Deployment deployment
      ]
    }
