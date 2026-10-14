package no.liflig.http4k.kotlinx.jsonschema

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.http4k.format.KotlinxSerialization
import org.junit.jupiter.api.Test

/** `@AllowedValues` on properties, and the sealed-hierarchy case it exists for. */
class AllowedValuesTest {

  private val schema by lazy { schemaCreator.toSchema(AllowedValuesDto.example) }

  @Test
  fun `renders enum for an integer property`() {
    val pieces = propertyOf(schema, "pieces")

    enumOf(pieces) shouldBe listOf("6", "8", "12")
    (pieces["type"] as JsonPrimitive).content shouldBe "integer"
  }

  @Test
  fun `renders enum values with the JSON type of the property`() {
    enumOf(propertyOf(schema, "serving_count")) shouldBe listOf("1", "2")
    enumOf(propertyOf(schema, "size")) shouldBe listOf("\"S\"", "\"M\"", "\"L\"")
    enumOf(propertyOf(schema, "ratio")) shouldBe listOf("0.5", "1.0")
    enumOf(propertyOf(schema, "grade")) shouldBe listOf("\"A\"", "\"B\"")
    enumOf(propertyOf(schema, "confirmed")) shouldBe listOf("true")
  }

  @Test
  fun `renders a value class as its inner value`() {
    val weight = propertyOf(schema, "weight")

    (weight["type"] as JsonPrimitive).content shouldBe "integer"
    enumOf(weight) shouldBe listOf("1500", "3000")
  }

  @Test
  fun `renders unsigned values beyond the signed range as unsigned numbers`() {
    enumOf(propertyOf(schema, "quota")) shouldBe listOf("10", "4000000000")
  }

  @Test
  fun `adds null to the enum of a nullable property under TYPE_ARRAY`() {
    val nullable = propertyOf(schema, "nullablePieces")

    (nullable["type"] as JsonArray).map { (it as JsonPrimitive).content } shouldBe
        listOf("integer", "null")
    enumOf(nullable) shouldBe listOf("5", "10", "15", "null")
  }

  @Test
  fun `places the enum beside anyOf for a nullable property under ANYOF`() {
    val creator =
        KotlinxSerializationJsonSchemaCreator<JsonElement>(
            json = KotlinxSerialization,
            kotlinxJson = kotlinxJson,
            nullableStrategy = NullableStrategy.ANYOF,
        )
    val nullable = propertyOf(creator.toSchema(AllowedValuesDto.example), "nullablePieces")

    // On the outer object, constraining the property as a whole, not inside one branch.
    nullable shouldContainKey "anyOf"
    enumOf(nullable) shouldBe listOf("5", "10", "15", "null")
    (nullable["anyOf"] as JsonArray).forEach { (it as JsonObject) shouldNotContainKey "enum" }
  }

  @Test
  fun `reads the getter use-site form`() {
    enumOf(propertyOf(schema, "viaGetter")) shouldBe listOf("\"S\"", "\"M\"", "\"L\"")
  }

  @Test
  fun `leaves properties without the annotation unconstrained`() {
    propertyOf(schema, "unconstrained") shouldNotContainKey "enum"
  }

  @Test
  fun `rejects a provider whose values are not of the property's type`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(AllowedValuesTypeMismatchDto.example)
        }

    error.message shouldContain "'size'"
    error.message shouldContain "kotlin.Int"
    error.message shouldContain "kotlin.String"
  }

  @Test
  fun `rejects properties that do not serialize as a primitive`() {
    val onEnum =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(AllowedValuesOnEnumDto.example)
        }
    onEnum.message shouldContain "'choice'"
    onEnum.message shouldContain "ENUM"

    val onList =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(AllowedValuesOnListDto.example)
        }
    onList.message shouldContain "'pieces'"
    onList.message shouldContain "LIST"
  }

  @Test
  fun `rejects a provider that is not an object`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(AllowedValuesNotAnObjectDto.example)
        }

    error.message shouldContain "NotAnObjectSizes"
    error.message shouldContain "`object`"
  }

  @Test
  fun `rejects a provider with no values`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(AllowedValuesEmptyDto.example)
        }

    error.message shouldContain "no values"
  }

  @Test
  fun `rejects an example that its own constraint disallows`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(AllowedValuesDisallowedExampleDto.example)
        }

    error.message shouldContain "example value 5"
  }

  @Test
  fun `rejects a property-level serializer that changes the wire type`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          schemaCreator.toSchema(AllowedValuesCustomSerializerDto.example)
        }

    error.message shouldContain "'pieces'"
    error.message shouldContain "STRING"
  }

  @Test
  fun `gives each sealed subclass its own allowed values`() {
    val schema = schemaCreator.toSchema(DishOrderRequestDto.example)

    val sushi = schema.definitions["SushiOrder"] as JsonObject
    val dumplings = schema.definitions["DumplingOrder"] as JsonObject

    enumOf(propertyIn(sushi, "pieces")) shouldBe listOf("6", "8", "12")
    enumOf(propertyIn(dumplings, "pieces")) shouldBe listOf("5", "10", "15")

    // The discriminator comes from @JsonClassDiscriminator, pinned per subclass and required.
    enumOf(propertyIn(sushi, "dish")) shouldBe listOf("\"sushi\"")
    (sushi["required"] as JsonArray).map { (it as JsonPrimitive).content } shouldBe
        listOf("dish", "pieces")
  }

  @Test
  fun `the provider enforces the constraint when decoding`() {
    val error =
        shouldThrow<IllegalArgumentException> {
          kotlinxJson.decodeFromString(
              DishOrderRequestDto.serializer(),
              """{"order": {"dish": "sushi", "pieces": 5}}""",
          )
        }

    error.message shouldBe "'pieces' must be one of [6, 8, 12], but was 5"
  }

  private fun propertyOf(
      schema: org.http4k.contract.jsonschema.JsonSchema<JsonElement>,
      property: String,
  ): JsonObject = propertyIn(schema.definitions["AllowedValuesDto"] as JsonObject, property)

  private fun propertyIn(definition: JsonObject, property: String): JsonObject =
      (definition["properties"] as JsonObject)[property] as JsonObject

  /** The `enum` values as JSON text, so strings keep their quotes and `null` reads as `null`. */
  private fun enumOf(property: JsonObject): List<String> =
      (property["enum"] as JsonArray).map { if (it is JsonNull) "null" else it.toString() }
}
