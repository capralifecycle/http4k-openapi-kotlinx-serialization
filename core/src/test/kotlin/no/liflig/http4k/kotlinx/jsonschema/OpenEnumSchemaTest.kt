package no.liflig.http4k.kotlinx.jsonschema

import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.format.KotlinxSerialization
import org.junit.jupiter.api.Test

/** Enums serialized by `OpenEnumSerializer`, rendered as open enums. */
class OpenEnumSchemaTest {

  private val schema = schemaCreator.toSchema(OpenEnumContainerDto.example)
  private val properties =
      (schema.definitions["OpenEnumContainerDto"] as JsonObject)["properties"] as JsonObject

  @Test
  fun `renders the known values and any other string, leaving out the fallback`() {
    val definition = schema.definitions["DescribedOpenEnum"] as JsonObject

    definition shouldNotContainKey "type"
    definition shouldNotContainKey "enum"
    val (known, anyString) = definition["anyOf"]!!.jsonArray.map { it.jsonObject }
    known["type"]?.jsonPrimitive?.content shouldBe "string"
    // Serial names, so @SerialName("phone") wins over the Kotlin name.
    known["enum"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("Email", "phone")
    anyString shouldBe JsonObject(mapOf("type" to JsonPrimitive("string")))
  }

  @Test
  fun `describes the definition with the type's description and the notice`() {
    descriptionOfDefinition("DescribedOpenEnum") shouldBe
        "How the customer is identified.\n\n$OPEN_ENUM_NOTICE"
    descriptionOfDefinition("PlainOpenEnum") shouldBe OPEN_ENUM_NOTICE
    // Found through the KType: Class.forName cannot load a nested type by its dotted serial name.
    descriptionOfDefinition("Nested") shouldBe
        "Described on an open enum nested inside its owner.\n\n$OPEN_ENUM_NOTICE"
  }

  @Test
  fun `renders a field without a description as a bare ref, so the notice is not repeated`() {
    properties["plain"] shouldBe ref("PlainOpenEnum")
    properties["nested"] shouldBe ref("Nested")
    (properties["many"] as JsonObject)["items"] shouldBe ref("PlainOpenEnum")
  }

  @Test
  fun `appends the notice to a field's own description`() {
    descriptionOf(properties, "described") shouldBe
        "The channel the customer prefers.\n\n$OPEN_ENUM_NOTICE"
    (properties["described"] as JsonObject)["\$ref"] shouldBe
        JsonPrimitive("#/components/schemas/DescribedOpenEnum")
  }

  @Test
  fun `appends the notice when the field holds open enums in a list or map`() {
    descriptionOf(properties, "describedMany") shouldBe
        "Every channel the customer accepts.\n\n$OPEN_ENUM_NOTICE"
    descriptionOf(properties, "describedByKey") shouldBe
        "Channels by priority.\n\n$OPEN_ENUM_NOTICE"
  }

  @Test
  fun `leaves closed enums as they were`() {
    descriptionOf(properties, "closed") shouldBe "A closed enum, for contrast."
    val closed = schema.definitions["DescribedEnum"] as JsonObject
    closed["type"]?.jsonPrimitive?.content shouldBe "string"
    closed["enum"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("FIRST", "SECOND")
    closed shouldNotContainKey "anyOf"
  }

  @Test
  fun `renders a nullable open enum per the nullable strategy`() {
    // TYPE_ARRAY: a $ref has no type to merge "null" into, so it stays a plain $ref.
    properties["nullable"] shouldBe ref("PlainOpenEnum")

    val anyOfSchema =
        KotlinxSerializationJsonSchemaCreator<JsonElement>(
                json = KotlinxSerialization,
                kotlinxJson = kotlinxJson,
                nullableStrategy = NullableStrategy.ANYOF,
            )
            .toSchema(OpenEnumContainerDto.example)
    val anyOfProperties =
        (anyOfSchema.definitions["OpenEnumContainerDto"] as JsonObject)["properties"] as JsonObject
    (anyOfProperties["nullable"] as JsonObject)["anyOf"] shouldBe
        JsonArray(listOf(ref("PlainOpenEnum"), JsonObject(mapOf("type" to JsonPrimitive("null")))))
  }

  @Test
  fun `renders an open enum passed straight to toSchema`() {
    // http4k does this for an enum query or path parameter, passing the parameter name as the
    // override; the definition keeps the enum's own name, as for a closed enum.
    val root = schemaCreator.toSchema(PlainOpenEnum.ACTIVE, overrideDefinitionId = "status")

    root.node shouldBe ref("PlainOpenEnum")
    root.definitions shouldContainKey "PlainOpenEnum"
    (root.definitions["PlainOpenEnum"] as JsonObject) shouldContainKey "anyOf"
  }

  private fun descriptionOfDefinition(name: String): String? =
      (schema.definitions[name] as JsonObject)["description"]?.jsonPrimitive?.content

  private fun descriptionOf(properties: JsonObject, property: String): String? =
      (properties[property] as JsonObject)["description"]?.jsonPrimitive?.content

  private fun ref(name: String) =
      JsonObject(mapOf("\$ref" to JsonPrimitive("#/components/schemas/$name")))
}
