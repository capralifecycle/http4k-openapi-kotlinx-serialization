package no.liflig.http4k.kotlinx.jsonschema

import io.kotest.matchers.collections.shouldContain
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

/** `@Deprecated` and `@Description` on properties, types, and sealed hierarchies. */
class PropertyAnnotationsTest {

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
  fun `does not copy a sealed base description onto properties that reference it`() {
    val schema = schemaCreator.toSchema(DescribedSealedContainerDto.example)

    // The base owns a definition, so its description belongs there and nowhere else.
    descriptionOf(schema.definitions["DescribedSealedContainerDto"], "state") shouldBe null
  }
}
