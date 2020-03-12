package io.github.novakovalexey.krboperator

sealed trait Password
final case class Static(value: String) extends Password
case object Random extends Password

sealed trait Secret {
  val name: String
}

final case class Keytab(name: String) extends Secret
final case class KeytabAndPassword(name: String) extends Secret

final case class Principal(name: String, password: Password = Random, keytab: String, secret: Secret)
final case class Krb(realm: String, principals: List[Principal])
final case class Status(processed: Boolean, lastPrincipalCount: Int, totalPrincipalCount: Int, error: String = "")
