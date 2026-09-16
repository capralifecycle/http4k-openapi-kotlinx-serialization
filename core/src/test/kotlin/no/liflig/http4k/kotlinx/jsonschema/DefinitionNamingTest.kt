package no.liflig.http4k.kotlinx.jsonschema

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

/** Definition keys: prefixes, `overrideDefinitionId`, and short-name collisions. */
class DefinitionNamingTest {

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
  fun `overrideDefinitionId on sealed class root renames parent definition`() {
    val schema =
        schemaCreator.toSchema(
            SealedChild1.example,
            overrideDefinitionId = "RenamedSubclass",
        )

    schema.definitions shouldContainKey "RenamedSubclass"
    schema.definitions shouldNotContainKey "SealedChild1"
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

  @Test
  fun `three-way short-name collision disambiguates every definition`() {
    val schema = schemaCreator.toSchema(ThreeUsersDto.example)

    schema.definitions shouldContainKey "packageA_User"
    schema.definitions shouldContainKey "packageB_User"
    schema.definitions shouldContainKey "packageC_User"
    schema.definitions shouldNotContainKey "User"

    val def = schema.definitions["ThreeUsersDto"]!!
    val asString = kotlinx.serialization.json.Json.encodeToString(JsonElement.serializer(), def)
    asString shouldContain "#/components/schemas/packageC_User"
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
}
