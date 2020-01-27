package io.github.novakovalexey.krboperator

import com.fasterxml.jackson.annotation.JsonSubTypes.Type
import com.fasterxml.jackson.annotation.JsonTypeInfo.Id
import com.fasterxml.jackson.annotation.{JsonSubTypes, JsonTypeInfo}
import io.github.novakovalexey.krboperator.Password.{Random, Static}
import io.github.novakovalexey.krboperator.Secret.{Keytab, KeytabAndPassword}

@JsonTypeInfo(use = Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
  Array(new Type(value = classOf[Static], name = "static"), new Type(value = classOf[Random], name = "random"))
)
sealed trait Password
object Password {
  final case class Static(value: String) extends Password
  final case class Random() extends Password
}

@JsonTypeInfo(use = Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes(
  Array(new Type(value = classOf[Keytab], name = "Keytab"), new Type(value = classOf[KeytabAndPassword], name = "KeytabAndPassword"))
)
sealed trait Secret {
  val name: String
}
object Secret {
  final case class Keytab(name: String) extends Secret
  final case class KeytabAndPassword(name: String) extends Secret
}

final case class Principal(name: String, password: Password, keytab: String, secret: Secret)
final case class Krb(realm: String, principals: List[Principal])
final case class Status(processed: Boolean, lastPrincipalCount: Int, totalPrincipalCount: Int, error: String = "")
