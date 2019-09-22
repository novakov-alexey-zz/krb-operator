import sbt._

object Dependencies extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    object DependenciesVersion {
      val logbackClassicVersion            = "1.2.3"
      val pureConfigVersion                = "0.11.1"
      val scalaLoggingVersion              = "3.9.2"
      val openshiftClientVersion           = "4.6.0"
    }

    import DependenciesVersion._

    val logbackClassic           = "ch.qos.logback"            %   "logback-classic"           % logbackClassicVersion
    val scalaLogging             = "com.typesafe.scala-logging" %% "scala-logging"             % scalaLoggingVersion
    val osClient                 = "io.fabric8"                % "openshift-client"            % openshiftClientVersion
    val pureConfig               = "com.github.pureconfig"     %%  "pureconfig"                % pureConfigVersion
  }
}
