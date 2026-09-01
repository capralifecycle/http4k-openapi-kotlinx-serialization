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
import kotlinx.serialization.json.Json as KotlinxJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.http4k.format.KotlinxSerialization
import org.junit.jupiter.api.Test

class KotlinxSerializationJsonSchemaCreatorTest {

  private val kotlinxJson = KotlinxJson { ignoreUnknownKeys = true }

  private val schemaCreator =
      KotlinxSerializationJsonSchemaCreator<JsonElement>(
          json = KotlinxSerialization,
          kotlinxJson = kotlinxJson,
      )

  private val schemaCreatorWithFormats =
      KotlinxSerializationJsonSchemaCreator<JsonElement>(
          json = KotlinxSerialization,
          kotlinxJson = kotlinxJson,
          formatMappings = KotlinxSerializationJsonSchemaCreator.COMMON_FORMAT_MAPPINGS,
      )

  private val prettyJson = KotlinxJson { prettyPrint = true }

  private fun prettyPrint(schema: org.http4k.contract.jsonschema.JsonSchema<JsonElement>): String {
    val combined =
        kotlinx.serialization.json.JsonObject(
            buildMap {
              put("node", schema.node)
              put(
                  "definitions",
                  kotlinx.serialization.json.JsonObject(
                      schema.definitions.mapValues {
                        it.value as kotlinx.serialization.json.JsonElement
                      }
                  ),
              )
            }
        )
    return prettyJson.encodeToString(
        kotlinx.serialization.json.JsonElement.serializer(),
        combined,
    )
  }

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
  fun `renders schema for optional fields with defaults`() {
    val schema = schemaCreator.toSchema(OptionalFieldDto.example)

    val definition = schema.definitions["OptionalFieldDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    requiredList shouldBe listOf("required", "requiredWithDefault")
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
  fun `renders schema for sealed classes with oneOf and discriminator`() {
    val schema = schemaCreator.toSchema(SealedContainerDto.example)

    schema.definitions shouldContainKey "SealedBase"
    schema.definitions shouldContainKey "SealedChild1"
    schema.definitions shouldContainKey "SealedChild2"

    val sealedBaseDef = schema.definitions["SealedBase"] as JsonObject
    val oneOfArray = sealedBaseDef["oneOf"] as JsonArray
    oneOfArray.size shouldBe 2

    val discriminator = sealedBaseDef["discriminator"] as JsonObject
    (discriminator["propertyName"] as JsonPrimitive).content shouldBe "type"

    // Discriminator mapping keys are still @SerialName values
    val mapping = discriminator["mapping"] as JsonObject
    mapping shouldContainKey "child_one"
    mapping shouldContainKey "child_two"

    // Definition keys are Kotlin class names, but discriminator enum values are @SerialName
    val child1Def = schema.definitions["SealedChild1"] as JsonObject
    val child1Props = child1Def["properties"] as JsonObject
    val child1Type = child1Props["type"] as JsonObject
    val child1Enum = child1Type["enum"] as JsonArray
    (child1Enum[0] as JsonPrimitive).content shouldBe "child_one"

    val child2Def = schema.definitions["SealedChild2"] as JsonObject
    val child2Props = child2Def["properties"] as JsonObject
    val child2Type = child2Props["type"] as JsonObject
    val child2Enum = child2Type["enum"] as JsonArray
    (child2Enum[0] as JsonPrimitive).content shouldBe "child_two"
  }

  @Test
  fun `renders schema with refModelNamePrefix`() {
    val schema = schemaCreator.toSchema(NestedObjectDto.example, refModelNamePrefix = "prefix_")

    schema.definitions shouldContainKey "prefix_NestedObjectDto"
    schema.definitions shouldContainKey "prefix_InnerDto"

    val node = schema.node as JsonObject
    val refPath = (node["\$ref"] as JsonPrimitive).content
    refPath shouldContain "prefix_NestedObjectDto"

    val nestedDef = schema.definitions["prefix_NestedObjectDto"] as JsonObject
    val properties = nestedDef["properties"] as JsonObject
    val innerField = properties["inner"] as JsonObject
    val innerRef = (innerField["\$ref"] as JsonPrimitive).content
    innerRef shouldContain "prefix_InnerDto"
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
  fun `renders schema for sealed class with data object`() {
    val schema = schemaCreator.toSchema(StatusContainerDto.example)

    schema.definitions shouldContainKey "StatusWithDataObject"
    schema.definitions shouldContainKey "Pending"
    schema.definitions shouldContainKey "Handled"

    // Pending (data object) should have only the discriminator property
    val pendingDef = schema.definitions["Pending"] as JsonObject
    val pendingProps = pendingDef["properties"] as JsonObject
    pendingProps.size shouldBe 1 // only "type" discriminator
    val pendingType = pendingProps["type"] as JsonObject
    val pendingEnum = pendingType["enum"] as JsonArray
    (pendingEnum[0] as JsonPrimitive).content shouldBe "PENDING"

    // Handled should have discriminator + mrn
    val handledDef = schema.definitions["Handled"] as JsonObject
    val handledProps = handledDef["properties"] as JsonObject
    handledProps.size shouldBe 2 // "type" + "mrn"
    handledProps shouldContainKey "mrn"
  }

  @Test
  fun `renders schema for sealed interface`() {
    val schema = schemaCreator.toSchema(SealedInterfaceContainerDto.example)

    schema.definitions shouldContainKey "TransportMode"
    schema.definitions shouldContainKey "Rail"
    schema.definitions shouldContainKey "Road"

    val transportModeDef = schema.definitions["TransportMode"] as JsonObject
    val oneOfArray = transportModeDef["oneOf"] as JsonArray
    oneOfArray.size shouldBe 2

    val discriminator = transportModeDef["discriminator"] as JsonObject
    (discriminator["propertyName"] as JsonPrimitive).content shouldBe "type"
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
  fun `renders schema for nullable fields with defaults`() {
    val schema = schemaCreator.toSchema(NullableWithDefaultDto.example)

    val definition = schema.definitions["NullableWithDefaultDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    // Only "required" should be required (not nullable, no default)
    requiredList shouldBe listOf("required")

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
  fun `renders schema for lists with defaults`() {
    val schema = schemaCreator.toSchema(ListWithDefaultDto.example)

    val definition = schema.definitions["ListWithDefaultDto"] as JsonObject
    val required = definition["required"] as JsonArray
    val requiredList = required.map { (it as JsonPrimitive).content }

    // Only "required" field should be in required list
    requiredList shouldBe listOf("required")

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
  fun `renders schema for nested sealed hierarchies`() {
    val schema = schemaCreator.toSchema(NestedSealedContainerDto.example)

    // Outer sealed hierarchy
    schema.definitions shouldContainKey "OuterSealed"
    schema.definitions shouldContainKey "WithInner"
    schema.definitions shouldContainKey "SimpleOuter"

    // Inner sealed hierarchy (referenced from with_inner)
    schema.definitions shouldContainKey "InnerSealed"
    schema.definitions shouldContainKey "OptionA"
    schema.definitions shouldContainKey "OptionB"

    val outerDef = schema.definitions["OuterSealed"] as JsonObject
    val outerOneOf = outerDef["oneOf"] as JsonArray
    outerOneOf.size shouldBe 2

    val innerDef = schema.definitions["InnerSealed"] as JsonObject
    val innerOneOf = innerDef["oneOf"] as JsonArray
    innerOneOf.size shouldBe 2
  }

  @Test
  fun `sealed classes with shared serial names produce distinct definitions`() {
    val schema = schemaCreator.toSchema(CollidingSealedContainerDto.example)

    // Each sealed hierarchy should have its own definitions using Kotlin class names
    schema.definitions shouldContainKey "ImportStatus"
    schema.definitions shouldContainKey "ImportPending"
    schema.definitions shouldContainKey "ImportHandled"
    schema.definitions shouldContainKey "TransitStatus"
    schema.definitions shouldContainKey "TransitPending"
    schema.definitions shouldContainKey "TransitHandled"

    // ImportHandled should have "mrn" field
    val importHandledDef = schema.definitions["ImportHandled"] as JsonObject
    val importHandledProps = importHandledDef["properties"] as JsonObject
    importHandledProps shouldContainKey "mrn"

    // TransitHandled should have "transitId" field (not "mrn")
    val transitHandledDef = schema.definitions["TransitHandled"] as JsonObject
    val transitHandledProps = transitHandledDef["properties"] as JsonObject
    transitHandledProps shouldContainKey "transitId"
    transitHandledProps shouldNotContainKey "mrn"

    // Discriminator enum values should still be @SerialName values
    val importPendingDef = schema.definitions["ImportPending"] as JsonObject
    val importPendingEnum =
        ((importPendingDef["properties"] as JsonObject)["type"] as JsonObject)["enum"] as JsonArray
    (importPendingEnum[0] as JsonPrimitive).content shouldBe "PENDING"

    val transitPendingDef = schema.definitions["TransitPending"] as JsonObject
    val transitPendingEnum =
        ((transitPendingDef["properties"] as JsonObject)["type"] as JsonObject)["enum"] as JsonArray
    (transitPendingEnum[0] as JsonPrimitive).content shouldBe "PENDING"
  }

  @Test
  fun `renders schema for nullable sealed field`() {
    val schema = schemaCreator.toSchema(NullableSealedDto.example)

    val definition = schema.definitions["NullableSealedDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    // Nullable sealed field: plain $ref (no anyOf wrapper, field excluded from required)
    val statusSchema = properties["status"] as JsonObject
    (statusSchema["\$ref"] as JsonPrimitive).content shouldContain "SealedBase"
    statusSchema["anyOf"] shouldBe null

    // Sealed hierarchy definitions should still be present
    schema.definitions shouldContainKey "SealedBase"
    schema.definitions shouldContainKey "SealedChild1"
    schema.definitions shouldContainKey "SealedChild2"

    // No definition key should contain '?'
    for (key in schema.definitions.keys) {
      key shouldNotContain "?"
    }
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
  fun `renders schema for list of sealed class`() {
    val schema = schemaCreator.toSchema(SealedListDto.example)

    val definition = schema.definitions["SealedListDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val itemsSchema = properties["items"] as JsonObject
    (itemsSchema["type"] as JsonPrimitive).content shouldBe "array"

    // Array items should reference the sealed parent
    val items = itemsSchema["items"] as JsonObject
    val itemsRef = (items["\$ref"] as JsonPrimitive).content
    itemsRef shouldContain "SealedBase"

    // Sealed hierarchy definitions should be present
    schema.definitions shouldContainKey "SealedBase"
    schema.definitions shouldContainKey "SealedChild1"
    schema.definitions shouldContainKey "SealedChild2"
  }

  @Test
  fun `renders schema for map with sealed values`() {
    val schema = schemaCreator.toSchema(SealedMapDto.example)

    val definition = schema.definitions["SealedMapDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val statusesSchema = properties["statuses"] as JsonObject
    (statusesSchema["type"] as JsonPrimitive).content shouldBe "object"

    // additionalProperties should reference the sealed parent
    val additionalProps = statusesSchema["additionalProperties"] as JsonObject
    val additionalRef = (additionalProps["\$ref"] as JsonPrimitive).content
    additionalRef shouldContain "SealedBase"

    // Sealed hierarchy definitions should be present
    schema.definitions shouldContainKey "SealedBase"
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
  fun `renders schema for sealed parent with serial name`() {
    val schema = schemaCreator.toSchema(CustomNamedSealedContainerDto.example)

    // The sealed parent has @SerialName("custom_event") — this should not prevent schema generation
    schema.definitions shouldContainKey "CustomNamedSealedParent"
    schema.definitions shouldContainKey "Started"
    schema.definitions shouldContainKey "Completed"

    val parentDef = schema.definitions["CustomNamedSealedParent"] as JsonObject
    val oneOfArray = parentDef["oneOf"] as JsonArray
    oneOfArray.size shouldBe 2

    val discriminator = parentDef["discriminator"] as JsonObject
    (discriminator["propertyName"] as JsonPrimitive).content shouldBe "type"

    val mapping = discriminator["mapping"] as JsonObject
    mapping shouldContainKey "started"
    mapping shouldContainKey "completed"
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
  fun `overrideDefinitionId renames top level definition`() {
    val schema =
        schemaCreator.toSchema(
            SimplePrimitivesDto.example,
            overrideDefinitionId = "MyCustomName",
        )

    // The definition should be stored under the override name
    schema.definitions shouldContainKey "MyCustomName"
    schema.definitions shouldNotContainKey "SimplePrimitivesDto"

    // The $ref in the node should point to the overridden name
    val ref = (schema.node as JsonObject)["\$ref"]
    ref.shouldNotBeNull()
    (ref as JsonPrimitive).content shouldBe "#/components/schemas/MyCustomName"
  }

  @Test
  fun `overrideDefinitionId with refModelNamePrefix`() {
    val schema =
        schemaCreator.toSchema(
            SimplePrimitivesDto.example,
            overrideDefinitionId = "MyCustomName",
            refModelNamePrefix = "prefix_",
        )

    schema.definitions shouldContainKey "prefix_MyCustomName"
    schema.definitions shouldNotContainKey "SimplePrimitivesDto"
    schema.definitions shouldNotContainKey "prefix_SimplePrimitivesDto"
  }

  @Test
  fun `overrideDefinitionId is ignored for enums`() {
    // http4k renders an enum query or path parameter by passing the *parameter* name as
    // overrideDefinitionId. The enum keeps its own name so the definition is not duplicated
    // under the parameter name when the same enum also appears in a request or response body.
    val schema = schemaCreator.toSchema(TestEnum.VALUE_A, overrideDefinitionId = "status")

    schema.definitions shouldContainKey "TestEnum"
    schema.definitions shouldNotContainKey "status"

    val ref = (schema.node as JsonObject)["\$ref"]
    ref.shouldNotBeNull()
    (ref as JsonPrimitive).content shouldBe "#/components/schemas/TestEnum"
  }

  @Test
  fun `overrideDefinitionId on sealed class renames parent only`() {
    val schema =
        schemaCreator.toSchema(
            SealedContainerDto.example,
            overrideDefinitionId = "CustomPayload",
        )

    // The container gets the overridden name
    schema.definitions shouldContainKey "CustomPayload"
    schema.definitions shouldNotContainKey "SealedContainerDto"

    // Subclass definitions and sealed parent are NOT renamed
    schema.definitions shouldContainKey "SealedBase"
    schema.definitions shouldContainKey "SealedChild1"
    schema.definitions shouldContainKey "SealedChild2"
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

  @Test
  fun `sealed parent with SerialName resolves through SerialNamed property`() {
    val schema = schemaCreator.toSchema(SerialNamedPropertyWithSealedDto.example)

    // The sealed parent has @SerialName("custom_event") and the property has
    // @SerialName("event_payload").
    // KType resolution must handle the property name mismatch.
    schema.definitions shouldContainKey "CustomNamedSealedParent"
    schema.definitions shouldContainKey "Started"
    schema.definitions shouldContainKey "Completed"
  }

  @Test
  fun `overrideDefinitionId on sealed class root renames parent definition`() {
    val schema =
        schemaCreator.toSchema(
            SealedChild1.example,
            overrideDefinitionId = "RenamedSubclass",
        )

    schema.definitions shouldContainKey "RenamedSubclass"
    schema.definitions shouldNotContainKey "SealedChild1"
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

  // --- H2 regression: overrideDefinitionId on recursive DTOs rewrites inner self-refs ---

  @Test
  fun `overrideDefinitionId rewrites recursive self-refs to the new name`() {
    val schema = schemaCreator.toSchema(RecursiveDto.example, overrideDefinitionId = "MyRecursive")

    schema.definitions shouldContainKey "MyRecursive"
    schema.definitions shouldNotContainKey "RecursiveDto"

    val definition = schema.definitions["MyRecursive"]!!
    val defAsString =
        kotlinx.serialization.json.Json.encodeToString(JsonElement.serializer(), definition)
    defAsString shouldNotContain "RecursiveDto"
    defAsString shouldContain "#/components/schemas/MyRecursive"
  }

  // --- H1 regression: short-name collisions don't leave dangling $refs ---

  @Test
  fun `short-name collision across packages produces valid refs in both directions`() {
    val schema = schemaCreator.toSchema(TwoUsersDto.example)

    // Both definitions exist under disambiguated keys; the bare "User" key must not exist.
    schema.definitions shouldContainKey "packageA_User"
    schema.definitions shouldContainKey "packageB_User"
    schema.definitions shouldNotContainKey "User"

    val def = schema.definitions["TwoUsersDto"]!!
    val asString = kotlinx.serialization.json.Json.encodeToString(JsonElement.serializer(), def)
    asString shouldContain "#/components/schemas/packageA_User"
    asString shouldContain "#/components/schemas/packageB_User"
    asString shouldNotContain "\"#/components/schemas/User\""
  }

  // --- M2 regression: two sealed hierarchies with same-named subclasses each get a definition ---

  @Test
  fun `two sealed hierarchies with overlapping subclass names produce distinct definitions`() {
    val schema = schemaCreator.toSchema(TwoSealedEventsDto.example)

    schema.definitions shouldContainKey "EventA"
    schema.definitions shouldContainKey "EventB"

    // The Created subclasses exist under disambiguated keys (collision resolution
    // renames both because FQCNs differ).
    schema.definitions shouldNotContainKey "Created"
    val createdKeys = schema.definitions.keys.filter { it.endsWith("_Created") }
    createdKeys.size shouldBe 2

    // Neither parent's oneOf references the bare "Created" key.
    val eventA = schema.definitions["EventA"]!!
    val eventB = schema.definitions["EventB"]!!
    val eventAStr = kotlinx.serialization.json.Json.encodeToString(JsonElement.serializer(), eventA)
    val eventBStr = kotlinx.serialization.json.Json.encodeToString(JsonElement.serializer(), eventB)
    eventAStr shouldNotContain "\"#/components/schemas/Created\""
    eventBStr shouldNotContain "\"#/components/schemas/Created\""

    // The two Created definitions document different properties (proves both are real).
    val def0 = schema.definitions[createdKeys[0]] as JsonObject
    val def1 = schema.definitions[createdKeys[1]] as JsonObject
    (def0 == def1) shouldBe false
  }

  // --- M3 regression: @JsonClassDiscriminator override is honoured ---

  @Test
  fun `JsonClassDiscriminator override is used instead of the global discriminator`() {
    val schema = schemaCreator.toSchema(CustomDiscriminatorContainerDto.example)

    val parent = schema.definitions["CustomDiscriminatorEvent"]!! as JsonObject
    val discriminator = parent["discriminator"] as JsonObject
    (discriminator["propertyName"] as JsonPrimitive).content shouldBe "kind"

    // Each subclass embeds the discriminator property as "kind", not the global "type".
    val subclass =
        schema.definitions.values.filterIsInstance<JsonObject>().first {
          (it["properties"] as? JsonObject)?.containsKey("kind") == true
        }
    val properties = subclass["properties"] as JsonObject
    properties shouldContainKey "kind"
    properties shouldNotContainKey "type"
  }

  @Test
  fun `renders deprecated marker for properties annotated with Deprecated`() {
    val schema = schemaCreator.toSchema(DeprecatedFieldDto.example)

    val definition = schema.definitions["DeprecatedFieldDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val legacySchema = properties["legacy"] as JsonObject
    (legacySchema["deprecated"] as JsonPrimitive).content.toBoolean() shouldBe true
    (legacySchema["type"] as JsonPrimitive).content shouldBe "string"

    // Non-annotated properties carry no marker at all, rather than "deprecated": false.
    val replacementSchema = properties["replacement"] as JsonObject
    replacementSchema shouldNotContainKey "deprecated"

    // Composes with the nullable type array rather than replacing it.
    val nullableSchema = properties["legacyNullable"] as JsonObject
    (nullableSchema["deprecated"] as JsonPrimitive).content.toBoolean() shouldBe true
    val nullableType = nullableSchema["type"] as JsonArray
    nullableType.map { (it as JsonPrimitive).content } shouldBe listOf("string", "null")

    // Sibling of $ref, which OpenAPI 3.1 / JSON Schema 2020-12 permits.
    val innerSchema = properties["legacyInner"] as JsonObject
    (innerSchema["deprecated"] as JsonPrimitive).content.toBoolean() shouldBe true
    (innerSchema["\$ref"] as JsonPrimitive).content shouldContain "InnerDto"

    // Resolved via @SerialName, since the serialized name differs from the Kotlin name.
    val renamedSchema = properties["legacy_renamed"] as JsonObject
    (renamedSchema["deprecated"] as JsonPrimitive).content.toBoolean() shouldBe true
  }

  @Test
  fun `renders deprecated marker for elements of a collection passed to toSchema`() {
    // The entry point erases the element type to a star projection, so no KType reaches the
    // element descriptor and the owner class has to be recovered from its serialName.
    val schema = schemaCreator.toSchema(listOf(DeprecatedFieldDto.example))

    val definition = schema.definitions["DeprecatedFieldDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val legacySchema = properties["legacy"] as JsonObject
    (legacySchema["deprecated"] as JsonPrimitive).content.toBoolean() shouldBe true

    val replacementSchema = properties["replacement"] as JsonObject
    replacementSchema shouldNotContainKey "deprecated"
  }

  @Test
  fun `renders deprecated marker for the get use-site target`() {
    // @get:Deprecated lands on the getter, not the property, so property.annotations alone
    // misses it.
    val schema = schemaCreator.toSchema(GetterDeprecatedDto.example)

    val definition = schema.definitions["GetterDeprecatedDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val legacySchema = properties["legacy"] as JsonObject
    (legacySchema["deprecated"] as JsonPrimitive).content.toBoolean() shouldBe true

    val replacementSchema = properties["replacement"] as JsonObject
    replacementSchema shouldNotContainKey "deprecated"
  }

  @Test
  fun `renders deprecated marker on sealed subclass properties`() {
    val schema = schemaCreator.toSchema(DeprecatedSealedContainerDto.example)

    val definition = schema.definitions["DeprecatedLeaf"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val legacySchema = properties["legacy"] as JsonObject
    (legacySchema["deprecated"] as JsonPrimitive).content.toBoolean() shouldBe true

    val currentSchema = properties["current"] as JsonObject
    currentSchema shouldNotContainKey "deprecated"

    // The synthesised discriminator property is untouched.
    val discriminator = properties["type"] as JsonObject
    discriminator shouldNotContainKey "deprecated"
  }

  @Test
  fun `serial name aliasing another class's qualified name does not read that class's annotations`() {
    // AliasedToDecoyDto's @SerialName is SerialNameDecoy's fully-qualified name, so resolving the
    // owner class from serialName would read the decoy's annotations and mark a field that is not
    // deprecated. The root KType carries the real element class, so the decoy is never consulted.
    //
    // The definition still lands under "SerialNameDecoy" rather than "AliasedToDecoyDto",
    // independently of any of this, since definition naming is keyed on serial name as well.
    val schema = schemaCreator.toSchema(listOf(AliasedToDecoyDto.example))

    val definition = schema.definitions["SerialNameDecoy"] as JsonObject
    val properties = definition["properties"] as JsonObject
    val field = properties["field"] as JsonObject

    field shouldNotContainKey "deprecated"
  }

  @Test
  fun `renders deprecated marker for a class-level SerialName in a top-level list`() {
    // A class-level @SerialName is not a loadable class name, so the serialName fallback cannot
    // recover the owner class. The root KType has to carry the element type instead.
    val schema = schemaCreator.toSchema(listOf(RenamedDeprecatedDto.example))

    val definition = schema.definitions["renamed_item"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val legacySchema = properties["legacy"] as JsonObject
    (legacySchema["deprecated"] as JsonPrimitive).content.toBoolean() shouldBe true
  }

  @Test
  fun `marks deprecated whether a dto is reached directly or as a list element`() {
    // Route order decides which definition wins when http4k merges components, so the two paths
    // have to agree. The nested path threads List<RenamedDeprecatedDto> from the property's
    // returnType; the top-level path builds its element type in resolveRootKType.
    val nested = schemaCreator.toSchema(RenamedDeprecatedContainerDto.example)
    val topLevel = schemaCreator.toSchema(listOf(RenamedDeprecatedDto.example))

    val nestedLegacy = deprecatedFlagOfLegacy(nested.definitions["renamed_item"])
    val topLevelLegacy = deprecatedFlagOfLegacy(topLevel.definitions["renamed_item"])

    nestedLegacy shouldBe true
    topLevelLegacy shouldBe true
  }

  /** Reads `properties.legacy.deprecated` out of a rendered definition. */
  private fun deprecatedFlagOfLegacy(definition: JsonElement?): Boolean? {
    val properties = (definition as JsonObject)["properties"] as JsonObject
    val legacy = properties["legacy"] as JsonObject
    return (legacy["deprecated"] as? JsonPrimitive)?.content?.toBoolean()
  }

  @Test
  fun `ANYOF strategy renders deprecated marker outside the anyOf branches`() {
    val anyOfSchemaCreator =
        KotlinxSerializationJsonSchemaCreator<JsonElement>(
            json = KotlinxSerialization,
            kotlinxJson = kotlinxJson,
            nullableStrategy = NullableStrategy.ANYOF,
        )
    val schema = anyOfSchemaCreator.toSchema(DeprecatedFieldDto.example)

    val definition = schema.definitions["DeprecatedFieldDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    val nullableSchema = properties["legacyNullable"] as JsonObject
    (nullableSchema["deprecated"] as JsonPrimitive).content.toBoolean() shouldBe true

    val anyOfArray = nullableSchema["anyOf"] as JsonArray
    anyOfArray.size shouldBe 2
    for (branch in anyOfArray) {
      (branch as JsonObject) shouldNotContainKey "deprecated"
    }
  }

  // --- @Description ---

  /** Reads a property's `description` out of a rendered definition. */
  private fun descriptionOf(definition: JsonElement?, property: String): String? {
    val properties = (definition as JsonObject)["properties"] as JsonObject
    return ((properties[property] as JsonObject)["description"] as? JsonPrimitive)?.content
  }

  @Test
  fun `renders description for properties annotated with Description`() {
    val schema = schemaCreator.toSchema(DescribedFieldDto.example)
    val definition = schema.definitions["DescribedFieldDto"]

    descriptionOf(definition, "described") shouldBe "What this field is for."

    // Non-annotated properties carry no description key at all, rather than an empty string.
    val properties = (definition as JsonObject)["properties"] as JsonObject
    properties["undescribed"] as JsonObject shouldNotContainKey "description"

    // Composes with the nullable type array rather than replacing it.
    val nullable = properties["describedNullable"] as JsonObject
    (nullable["description"] as JsonPrimitive).content shouldBe "Nullable, and still described."
    (nullable["type"] as JsonArray).map { (it as JsonPrimitive).content } shouldBe
        listOf("string", "null")

    // Sibling of $ref, which OpenAPI 3.1 / JSON Schema 2020-12 permits.
    val inner = properties["describedInner"] as JsonObject
    (inner["description"] as JsonPrimitive).content shouldBe "Described alongside a reference."
    inner shouldContainKey "\$ref"

    descriptionOf(definition, "described_renamed") shouldBe
        "Described under a different serialized name."
  }

  @Test
  fun `falls back to the description on an inlined property type`() {
    val schema = schemaCreator.toSchema(DescribedFieldDto.example)
    val definition = schema.definitions["DescribedFieldDto"]

    // A value class is flattened into the property's schema, so it has no definition of its own
    // to carry the description and the use site is the only place it can go.
    descriptionOf(definition, "fromType") shouldBe
        "A location code, as the traffic systems write it."

    // The property's own description wins over the type's.
    descriptionOf(definition, "overridesType") shouldBe
        "The property wins over the type's own description."
  }

  @Test
  fun `does not copy a type description onto properties that reference it`() {
    val schema = schemaCreator.toSchema(DescribedFieldDto.example)
    val definition = schema.definitions["DescribedFieldDto"] as JsonObject
    val properties = definition["properties"] as JsonObject

    // The referenced type has a definition of its own, so repeating its description at every use
    // site would be noise. It belongs on the definition.
    properties["referenceToDescribedType"] as JsonObject shouldNotContainKey "description"

    val referenced = schema.definitions["DescribedInnerDto"] as JsonObject
    (referenced["description"] as JsonPrimitive).content shouldBe
        "An inner object that describes itself."
  }

  @Test
  fun `renders a class description on an enum definition`() {
    val schema = schemaCreator.toSchema(DescribedEnumContainerDto.example)

    val definition = schema.definitions["DescribedEnum"] as JsonObject
    (definition["description"] as JsonPrimitive).content shouldBe "Which system owns the record."
    (definition["type"] as JsonPrimitive).content shouldBe "string"
  }

  @Test
  fun `renders a class description on a nested enum definition`() {
    val schema = schemaCreator.toSchema(NestedDescribedEnumDto.example)

    // A nested enum's serial name is dotted while its binary name uses `Outer$NestedEnum`, so
    // resolving the class through Class.forName alone silently loses the description.
    val definition = schema.definitions["NestedEnum"] as JsonObject
    (definition["description"] as JsonPrimitive).content shouldBe
        "Described on an enum nested inside its owner."
    (definition["type"] as JsonPrimitive).content shouldBe "string"
  }

  @Test
  fun `renders description for the get use-site target`() {
    val schema = schemaCreator.toSchema(GetterDescribedDto.example)
    val definition = schema.definitions["GetterDescribedDto"]

    descriptionOf(definition, "viaGetter") shouldBe "Described through the getter."
    ((definition as JsonObject)["properties"] as JsonObject)["plain"]
        as JsonObject shouldNotContainKey "description"
  }

  @Test
  fun `renders description for elements of a collection passed to toSchema`() {
    // The entry point erases the element type to a star projection, so the owner class has to be
    // recovered from its serialName before any property annotation is reachable.
    val schema = schemaCreator.toSchema(listOf(DescribedFieldDto.example))

    descriptionOf(schema.definitions["DescribedFieldDto"], "described") shouldBe
        "What this field is for."
  }

  @Test
  fun `ANYOF strategy renders description outside the anyOf branches`() {
    val anyOfSchemaCreator =
        KotlinxSerializationJsonSchemaCreator<JsonElement>(
            json = KotlinxSerialization,
            kotlinxJson = kotlinxJson,
            nullableStrategy = NullableStrategy.ANYOF,
        )
    val schema = anyOfSchemaCreator.toSchema(DescribedFieldDto.example)

    val properties =
        (schema.definitions["DescribedFieldDto"] as JsonObject)["properties"] as JsonObject
    val nullable = properties["describedNullable"] as JsonObject

    (nullable["description"] as JsonPrimitive).content shouldBe "Nullable, and still described."
    val branches = nullable["anyOf"] as JsonArray
    branches.size shouldBe 2
    for (branch in branches) {
      (branch as JsonObject) shouldNotContainKey "description"
    }
  }

  @Test
  fun `ANYOF strategy does not copy a type description onto a nullable reference`() {
    // The nullable $ref sits inside anyOf rather than at the top level. A check that only looked
    // at the top level would read this as an inlined type and duplicate the definition's
    // description onto every field holding one.
    val anyOfSchemaCreator =
        KotlinxSerializationJsonSchemaCreator<JsonElement>(
            json = KotlinxSerialization,
            kotlinxJson = kotlinxJson,
            nullableStrategy = NullableStrategy.ANYOF,
        )
    val schema = anyOfSchemaCreator.toSchema(DescribedNullableRefDto.example)

    val properties =
        (schema.definitions["DescribedNullableRefDto"] as JsonObject)["properties"] as JsonObject
    val inner = properties["inner"] as JsonObject

    // Its own description survives.
    (inner["description"] as JsonPrimitive).content shouldBe "Optional inner."
    inner shouldContainKey "anyOf"

    // This is the case that bites: DescribedInnerDto carries a description and is referenced
    // through a nullable anyOf, so a top-level-only $ref check would inherit it here.
    val describedInner = properties["describedInner"] as JsonObject
    describedInner shouldContainKey "anyOf"
    describedInner shouldNotContainKey "description"
  }

  @Test
  fun `falls back to the type description for a nullable inlined type`() {
    val schema = schemaCreator.toSchema(NullableDescribedTypeDto.example)

    descriptionOf(schema.definitions["NullableDescribedTypeDto"], "code") shouldBe
        "A location code, as the traffic systems write it."
  }

  @Test
  fun `renders a class description on a sealed base definition`() {
    val schema = schemaCreator.toSchema(DescribedSealedContainerDto.example)

    val parent = schema.definitions["DescribedSealedBase"] as JsonObject
    (parent["description"] as JsonPrimitive).content shouldBe "The lifecycle state of an import."

    // The description joins the polymorphic keys rather than displacing them.
    parent shouldContainKey "oneOf"
    parent shouldContainKey "discriminator"
  }

  @Test
  fun `renders a class description on a sealed subclass definition`() {
    val schema = schemaCreator.toSchema(DescribedSealedContainerDto.example)

    val completed = schema.definitions["DescribedCompleted"] as JsonObject
    (completed["description"] as JsonPrimitive).content shouldBe
        "The import finished without errors."
    descriptionOf(completed, "at") shouldBe "When the import finished."

    // A sibling subclass in the same hierarchy is left alone.
    schema.definitions["DescribedPending"] as JsonObject shouldNotContainKey "description"
  }

  @Test
  fun `describes a sealed subclass the same reached directly or through its base`() {
    val throughBase = schemaCreator.toSchema(DescribedSealedContainerDto.example)
    val directly = schemaCreator.toSchema(DescribedSubclassDirectDto.example)

    // The two paths key the definition differently — the sealed walk names it after the Kotlin
    // class, the direct walk after @SerialName — which predates descriptions and is left alone
    // here. What must agree is the description itself.
    val fromBase = (throughBase.definitions["DescribedCompleted"] as JsonObject)["description"]
    val fromDirect = (directly.definitions["described_completed"] as JsonObject)["description"]

    fromDirect shouldBe fromBase
    (fromDirect as JsonPrimitive).content shouldBe "The import finished without errors."
  }

  @Test
  fun `does not copy a sealed base description onto properties that reference it`() {
    val schema = schemaCreator.toSchema(DescribedSealedContainerDto.example)

    // The base owns a definition, so its description belongs there and nowhere else.
    descriptionOf(schema.definitions["DescribedSealedContainerDto"], "state") shouldBe null
  }
}
