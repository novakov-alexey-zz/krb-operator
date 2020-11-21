package io.github.novakovalexey.krboperator

import freya.models.{CustomResource, Metadata}
import org.scalacheck.{Arbitrary, Gen}

object Generators {
  val nonEmptyString: Gen[String] = Gen.nonEmptyListOf[Char](Gen.alphaChar).map(_.mkString)
  implicit lazy val arbBoolean: Arbitrary[Boolean] = Arbitrary(Gen.oneOf(true, false))

  def arbitrary[T](implicit a: Arbitrary[T]): Gen[T] = a.arbitrary

  def server: Gen[KrbServer] =
    for {
      realm <- Gen.alphaUpperStr.suchThat(_.nonEmpty)
    } yield KrbServer(realm)

  def principals: Gen[PrincipalList] =
    for {
      ps <- Gen.nonEmptyListOf(principal)
    } yield PrincipalList(ps)

  def secretGen: Gen[Secret] =
    Gen.frequency((1, keytabSecret), (1, keytabAndPasswordSecret))

  def keytabSecret: Gen[Keytab] =
    nonEmptyString.map(Keytab)

  def keytabAndPasswordSecret: Gen[KeytabAndPassword] =
    nonEmptyString.map(KeytabAndPassword)

  def password: Gen[Password] =
    Gen.frequency[Password]((1, nonEmptyString.map(Static)), (1, Gen.const(Random)))

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
      labels <- Gen.const(Map(PrincipalsController.ServerLabel -> name))
    } yield Metadata(name, namespace, labels, version, uid)

  def customResource
    : Gen[(CustomResource[KrbServer, KrbServerStatus], CustomResource[PrincipalList, PrincipalListStatus])] =
    for {
      srv <- server
      ps <- principals
      m <- meta
    } yield (CustomResource[KrbServer, KrbServerStatus](m, srv, None), CustomResource(m, ps, None))
}
