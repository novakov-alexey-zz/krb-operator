package io.github.novakovalexey.krboperator

import cats.syntax.functor._
import io.circe.Decoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.auto._

trait Codecs {
  implicit val genConfig: Configuration =
    Configuration.default.withDiscriminator("type").withDefaults

  implicit val decodePassword: Decoder[Password] =
    List[Decoder[Password]](Decoder[Static].widen, Decoder.const(Random).widen).reduceLeft(_.or(_))

  implicit val decodeSecret: Decoder[Secret] =
    List[Decoder[Secret]](Decoder[Keytab].widen, Decoder[KeytabAndPassword].widen).reduceLeft(_.or(_))
}
