package io.github.novakovalexey.krboperator

final case class Principal(name: String, password: String, value: String, keytab: String, secret: String)
final case class Krb(realm: String, principals: List[Principal])
