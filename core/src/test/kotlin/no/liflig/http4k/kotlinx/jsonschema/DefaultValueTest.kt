package no.liflig.http4k.kotlinx.jsonschema

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Required-vs-optional and `default` keyword handling for properties with a Kotlin default value,
 * across both `encodeDefaults` settings of the [kotlinx.serialization.json.Json] instance passed to
 * the schema creator.
 */
class DefaultValueTest {

  @Test
  fun `defaulted fields are optional and carry the default value when encodeDefaults is enabled`() {
    val schema = schemaCreatorEncodeDefaults.toSchema(OptionalFieldDto.example)

    val definition = schema.definitions["OptionalFieldDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    // Only fields without a default - "required" itself, and "requiredWithDefault" which is
    // forced non-optional by @Required - stay required.
    requiredList shouldBe listOf("required", "requiredWithDefault")

    val properties = definition["properties"] as JsonObject
    ((properties["withDefault"] as JsonObject)["default"] as JsonPrimitive).content shouldBe
        "default-value"
    ((properties["withDefaultInt"] as JsonObject)["default"] as JsonPrimitive)
        .content
        .toInt() shouldBe 42

    // @Required never gets a "default" key: it isn't optional, so there's nothing to default to.
    (properties["requiredWithDefault"] as JsonObject)["default"] shouldBe null
  }

  @Test
  fun `defaulted fields stay required and carry no default value when encodeDefaults is disabled`() {
    val schema = schemaCreator.toSchema(OptionalFieldDto.example)

    val definition = schema.definitions["OptionalFieldDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    requiredList shouldBe listOf("required", "withDefault", "withDefaultInt", "requiredWithDefault")

    val properties = definition["properties"] as JsonObject
    (properties["withDefault"] as JsonObject)["default"] shouldBe null
    (properties["withDefaultInt"] as JsonObject)["default"] shouldBe null
  }

  @Test
  fun `a null default value is optional but never rendered as a default key`() {
    val schema = schemaCreatorEncodeDefaults.toSchema(NullableWithDefaultDto.example)

    val definition = schema.definitions["NullableWithDefaultDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    // Only "required" (no default) stays required; the null-defaulted fields are optional but
    // omit "default" entirely, since a rendered "default": null would be stripped later by
    // KotlinxOpenApi3Renderer's null-stripping pass outside example payloads.
    requiredList shouldBe listOf("required")

    val properties = definition["properties"] as JsonObject
    (properties["optionalNullable"] as JsonObject)["default"] shouldBe null
    (properties["optionalNullableInner"] as JsonObject)["default"] shouldBe null
    ((properties["optionalWithNonNullDefault"] as JsonObject)["default"] as JsonPrimitive)
        .content shouldBe "default"
  }

  @Test
  fun `collection defaults are rendered as an empty array`() {
    val schema = schemaCreatorEncodeDefaults.toSchema(ListWithDefaultDto.example)

    val definition = schema.definitions["ListWithDefaultDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    requiredList shouldBe listOf("required")

    val properties = definition["properties"] as JsonObject
    ((properties["tags"] as JsonObject)["default"] as JsonArray).size shouldBe 0
    ((properties["items"] as JsonObject)["default"] as JsonArray).size shouldBe 0
  }
}
