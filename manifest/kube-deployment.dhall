let schemas =
      https://raw.githubusercontent.com/dhall-lang/dhall-kubernetes/a4126b7f8f0c0935e4d86f0f596176c41efbe6fe/schemas.dhall

let union =
      https://raw.githubusercontent.com/dhall-lang/dhall-kubernetes/master/typesUnion.dhall sha256:d7b8c9c574f3c894fa2bca9d9c2bec1fea972bb3acdde90e473bc2d6ee51b5b1

let deploymentName = "krb-operator"

let deployment =
      schemas.Deployment::{
      , metadata = schemas.ObjectMeta::{ name = Some deploymentName }
      , spec = Some schemas.DeploymentSpec::{
        , replicas = Some 1
        , selector = schemas.LabelSelector::{
          , matchLabels = Some
            [ { mapKey = "deployment", mapValue = deploymentName } ]
          }
        , template = schemas.PodTemplateSpec::{
          , metadata = schemas.ObjectMeta::{
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
                , image = Some "alexeyn/kerberos-operator:0.4.16"
                , imagePullPolicy = Some "Always"
                , livenessProbe = Some schemas.Probe::{
                  , exec = Some schemas.ExecAction::{
                    , command = Some [ "pgrep", "-fl", "kerberos-operator" ]
                    }
                  , initialDelaySeconds = Some 20
                  , periodSeconds = Some 20
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
                  , defaultMode = Some 777
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

let logbackGraalVmCm =
      schemas.ConfigMap::{
      , data = Some
        [ { mapKey = "logback.xml"
          , mapValue =
              ''
              <configuration>
                <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                    <!-- On Windows machines setting withJansi to true enables ANSI
                        color code interpretation by the Jansi library. This requires
                        org.fusesource.jansi:jansi:1.8 on the class path.  Note that
                        Unix-based operating systems such as Linux and Mac OS X
                        support ANSI color codes by default. -->
                    <withJansi>true</withJansi>
                    <encoder>
                        <pattern>%highlight(%date{yyyy-MM-dd HH:mm:ss.SSSZ, UTC}) [%thread] %highlight(%-5level) %cyan(%logger{15}) - %msg %n</pattern>
                    </encoder>
                </appender>
                <root level="INFO">
                    <appender-ref ref="STDOUT" />
                </root>
              </configuration>
              ''
          }
        ]
      , metadata = schemas.ObjectMeta::{
        , name = Some "krb-logback-graal-native"
        }
      }

in  { apiVersion = "v1"
    , kind = "List"
    , items =
      [ union.Deployment deployment
      , union.ConfigMap logbackCm
      , union.ConfigMap logbackGraalVmCm
      ]
    }