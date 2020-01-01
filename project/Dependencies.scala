import sbt._

object Dependencies extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements

  object autoImport {

    object DependenciesVersion {
      val catsVersion                      = "2.1.0"
      val logbackClassicVersion            = "1.3.0-alpha4"
      val pureConfigVersion                = "0.11.1"
      val scalaLoggingVersion              = "3.9.2"
      val openshiftClientVersion           = "4.6.4"
      val codecsVersion                    = "1.13"
      val betterMonadicVersion             = "0.3.1"
      val freyaVersion                     = "0.1.2"
    }

    import DependenciesVersion._

    val cats                     = "org.typelevel"             %%  "cats-core"                 % catsVersion
    val logbackClassic           = "ch.qos.logback"            %   "logback-classic"           % logbackClassicVersion
    val scalaLogging             = "com.typesafe.scala-logging" %% "scala-logging"             % scalaLoggingVersion
    val osClient                 = "io.fabric8"                % "openshift-client"            % openshiftClientVersion
    val pureConfig               = "com.github.pureconfig"     %%  "pureconfig"                % pureConfigVersion
    val codecs                   = "commons-codec"             % "commons-codec"               % codecsVersion
    val betterMonadicFor         = "com.olegpy"                %% "better-monadic-for"         % betterMonadicVersion
    val freya                    = "io.github.novakov-alexey"  %% "freya"                      % freyaVersion
  }
}
