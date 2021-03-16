import sbt._

object Dependencies extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    object DependenciesVersion {
      val catsVersion                      = "2.1.1"
      val circeVersion                     = "0.13.0"
      val circeExtrasVersion               = "0.13.0"

      val logbackClassicVersion            = "1.3.0-alpha4"
      val janinoVersion                    = "3.1.2"

      val pureConfigVersion                = "0.14.1"
      val scalaLoggingVersion              = "3.9.2"
      val fabric8K8sVersion                = "5.2.1"
      val codecsVersion                    = "1.15"
      val jacksonJsonSchemaV               = "1.0.39"
      val jacksonScalaVersion              = "2.12.2"
      val betterMonadicVersion             = "0.3.1"
      val freyaVersion                     = "0.2.12"
      val scalaTestVersion                 = "3.2.6"
      val scalaTestCheckVersion            = "3.1.0.0-RC2"
      val scalaCheckVersion                = "1.15.3"
    }

    import DependenciesVersion._

    val cats                     = "org.typelevel"             %%  "cats-core"                 % catsVersion
    val logbackClassic           = "ch.qos.logback"            %   "logback-classic"           % logbackClassicVersion
    val janino                   = "org.codehaus.janino"      % "janino"                       % janinoVersion
    val bcpkix                   = "org.bouncycastle"         % "bcpkix-jdk15on"               % "1.67"
    val commonCompress           = "org.apache.commons"       % "commons-compress"             % "1.20"
    val scalaLogging             = "com.typesafe.scala-logging" %% "scala-logging"             % scalaLoggingVersion
    val scalaTest                = "org.scalatest"             %%  "scalatest"                 % scalaTestVersion
    val scalaCheck               = "org.scalacheck"            %% "scalacheck"                 % scalaCheckVersion
    val scalaTestCheck           = "org.scalatestplus"         %% "scalatestplus-scalacheck"   % scalaTestCheckVersion
    val osClient                 = "io.fabric8"                % "openshift-client"            % fabric8K8sVersion
    val osServerMock             = "io.fabric8"                % "openshift-server-mock"       % fabric8K8sVersion
    val pureConfig               = "com.github.pureconfig"     %%  "pureconfig"                % pureConfigVersion
    val codecs                   = "commons-codec"             % "commons-codec"               % codecsVersion
    val betterMonadicFor         = "com.olegpy"                %% "better-monadic-for"         % betterMonadicVersion
    val freya                    = "io.github.novakov-alexey"  %% "freya-core"                 % freyaVersion
    val freyaCirce               = "io.github.novakov-alexey"  %% "freya-circe"                % freyaVersion
    val circeCore                = "io.circe"                  %% "circe-core"                 % circeVersion
    val circeExtra               = "io.circe"                  %% "circe-generic-extras"       % circeExtrasVersion
    val jacksonJsonSchema        = "com.kjetland"               %% "mbknor-jackson-jsonschema" % jacksonJsonSchemaV
    val jacksonScala             = "com.fasterxml.jackson.module" %% "jackson-module-scala"    % jacksonScalaVersion
  }
}
