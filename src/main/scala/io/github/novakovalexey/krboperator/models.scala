package io.github.novakovalexey.krboperator

final case class Principal(name: String, password: String, value: String, keytab: String, secret: String) {
  def isRandomPassword: Boolean =
    value == null || value == "random"
}
final case class Krb(realm: String, principals: List[Principal])
