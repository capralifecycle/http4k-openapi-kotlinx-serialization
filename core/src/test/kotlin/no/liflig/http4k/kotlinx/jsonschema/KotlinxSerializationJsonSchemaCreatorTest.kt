package no.liflig.http4k.kotlinx.jsonschema

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.http4k.format.KotlinxSerialization
import org.junit.jupiter.api.Test

/** Structure: primitives, collections, nullability, formats, and the entry-point contract. */
class KotlinxSerializationJsonSchemaCreatorTest {

  @Test
  fun `emits numeric examples as JSON numbers rather than strings`() {
    val schema = schemaCreator.toSchema(SimplePrimitivesDto.example)

    val defName =
        ((schema.node as JsonObject)["\$ref"] as JsonPrimitive).content.substringAfterLast('/')
    val properties = (schema.definitions[defName] as JsonObject)["properties"] as JsonObject

    // `content` reads the same either way, so assert on quoting itself: a quoted example
    // does not validate against its own "type": "number" / "integer" schema.
    for (property in listOf("age", "score", "rating")) {
      val example = ((properties[property] as JsonObject)["example"] as JsonPrimitive)
      withClue(property) { example.isString shouldBe false }
    }
  }

  @Test
  fun `renders schema for simple primitives`() {
    val schema = schemaCreator.toSchema(SimplePrimitivesDto.example)

    val node = schema.node as JsonObject
    val refPath = (node["\$ref"] as JsonPrimitive).content
    val defName = refPath.substringAfterLast('/')

    val definition = schema.definitions[defName] as JsonObject
    (definition["type"] as JsonPrimitive).content shouldBe "object"

    val properties = definition["properties"] as JsonObject
    val nameSchema = properties["name"] as JsonObject
    (nameSchema["type"] as JsonPrimitive).content shouldBe "string"
    (nameSchema["example"] as JsonPrimitive).content shouldBe "Alice"

    val ageSchema = properties["age"] as JsonObject
    (ageSchema["type"] as JsonPrimitive).content shouldBe "integer"
    (ageSchema["format"] as JsonPrimitive).content shouldBe "int32"
    (ageSchema["example"] as JsonPrimitive).content.toInt() shouldBe 30

    val scoreSchema = properties["score"] as JsonObject
    (scoreSchema["type"] as JsonPrimitive).content shouldBe "integer"
    (scoreSchema["format"] as JsonPrimitive).content shouldBe "int64"
    (scoreSchema["example"] as JsonPrimitive).content.toLong() shouldBe 100_000L

    val ratingSchema = properties["rating"] as JsonObject
    (ratingSchema["type"] as JsonPrimitive).content shouldBe "number"
    (ratingSchema["format"] as JsonPrimitive).content shouldBe "double"
    (ratingSchema["example"] as JsonPrimitive).content.toDouble() shouldBe 4.5

    val activeSchema = properties["active"] as JsonObject
    (activeSchema["type"] as JsonPrimitive).content shouldBe "boolean"

    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }
    requiredList shouldBe listOf("name", "age", "score", "rating", "active")
  }

  @Test
  fun `renders schema for nested objects`() {
    val schema = schemaCreator.toSchema(NestedObjectDto.example)

    schema.definitions shouldContainKey "NestedObjectDto"
    schema.definitions shouldContainKey "InnerDto"

    val nestedDef = schema.definitions["NestedObjectDto"] as JsonObject
    val properties = nestedDef["properties"] as JsonObject
    val innerField = properties["inner"] as JsonObject
    val innerRef = (innerField["\$ref"] as JsonPrimitive).content
    innerRef shouldContain "InnerDto"
  }

  @Test
  fun `renders schema for lists`() {
    val schema = schemaCreator.toSchema(ListDto.example)

    val listDef = schema.definitions["ListDto"] as JsonObject
    val properties = listDef["properties"] as JsonObject

    val tagsSchema = properties["tags"] as JsonObject
    (tagsSchema["type"] as JsonPrimitive).content shouldBe "array"
    val tagsItems = tagsSchema["items"] as JsonObject
    (tagsItems["type"] as JsonPrimitive).content shouldBe "string"

    val itemsSchema = properties["items"] as JsonObject
    (itemsSchema["type"] as JsonPrimitive).content shouldBe "array"
    val itemsItems = itemsSchema["items"] as JsonObject
    val itemsRef = (itemsItems["\$ref"] as JsonPrimitive).content
    itemsRef shouldContain "InnerDto"
  }

  @Test
  fun `renders schema for nullable fields`() {
    val schema = schemaCreator.toSchema(NullableFieldDto.example)

    val definition = schema.definitions["NullableFieldDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    requiredList shouldBe listOf("required", "nullable", "nullableInner")

    val properties = definition["properties"] as JsonObject

    // Nullable primitive: type array with "null"
    val optionalSchema = properties["nullable"] as JsonObject
    val optionalType = optionalSchema["type"] as JsonArray
    optionalType.size shouldBe 2
    (optionalType[0] as JsonPrimitive).content shouldBe "string"
    (optionalType[1] as JsonPrimitive).content shouldBe "null"

    // Nullable $ref: plain $ref (no anyOf wrapper)
    val optionalInnerSchema = properties["nullableInner"] as JsonObject
    (optionalInnerSchema["\$ref"] as JsonPrimitive).content shouldContain "InnerDto"
    optionalInnerSchema["anyOf"] shouldBe null

    // Definition names must not contain '?' suffix from nullable descriptors
    for (key in schema.definitions.keys) {
      key shouldNotContain "?"
    }
  }

  @Test
  fun `renders schema for optional fields with defaults (encodeDefaults disabled)`() {
    val schema = schemaCreator.toSchema(OptionalFieldDto.example)

    val definition = schema.definitions["OptionalFieldDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    // With encodeDefaults disabled, a value equal to its default is omitted from the wire, so
    // every field stays required. See DefaultValueTest for the encodeDefaults = true behaviour.
    requiredList shouldBe listOf("required", "withDefault", "withDefaultInt", "requiredWithDefault")

    val properties = definition["properties"] as JsonObject
    (properties["withDefault"] as JsonObject)["default"] shouldBe null
  }

  @Test
  fun `renders schema for enums`() {
    val schema = schemaCreator.toSchema(EnumDto.example)

    schema.definitions shouldContainKey "TestEnum"

    val enumDef = schema.definitions["TestEnum"] as JsonObject
    (enumDef["type"] as JsonPrimitive).content shouldBe "string"

    val enumValues = enumDef["enum"] as JsonArray
    val enumList = enumValues.map { (it as JsonPrimitive).content }
    enumList shouldBe listOf("value_a", "value_b", "value_c")

    val enumDtoDef = schema.definitions["EnumDto"] as JsonObject
    val properties = enumDtoDef["properties"] as JsonObject
    val statusSchema = properties["status"] as JsonObject
    val statusRef = (statusSchema["\$ref"] as JsonPrimitive).content
    statusRef shouldContain "TestEnum"
  }

  @Test
  fun `renders schema for maps`() {
    val schema = schemaCreator.toSchema(MapDto.example)

    val mapDef = schema.definitions["MapDto"] as JsonObject
    val properties = mapDef["properties"] as JsonObject

    val stringMapSchema = properties["stringMap"] as JsonObject
    (stringMapSchema["type"] as JsonPrimitive).content shouldBe "object"
    val stringMapAdditional = stringMapSchema["additionalProperties"] as JsonObject
    (stringMapAdditional["type"] as JsonPrimitive).content shouldBe "string"

    val objectMapSchema = properties["objectMap"] as JsonObject
    (objectMapSchema["type"] as JsonPrimitive).content shouldBe "object"
    val objectMapAdditional = objectMapSchema["additionalProperties"] as JsonObject
    val objectMapRef = (objectMapAdditional["\$ref"] as JsonPrimitive).content
    objectMapRef shouldContain "InnerDto"
  }

  @Test
  fun `renders schema for recursive circular DTO`() {
    val schema = schemaCreator.toSchema(RecursiveDto.example)

    val recursiveDef = schema.definitions["RecursiveDto"] as JsonObject
    val properties = recursiveDef["properties"] as JsonObject
    val childrenSchema = properties["children"] as JsonObject

    (childrenSchema["type"] as JsonPrimitive).content shouldBe "array"
    val items = childrenSchema["items"] as JsonObject
    val itemsRef = (items["\$ref"] as JsonPrimitive).content
    itemsRef shouldContain "RecursiveDto"
  }

  @Test
  fun `renders schema for custom serial name`() {
    val schema = schemaCreator.toSchema(CustomSerialNameDto.example)

    schema.definitions shouldContainKey "custom_named_dto"

    val node = schema.node as JsonObject
    val refPath = (node["\$ref"] as JsonPrimitive).content
    refPath shouldContain "custom_named_dto"
  }

  @Test
  fun `renders schema for value classes`() {
    val schema = schemaCreator.toSchema(ValueClassDto.example)

    val definition = schema.definitions["ValueClassDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    // Kg(1500) should produce integer schema, not an object
    val weightSchema = properties["weight"] as JsonObject
    (weightSchema["type"] as JsonPrimitive).content shouldBe "integer"
    (weightSchema["format"] as JsonPrimitive).content shouldBe "int32"
    (weightSchema["example"] as JsonPrimitive).content.toInt() shouldBe 1500

    // TrainId("TR-001") should produce string schema
    val trainIdSchema = properties["trainId"] as JsonObject
    (trainIdSchema["type"] as JsonPrimitive).content shouldBe "string"
    (trainIdSchema["example"] as JsonPrimitive).content shouldBe "TR-001"
  }

  @Test
  fun `renders schema for serial name on properties`() {
    val schema = schemaCreator.toSchema(PropertySerialNameDto.example)

    val definition = schema.definitions["PropertySerialNameDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    // Properties should use serial names, not Kotlin property names
    properties shouldContainKey "train_id"
    properties shouldContainKey "wagon_count"
    properties shouldNotContainKey "trainIdentifier"
    properties shouldNotContainKey "numberOfWagons"

    val trainIdSchema = properties["train_id"] as JsonObject
    (trainIdSchema["type"] as JsonPrimitive).content shouldBe "string"
    (trainIdSchema["example"] as JsonPrimitive).content shouldBe "T-42"
  }

  @Test
  fun `renders schema for custom primitive serializer`() {
    val schema = schemaCreator.toSchema(CustomSerializerDto.example)

    val definition = schema.definitions["CustomSerializerDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val dateSchema = properties["departureDate"] as JsonObject
    (dateSchema["type"] as JsonPrimitive).content shouldBe "string"
    (dateSchema["example"] as JsonPrimitive).content shouldBe "2024-01-15"
  }

  @Test
  fun `renders schema for nullable fields with defaults (encodeDefaults disabled)`() {
    val schema = schemaCreator.toSchema(NullableWithDefaultDto.example)

    val definition = schema.definitions["NullableWithDefaultDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    // With encodeDefaults disabled, defaulted fields (nullable or not) stay required. See
    // DefaultValueTest for the encodeDefaults = true behaviour.
    requiredList shouldBe
        listOf(
            "required",
            "optionalNullable",
            "optionalNullableInner",
            "optionalWithNonNullDefault",
        )

    val properties = definition["properties"] as JsonObject

    // optionalNullable: String? = null → type array with "null"
    val optNullable = properties["optionalNullable"] as JsonObject
    val optNullableType = optNullable["type"] as JsonArray
    (optNullableType[0] as JsonPrimitive).content shouldBe "string"
    (optNullableType[1] as JsonPrimitive).content shouldBe "null"

    // optionalWithNonNullDefault: String = "default" → plain string, not required
    val optDefault = properties["optionalWithNonNullDefault"] as JsonObject
    (optDefault["type"] as JsonPrimitive).content shouldBe "string"
  }

  @Test
  fun `renders schema for nested maps`() {
    val schema = schemaCreator.toSchema(NestedMapDto.example)

    val definition = schema.definitions["NestedMapDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val permissionsSchema = properties["permissions"] as JsonObject
    (permissionsSchema["type"] as JsonPrimitive).content shouldBe "object"

    // additionalProperties should be another object with additionalProperties
    val innerMap = permissionsSchema["additionalProperties"] as JsonObject
    (innerMap["type"] as JsonPrimitive).content shouldBe "object"

    val innerValue = innerMap["additionalProperties"] as JsonObject
    (innerValue["type"] as JsonPrimitive).content shouldBe "boolean"
  }

  @Test
  fun `renders schema for lists with defaults (encodeDefaults disabled)`() {
    val schema = schemaCreator.toSchema(ListWithDefaultDto.example)

    val definition = schema.definitions["ListWithDefaultDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    // With encodeDefaults disabled, defaulted list fields stay required too. See
    // DefaultValueTest for the encodeDefaults = true behaviour.
    requiredList shouldBe listOf("required", "tags", "items")

    val properties = definition["properties"] as JsonObject

    // tags should still have array schema
    val tagsSchema = properties["tags"] as JsonObject
    (tagsSchema["type"] as JsonPrimitive).content shouldBe "array"

    // items should still have array schema with ref
    val itemsSchema = properties["items"] as JsonObject
    (itemsSchema["type"] as JsonPrimitive).content shouldBe "array"
  }

  @Test
  fun `renders schema excluding transient fields`() {
    val schema = schemaCreator.toSchema(TransientFieldDto.example)

    val definition = schema.definitions["TransientFieldDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    properties shouldContainKey "visible"
    properties shouldContainKey "alsoVisible"
    properties shouldNotContainKey "hidden"
  }

  @Test
  fun `renders schema for sets`() {
    val schema = schemaCreator.toSchema(SetDto.example)

    val definition = schema.definitions["SetDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val tagsSchema = properties["uniqueTags"] as JsonObject
    (tagsSchema["type"] as JsonPrimitive).content shouldBe "array"
    val tagsItems = tagsSchema["items"] as JsonObject
    (tagsItems["type"] as JsonPrimitive).content shouldBe "string"

    val itemsSchema = properties["uniqueItems"] as JsonObject
    (itemsSchema["type"] as JsonPrimitive).content shouldBe "array"
    val itemsItems = itemsSchema["items"] as JsonObject
    val itemsRef = (itemsItems["\$ref"] as JsonPrimitive).content
    itemsRef shouldContain "InnerDto"
  }

  @Test
  fun `renders schema for deeply nested structures`() {
    val schema = schemaCreator.toSchema(DeeplyNestedDto.example)

    schema.definitions shouldContainKey "DeeplyNestedDto"
    schema.definitions shouldContainKey "Level1Dto"
    schema.definitions shouldContainKey "Level2Dto"
    schema.definitions shouldContainKey "Level3Dto"
    schema.definitions shouldContainKey "Level4Dto"
  }

  @Test
  fun `ANYOF strategy renders nullable fields with anyOf pattern`() {
    val anyOfSchemaCreator =
        KotlinxSerializationJsonSchemaCreator<JsonElement>(
            json = KotlinxSerialization,
            kotlinxJson = kotlinxJson,
            nullableStrategy = NullableStrategy.ANYOF,
        )
    val schema = anyOfSchemaCreator.toSchema(NullableFieldDto.example)

    val definition = schema.definitions["NullableFieldDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    // Nullable primitive: anyOf with type + null
    val optionalSchema = properties["nullable"] as JsonObject
    optionalSchema["anyOf"].shouldNotBeNull()
    val anyOfArray = optionalSchema["anyOf"] as JsonArray
    anyOfArray.size shouldBe 2

    // Nullable $ref: anyOf with $ref + null
    val optionalInnerSchema = properties["nullableInner"] as JsonObject
    optionalInnerSchema["anyOf"].shouldNotBeNull()
    val innerAnyOf = optionalInnerSchema["anyOf"] as JsonArray
    innerAnyOf.size shouldBe 2
    val innerRef = innerAnyOf[0] as JsonObject
    (innerRef["\$ref"] as JsonPrimitive).content shouldContain "InnerDto"
    val innerNull = innerAnyOf[1] as JsonObject
    (innerNull["type"] as JsonPrimitive).content shouldBe "null"
  }

  @Test
  fun `renders schema for collection passed to toSchema`() {
    val schema = schemaCreator.toSchema(listOf(SimplePrimitivesDto.example))

    // Should produce an array schema
    val node = schema.node as JsonObject
    (node["type"] as JsonPrimitive).content shouldBe "array"

    // The item should reference SimplePrimitivesDto
    val items = node["items"] as JsonObject
    val itemsRef = (items["\$ref"] as JsonPrimitive).content
    itemsRef shouldContain "SimplePrimitivesDto"

    schema.definitions shouldContainKey "SimplePrimitivesDto"
  }

  @Test
  fun `returns empty schema for anonymous object`() {
    // http4k's OpenApi3.exampleSchemaIsValid passes `object {}` to toSchema()
    val schema = schemaCreator.toSchema(object {})

    val node = schema.node as JsonObject
    node.shouldBeEmpty()
    schema.definitions.shouldBeEmpty()
  }

  @Test
  fun `renders format for custom serializer with format mappings`() {
    // FakeDateSerializer has PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    // COMMON_FORMAT_MAPPINGS maps "LocalDate" to "date"
    val schema = schemaCreatorWithFormats.toSchema(CustomSerializerDto.example)

    val definition = schema.definitions["CustomSerializerDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val dateSchema = properties["departureDate"] as JsonObject
    (dateSchema["type"] as JsonPrimitive).content shouldBe "string"
    (dateSchema["format"] as JsonPrimitive).content shouldBe "date"
    (dateSchema["example"] as JsonPrimitive).content shouldBe "2024-01-15"

    // Non-custom fields should not get a format from mappings
    val nameSchema = properties["name"] as JsonObject
    (nameSchema["type"] as JsonPrimitive).content shouldBe "string"
    nameSchema shouldNotContainKey "format"
  }

  @Test
  fun `renders schema for byte short char fields`() {
    val schema = schemaCreator.toSchema(ByteShortCharDto.example)

    val definition = schema.definitions["ByteShortCharDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    // Byte should be integer with int32 format
    val byteSchema = properties["byteField"] as JsonObject
    (byteSchema["type"] as JsonPrimitive).content shouldBe "integer"
    (byteSchema["format"] as JsonPrimitive).content shouldBe "int32"

    // Short should be integer with int32 format
    val shortSchema = properties["shortField"] as JsonObject
    (shortSchema["type"] as JsonPrimitive).content shouldBe "integer"
    (shortSchema["format"] as JsonPrimitive).content shouldBe "int32"

    // Char should remain string
    val charSchema = properties["charField"] as JsonObject
    (charSchema["type"] as JsonPrimitive).content shouldBe "string"
  }

  @Test
  fun `handles scientific notation numbers`() {
    // Verify that numeric example conversion handles edge cases correctly
    // (scientific notation like 1.5E10 should not crash or produce garbage)
    val schema = schemaCreator.toSchema(SimplePrimitivesDto("test", 30, 100000L, 1.5e10, true))

    val definition = schema.definitions["SimplePrimitivesDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val ratingSchema = properties["rating"] as JsonObject
    val example = ratingSchema["example"]
    example.shouldNotBeNull()
    // The example should be present and parseable as a number
    example.shouldBeInstanceOf<JsonPrimitive>()
    (example as JsonPrimitive).content.toBigDecimalOrNull().shouldNotBeNull()
  }

  // --- H3 regression: SerializationException for unregistered classes must propagate ---

  @Test
  fun `propagates SerializationException for non-Serializable classes`() {
    shouldThrow<kotlinx.serialization.SerializationException> {
      schemaCreator.toSchema(UnregisteredDto("oops"))
    }
  }

  @Test
  fun `returns empty schema for anonymous-object sentinel from exampleSchemaIsValid`() {
    val schema = schemaCreator.toSchema(object {})
    schema.definitions.shouldBeEmpty()
    (schema.node as JsonObject).keys.shouldBeEmpty()
  }
}
