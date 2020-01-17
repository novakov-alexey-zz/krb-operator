package io.github.novakovalexey.krboperator

import freya.Metadata
import io.github.novakovalexey.krboperator.Password.{Random, Static}
import io.github.novakovalexey.krboperator.Secret.{Keytab, KeytabAndPassword}
import org.scalacheck.Gen

object Generators {
  val nonEmptyString: Gen[String] = Gen.nonEmptyListOf[Char](Gen.alphaChar).map(_.mkString)

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
    } yield Metadata(name, namespace)
}
