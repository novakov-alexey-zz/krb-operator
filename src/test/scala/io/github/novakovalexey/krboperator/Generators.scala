package io.github.novakovalexey.krboperator

import freya.Metadata
import freya.models.CustomResource
import org.scalacheck.{Arbitrary, Gen}

object Generators {
  val nonEmptyString: Gen[String] = Gen.nonEmptyListOf[Char](Gen.alphaChar).map(_.mkString)
  implicit lazy val arbBoolean: Arbitrary[Boolean] = Arbitrary(Gen.oneOf(true, false))

  def arbitrary[T](implicit a: Arbitrary[T]): Gen[T] = a.arbitrary

  def krb: Gen[Krb] =
    for {
      realm <- Gen.alphaUpperStr.suchThat(_.nonEmpty)
      ps <- Gen.nonEmptyListOf(principal)
    } yield Krb(realm, ps)

  def secretGen: Gen[Secret] =
    Gen.frequency((1, keytabSecret), (1, keytabAndPasswordSecret))

  def keytabSecret: Gen[Keytab] =
    nonEmptyString.map(Keytab)

  def keytabAndPasswordSecret: Gen[KeytabAndPassword] =
    nonEmptyString.map(KeytabAndPassword)

  def password: Gen[Password] = Gen.frequency[Password]((1, nonEmptyString.map(Static)), (1, Gen.const(Random())))

  def principal: Gen[Principal] =
    for {
      name <- nonEmptyString
      pwd <- password
      keytab <- nonEmptyString
      secret <- secretGen
    } yield Principal(name, pwd, keytab, secret)

  def meta: Gen[Metadata] =
    for {
      name <- nonEmptyString
      namespace <- nonEmptyString
      version <- nonEmptyString
      uid <- nonEmptyString
    } yield Metadata(name, namespace, version, uid)

  def customResource: Gen[CustomResource[Krb, Status]] =
    for {
      spec <- krb
      m <- meta
    } yield CustomResource[Krb, Status](spec, m, None)
}
