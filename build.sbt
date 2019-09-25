name := "krb-operator"
version := "0.1"
scalaVersion := "2.13.1"
ThisBuild / organization := "io.github.novakov-alexey"
ThisBuild / turbo := true

resolvers += "Local Maven Repository" at "file://"+Path.userHome.absolutePath+"/.ivy2/local"

lazy val root = (project in file("."))
  .settings(
    libraryDependencies ++= Seq(
      operatorLib,
      codecs,
      osClient,
      scalaLogging,
      logbackClassic,
      pureConfig
    ),
    dockerBaseImage := "centos:centos7",
  )
  .enablePlugins(JavaAppPackaging)