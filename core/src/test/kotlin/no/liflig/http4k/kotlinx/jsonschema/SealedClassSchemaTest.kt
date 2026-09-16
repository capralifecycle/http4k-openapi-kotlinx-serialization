package no.liflig.http4k.kotlinx.jsonschema

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/** Sealed hierarchies: `oneOf`, discriminators, subclass examples, and how they nest. */
class SealedClassSchemaTest {

  @Test
  fun `uses companion example of a leaf below a nested sealed level`() {
    val schema = schemaCreator.toSchema(MultiLevelContainerDto.example)

    val leaf = schema.definitions["CreationError"].shouldNotBeNull() as JsonObject
    val message = (leaf["properties"] as JsonObject)["message"] as JsonObject
    (message["example"] as? JsonPrimitive)?.content shouldBe "Unknown child reference"

    val base = schema.definitions["MultiLevelBase"] as JsonObject
    (base["oneOf"] as JsonArray).map {
      ((it as JsonObject)["\$ref"] as JsonPrimitive).content
    } shouldBe
        listOf(
            "#/components/schemas/CreationError",
            "#/components/schemas/Unknown",
            "#/components/schemas/Ok",
        )
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
  fun `sealed parent with SerialName resolves through SerialNamed property`() {
    val schema = schemaCreator.toSchema(SerialNamedPropertyWithSealedDto.example)

    // The sealed parent has @SerialName("custom_event") and the property has
    // @SerialName("event_payload").
    // KType resolution must handle the property name mismatch.
    schema.definitions shouldContainKey "CustomNamedSealedParent"
    schema.definitions shouldContainKey "Started"
    schema.definitions shouldContainKey "Completed"
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
}
