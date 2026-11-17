package no.liflig.http4k.kotlinx.jsonschema

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.http4k.format.KotlinxSerialization
import org.junit.jupiter.api.Test

/** `@KnownValues` on properties: the known values of an open set, rendered as `examples`. */
class KnownValuesTest {

  private val schema by lazy { schemaCreator.toSchema(KnownValuesDto.example) }

  @Test
  fun `renders examples and no enum for a string property`() {
    val reason = propertyOf(schema, "reason")

    examplesOf(reason) shouldBe listOf("\"OUT_OF_STOCK\"", "\"KITCHEN_CLOSED\"")
    (reason["type"] as JsonPrimitive).content shouldBe "string"
    reason shouldNotContainKey "enum"
  }

  @Test
  fun `renders an example that the provider does not list`() {
    val reason = propertyOf(schema, "reason")

    (reason["example"] as JsonPrimitive).content shouldBe "NOT_KNOWN_YET"
  }

  @Test
  fun `renders values with the JSON type of the property`() {
    examplesOf(propertyOf(schema, "status")) shouldBe listOf("404", "409")
  }

  @Test
  fun `renders a value class as its inner value`() {
    val reasonCode = propertyOf(schema, "reasonCode")

    (reasonCode["type"] as JsonPrimitive).content shouldBe "string"
    examplesOf(reasonCode) shouldBe listOf("\"OUT_OF_STOCK\"", "\"KITCHEN_CLOSED\"")
  }

  @Test
  fun `leaves null out of the examples of a nullable property under TYPE_ARRAY`() {
    val nullable = propertyOf(schema, "nullableReason")

    (nullable["type"] as JsonArray).map { (it as JsonPrimitive).content } shouldBe
        listOf("string", "null")
    examplesOf(nullable) shouldBe listOf("\"OUT_OF_STOCK\"", "\"KITCHEN_CLOSED\"")
  }

  @Test
  fun `places the examples beside anyOf for a nullable property under ANYOF`() {
    val creator =
        KotlinxSerializationJsonSchemaCreator<JsonElement>(
            json = KotlinxSerialization,
            kotlinxJson = kotlinxJson,
            nullableStrategy = NullableStrategy.ANYOF,
        )
    val nullable = propertyOf(creator.toSchema(KnownValuesDto.example), "nullableReason")

    nullable shouldContainKey "anyOf"
    examplesOf(nullable) shouldBe listOf("\"OUT_OF_STOCK\"", "\"KITCHEN_CLOSED\"")
    (nullable["anyOf"] as JsonArray).forEach { (it as JsonObject) shouldNotContainKey "examples" }
  }

  @Test
  fun `reads the getter use-site form`() {
    examplesOf(propertyOf(schema, "viaGetter")) shouldBe
        listOf("\"OUT_OF_STOCK\"", "\"KITCHEN_CLOSED\"")
  }

  @Test
  fun `leaves properties without the annotation without examples`() {
    propertyOf(schema, "unannotated") shouldNotContainKey "examples"
  }

  @Test
  fun `rejects a provider whose values are not of the property's type`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(KnownValuesTypeMismatchDto.example)
        }

    error.message shouldContain "@KnownValues"
    error.message shouldContain "'reason'"
    error.message shouldContain "kotlin.Int"
    error.message shouldContain "kotlin.String"
  }

  @Test
  fun `rejects properties that do not serialize as a primitive`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(KnownValuesOnEnumDto.example)
        }

    error.message shouldContain "'choice'"
    error.message shouldContain "ENUM"
  }

  @Test
  fun `rejects a provider that is not an object`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(KnownValuesNotAnObjectDto.example)
        }

    error.message shouldContain "NotAnObjectReasons"
    error.message shouldContain "`object`"
  }

  @Test
  fun `rejects a provider with no values`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(KnownValuesEmptyDto.example)
        }

    error.message shouldContain "no values"
  }

  @Test
  fun `rejects a property that is both open and closed`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(KnownAndAllowedValuesDto.example)
        }

    error.message shouldContain "'size'"
    error.message shouldContain "@KnownValues"
    error.message shouldContain "@AllowedValues"
  }

  @Test
  fun `reports the contradiction before a mistake in the allowed values`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(KnownAndDisallowedValuesDto.example)
        }

    error.message shouldContain "@KnownValues and @AllowedValues"
  }

  private fun propertyOf(
      schema: org.http4k.contract.jsonschema.JsonSchema<JsonElement>,
      property: String,
  ): JsonObject =
      ((schema.definitions["KnownValuesDto"] as JsonObject)["properties"] as JsonObject)[property]
          as JsonObject

  /** The `examples` values as JSON text, so strings keep their quotes. */
  private fun examplesOf(property: JsonObject): List<String> =
      (property["examples"] as JsonArray).map { it.toString() }
}
