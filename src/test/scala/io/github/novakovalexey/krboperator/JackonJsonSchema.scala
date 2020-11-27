package io.github.novakovalexey.krboperator

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.kjetland.jackson.jsonSchema.JsonSchemaGenerator

object JackonJsonSchema extends App {
  val objectMapper = new ObjectMapper
  objectMapper.registerModule(DefaultScalaModule)

  val jsonSchemaGenerator = new JsonSchemaGenerator(objectMapper)
  val jsonSchema = jsonSchemaGenerator.generateJsonSchema(classOf[PrincipalsStatus])

  val jsonSchemaAsString = objectMapper.writeValueAsString(jsonSchema)
  println(jsonSchemaAsString)
}