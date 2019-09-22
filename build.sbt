name := "krb-operator"
version := "0.1"
scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"
ThisBuild / turbo := true

resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.ivy2/local"

lazy val root = (project in file("."))
  .settings(
    libraryDependencies ++= Seq(
      "io.github.novakov-alexey" %% "k8s-operator4s-core" % "0.1.0-SNAPSHOT",
      osClient,
      scalaLogging,
      logbackClassic,
      pureConfig
    ),
    dockerBaseImage := "centos:centos7",
  )
  .enablePlugins(JavaAppPackaging)