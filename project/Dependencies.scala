import sbt._

object Dependencies extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    object DependenciesVersion {
      val catsVersion                      = "2.0.0"
      val logbackClassicVersion            = "1.2.3"
      val pureConfigVersion                = "0.11.1"
      val scalaLoggingVersion              = "3.9.2"
      val openshiftClientVersion           = "4.6.4"
      val codecsVersion                    = "1.13"
      val operatorLibVersion               = "0.1.0-SNAPSHOT"
    }

    import DependenciesVersion._

    val cats                     = "org.typelevel"             %%  "cats-core"                 % catsVersion
    val logbackClassic           = "ch.qos.logback"            %   "logback-classic"           % logbackClassicVersion
    val scalaLogging             = "com.typesafe.scala-logging" %% "scala-logging"             % scalaLoggingVersion
    val osClient                 = "io.fabric8"                % "openshift-client"            % openshiftClientVersion
    val pureConfig               = "com.github.pureconfig"     %%  "pureconfig"                % pureConfigVersion
    val codecs                   = "commons-codec"             % "commons-codec"               % codecsVersion
    val freya                    = "io.github.novakov-alexey"  %% "freya"                      % operatorLibVersion
  }
}
