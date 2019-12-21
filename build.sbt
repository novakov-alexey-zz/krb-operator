import NativePackagerHelper._

name := "kerberos-operator"
version := "0.1"
scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"
ThisBuild / turbo := true

resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.ivy2/local"

lazy val root = (project in file("."))
  .settings(
    libraryDependencies ++= Seq(
      freya,
      codecs,
      osClient,
      scalaLogging,
      logbackClassic,
      pureConfig,
    ),
    dockerBaseImage := "openjdk:8-jre-alpine",
    dockerRepository := Some("alexeyn")
  )
  .enablePlugins(AshScriptPlugin)

mappings in Universal ++= directory("src/main/resources")