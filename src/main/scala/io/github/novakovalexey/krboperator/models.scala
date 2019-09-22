package io.github.novakovalexey.krboperator

final case class Principal(name: String, password: String, value: String = "")
final case class Keytab(secret: String, key: String, principal: String, realm: String)
final case class Krb(realm: String, principals: List[Principal], keytabs: List[Keytab])

