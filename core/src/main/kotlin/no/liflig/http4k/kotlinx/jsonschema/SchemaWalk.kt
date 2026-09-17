package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.starProjectedType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.getContextualDescriptor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.serializer
import org.http4k.format.Json
import org.http4k.format.JsonType

/**
 * One traversal of a [SerialDescriptor] tree, producing JSON Schema nodes and registering every
 * named definition it meets in a [DefinitionRegistry]. Created per `toSchema` call.
 */
@OptIn(ExperimentalSerializationApi::class)
internal class SchemaWalk<NODE : Any>(
    private val json: Json<NODE>,
    private val kotlinxJson: kotlinx.serialization.json.Json,
    private val registry: DefinitionRegistry<NODE>,
    private val sealedClassExampleProvider: SealedClassExampleProvider,
    private val formatMappings: Map<String, String>,
    private val nullableStrategy: NullableStrategy,
) {

  /**
   * @param kType Optional Kotlin type corresponding to this descriptor, threaded through the
   *   traversal to recover the declared KClass when `descriptor.serialName` is not a loadable class
   *   name (e.g. sealed parents with `@SerialName`). Also used to resolve generic type arguments
   *   for List/Map/Set element types and inline value class inner types.
   */
  fun schemaFor(descriptor: SerialDescriptor, jsonElement: JsonElement?, kType: KType?): NODE {
    // Nullable descriptors have "?" appended to serialName by kotlinx.serialization.
    // Normalize once here so individual handlers don't need to strip it.
    val serialName = descriptor.serialName.removeSuffix("?")

    val baseSchema =
        when (descriptor.kind) {
          is PrimitiveKind ->
              primitiveToSchema(descriptor.kind as PrimitiveKind, serialName, jsonElement)
          SerialKind.ENUM -> enumToSchema(descriptor, serialName, kType)
          SerialKind.CONTEXTUAL -> {
            val contextualDescriptor =
                kotlinxJson.serializersModule.getContextualDescriptor(descriptor)
                    ?: throw IllegalArgumentException(
                        "Unregistered contextual type: ${descriptor.serialName}"
                    )
            schemaFor(contextualDescriptor, jsonElement, kType)
          }
          StructureKind.CLASS ->
              if (descriptor.isInline) {
                schemaFor(
                    descriptor.getElementDescriptor(0),
                    jsonElement,
                    resolveInlineInnerType(kType),
                )
              } else {
                classToSchema(descriptor, serialName, jsonElement, kType)
              }
          StructureKind.OBJECT -> json.obj("type" to json.string("object"))
          StructureKind.LIST -> listToSchema(descriptor, jsonElement, kType)
          StructureKind.MAP -> mapToSchema(descriptor, jsonElement, kType)
          is PolymorphicKind.SEALED -> sealedToSchema(descriptor, serialName, kType)
          is PolymorphicKind.OPEN ->
              throw IllegalArgumentException(
                  "PolymorphicKind.OPEN is not supported for JSON Schema generation"
              )
          else -> throw IllegalArgumentException("Unsupported descriptor kind: ${descriptor.kind}")
        }

    return if (descriptor.isNullable) wrapNullable(baseSchema) else baseSchema
  }

  private fun primitiveToSchema(
      kind: PrimitiveKind,
      serialName: String,
      jsonElement: JsonElement?,
  ): NODE {
    val (type, format) =
        when (kind) {
          PrimitiveKind.STRING -> "string" to null
          PrimitiveKind.INT -> "integer" to "int32"
          PrimitiveKind.LONG -> "integer" to "int64"
          PrimitiveKind.DOUBLE -> "number" to "double"
          PrimitiveKind.FLOAT -> "number" to "float"
          PrimitiveKind.BOOLEAN -> "boolean" to null
          PrimitiveKind.BYTE -> "integer" to "int32"
          PrimitiveKind.SHORT -> "integer" to "int32"
          PrimitiveKind.CHAR -> "string" to null
        }
    val resolvedFormat = format ?: formatMappings[serialName.substringAfterLast('.')]

    val fields = mutableListOf<Pair<String, NODE>>()
    fields.add("type" to json.string(type))
    resolvedFormat?.let { fields.add("format" to json.string(it)) }
    if (jsonElement != null && jsonElement !is JsonNull) {
      fields.add("example" to convertJsonElement(jsonElement))
    }

    return json.obj(fields)
  }

  private fun enumToSchema(descriptor: SerialDescriptor, serialName: String, kType: KType?): NODE {
    return registry.define(serialName, serialName.substringAfterLast('.')) {
      val elementNames = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
      withClassDescription(
          json.obj(
              "type" to json.string("string"),
              "enum" to json.array(elementNames.map { json.string(it) }),
          ),
          kClassOf(kType, serialName),
      )
    }
  }

  private fun classToSchema(
      descriptor: SerialDescriptor,
      serialName: String,
      jsonElement: JsonElement?,
      kType: KType?,
  ): NODE {
    return registry.define(serialName, serialName.substringAfterLast('.')) {
      withClassDescription(
          buildObjectProperties(descriptor, jsonElement as? JsonObject, kType).toSchema(),
          kClassOf(kType, serialName),
      )
    }
  }

  private fun listToSchema(
      descriptor: SerialDescriptor,
      jsonElement: JsonElement?,
      kType: KType?,
  ): NODE {
    val itemSchema =
        schemaFor(
            descriptor.getElementDescriptor(0),
            (jsonElement as? JsonArray)?.firstOrNull(),
            kType?.arguments?.firstOrNull()?.type,
        )
    return json.obj("type" to json.string("array"), "items" to itemSchema)
  }

  private fun mapToSchema(
      descriptor: SerialDescriptor,
      jsonElement: JsonElement?,
      kType: KType?,
  ): NODE {
    val keyDescriptor = descriptor.getElementDescriptor(0)
    if (keyDescriptor.kind !is PrimitiveKind.STRING) {
      throw IllegalArgumentException(
          "Map keys must be strings for JSON Schema generation. Found: ${keyDescriptor.kind}"
      )
    }

    val valueSchema =
        schemaFor(
            descriptor.getElementDescriptor(1),
            (jsonElement as? JsonObject)?.values?.firstOrNull(),
            kType?.arguments?.getOrNull(1)?.type,
        )
    return json.obj("type" to json.string("object"), "additionalProperties" to valueSchema)
  }

  private fun wrapNullable(schema: NODE): NODE =
      when {
        nullableStrategy == NullableStrategy.ANYOF -> anyOfNull(schema)
        // A $ref has no "type" to merge "null" into, so it stays as it is. See NullableStrategy.
        fieldNamed(schema, "\$ref") != null -> schema
        fieldNamed(schema, "type") != null ->
            json.obj(
                json.fields(schema).map { (name, value) ->
                  if (name == "type") name to json.array(listOf(value, json.string("null")))
                  else name to value
                }
            )
        // Neither: not produced by this walk, but anyOf is always a valid way to say nullable.
        else -> anyOfNull(schema)
      }

  private fun anyOfNull(schema: NODE): NODE =
      json.obj("anyOf" to json.array(listOf(schema, json.obj("type" to json.string("null")))))

  private fun fieldNamed(node: NODE, name: String): NODE? =
      json.fields(node).firstOrNull { (field, _) -> field == name }?.second

  private fun NODE.plusField(name: String, value: NODE): NODE =
      json.obj(json.fields(this).toList() + (name to value))

  /**
   * Appends `"deprecated": true` to an already-built property schema.
   *
   * Applied after [wrapNullable], so the marker lands on the outer object in every shape the walk
   * produces: alongside `type` for primitives, as a sibling of `$ref` for reference types (which
   * OpenAPI 3.1 / JSON Schema 2020-12 permits), and as a sibling of `anyOf` under
   * [NullableStrategy.ANYOF] rather than inside one of its branches.
   */
  private fun withDeprecatedMarker(schema: NODE): NODE =
      schema.plusField("deprecated", json.boolean(true))

  /**
   * Appends `"description"` to an already-built property schema.
   *
   * Applied after [wrapNullable] for the same reason as [withDeprecatedMarker]: the description
   * belongs to the property, so it lands on the outer object in every shape the walk produces
   * rather than inside one branch of an `anyOf`.
   */
  private fun withDescription(schema: NODE, description: String): NODE =
      schema.plusField("description", json.string(description))

  private fun withDefaultValue(schema: NODE, value: JsonElement): NODE =
      schema.plusField("default", convertJsonElement(value))

  /**
   * The Kotlin default value for [elementName], as actually assigned by the constructor — not the
   * current example's value, which may differ from the default.
   *
   * Decodes [jsonObj] with [elementName] removed, using the owning class's own serializer: kotlinx
   * fills the omitted key with its real default regardless of `encodeDefaults` (that flag only
   * governs encoding). Re-encoding the decoded instance with the same [kotlinxJson] — which the
   * caller has already established has `encodeDefaults = true` — then always includes the filled-in
   * field, so its value can be read straight back out. No per-type value handling needed.
   *
   * Returns null (silently degrading to "optional, no default shown") when there is no concrete
   * example to decode from, no resolvable class, decoding fails, or the default is `null` — a
   * `"default": null` alongside other schema fields would be stripped by
   * [no.liflig.http4k.kotlinx.openapi.KotlinxOpenApi3Renderer]'s null-stripping pass outside
   * example payloads, so it is omitted here rather than emitted and silently dropped later.
   */
  private fun defaultValueOf(
      ownerKClass: KClass<*>?,
      jsonObj: JsonObject?,
      elementName: String,
  ): JsonElement? {
    if (ownerKClass == null || jsonObj == null) return null
    return try {
      val serializer = kotlinxJson.serializersModule.serializer(ownerKClass.java)
      val probe = JsonObject(jsonObj.filterKeys { it != elementName })
      val decoded = kotlinxJson.decodeFromJsonElement(serializer, probe)
      val reEncoded = kotlinxJson.encodeToJsonElement(serializer, decoded) as? JsonObject
      reEncoded?.get(elementName)?.takeIf { it !is JsonNull }
    } catch (_: kotlinx.serialization.SerializationException) {
      null
    }
  }

  /**
   * The description for a property: its own [Description], or failing that the one on its declared
   * type.
   *
   * The type-level fallback applies only where [schema] renders the type inlined — a value class,
   * or one whose custom serializer produces a primitive. Those have no definition of their own to
   * carry a shared description, so the use site is the only place one can go. A type that renders
   * as `$ref` does have a definition, and [withClassDescription] puts its description there
   * instead, once, rather than repeating it at every field holding it.
   */
  private fun descriptionOf(property: KProperty1<*, *>?, schema: NODE): String? {
    if (property == null) return null
    descriptionOn(property)?.let {
      return it
    }
    if (!rendersAsReference(schema)) {
      descriptionOn(property.returnType.classifier as? KClass<*>)?.let {
        return it
      }
    }
    return null
  }

  /**
   * True when [schema] points at a definition rather than describing the type in place.
   *
   * Checks inside `anyOf` branches as well as the top level, since a nullable reference renders as
   * `{"anyOf": [{"$ref": ...}, {"type": "null"}]}` under [NullableStrategy.ANYOF] — the `$ref` sits
   * one level down, and a top-level-only check would mistake it for an inlined type and duplicate
   * the definition's description onto every field.
   */
  private fun rendersAsReference(schema: NODE): Boolean {
    if (fieldNamed(schema, "\$ref") != null) return true

    val branches = fieldNamed(schema, "anyOf") ?: return false
    return json.typeOf(branches) == JsonType.Array &&
        json.elements(branches).any { branch ->
          json.typeOf(branch) == JsonType.Object && fieldNamed(branch, "\$ref") != null
        }
  }

  /**
   * Appends the [Description] declared on [kClass] to that type's own definition, so a type
   * rendered as `$ref` is described once rather than at each use site.
   *
   * Prepended rather than appended: `description` reads as a heading for the schema that follows,
   * and http4k's own `ApiPath` rendering places it there too.
   */
  private fun withClassDescription(schema: NODE, kClass: KClass<*>?): NODE =
      descriptionOn(kClass)?.let { description ->
        json.obj(listOf("description" to json.string(description)) + json.fields(schema).toList())
      } ?: schema

  private fun convertJsonElement(element: JsonElement): NODE {
    return when (element) {
      is JsonPrimitive -> {
        when {
          element.isString -> json.string(element.content)
          element.content == "true" || element.content == "false" ->
              json.boolean(element.content.toBoolean())
          else -> {
            val content = element.content
            // Non-integral numbers go through the Double overload rather than BigDecimal:
            // http4k's kotlinx backend renders BigDecimal as JsonPrimitive("$this"), a *quoted*
            // primitive, which would emit a JSON string under "type": "number" and leave the
            // example failing validation against its own schema. Its Double and BigInteger
            // overloads are unquoted. BigDecimal still gates validity, so NaN, Infinity and
            // out-of-range magnitudes keep falling through to a string as before.
            content.toBigIntegerOrNull()?.let { json.number(it) }
                ?: content
                    .toBigDecimalOrNull()
                    ?.toDouble()
                    ?.takeIf { it.isFinite() }
                    ?.let { json.number(it) }
                ?: json.string(content)
          }
        }
      }
      is JsonArray -> json.array(element.map { convertJsonElement(it) })
      is JsonObject -> json.obj(element.entries.map { (k, v) -> k to convertJsonElement(v) })
      is JsonNull -> json.nullNode()
    }
  }

  /**
   * The members of an object schema: its properties in declaration order and the required names.
   */
  private inner class ObjectMembers(
      val properties: List<Pair<String, NODE>>,
      val required: List<String>,
  ) {
    operator fun plus(other: ObjectMembers) =
        ObjectMembers(properties + other.properties, required + other.required)

    fun toSchema(): NODE =
        json.obj(
            listOfNotNull(
                "type" to json.string("object"),
                "properties" to json.obj(properties),
                if (required.isEmpty()) null
                else "required" to json.array(required.map { json.string(it) }),
            )
        )
  }

  private fun buildObjectProperties(
      descriptor: SerialDescriptor,
      jsonObj: JsonObject?,
      kType: KType?,
  ): ObjectMembers {
    val ownerKClass = kClassOf(kType, descriptor.serialName)
    val encodeDefaults = kotlinxJson.configuration.encodeDefaults
    val properties = mutableListOf<Pair<String, NODE>>()
    val required = mutableListOf<String>()

    for (i in 0 until descriptor.elementsCount) {
      val elementName = descriptor.getElementName(i)
      val property = resolveProperty(ownerKClass, elementName)

      val elementSchema =
          schemaFor(
              descriptor.getElementDescriptor(i),
              jsonObj?.get(elementName),
              property?.returnType,
          )
      val describedSchema =
          descriptionOf(property, elementSchema)?.let { withDescription(elementSchema, it) }
              ?: elementSchema
      val deprecatedSchema =
          if (isDeprecated(property)) withDeprecatedMarker(describedSchema) else describedSchema

      // A property with a Kotlin default is only really optional on the wire when the encoder
      // actually emits defaulted values (encodeDefaults = true) — otherwise a value equal to the
      // default is omitted from output and consumers can't rely on its presence either way, so it
      // stays required. See defaultValueOf for how the default's value is recovered.
      val hasDefault = descriptor.isElementOptional(i)
      val finalSchema =
          if (hasDefault && encodeDefaults) {
            defaultValueOf(ownerKClass, jsonObj, elementName)?.let {
              withDefaultValue(deprecatedSchema, it)
            } ?: deprecatedSchema
          } else deprecatedSchema

      properties.add(elementName to finalSchema)
      if (!hasDefault || !encodeDefaults) {
        required.add(elementName)
      }
    }

    return ObjectMembers(properties, required)
  }

  private fun sealedToSchema(
      descriptor: SerialDescriptor,
      serialName: String,
      kType: KType?,
  ): NODE {
    require(descriptor.elementsCount >= 2) {
      "Unexpected SEALED descriptor structure: elementsCount=${descriptor.elementsCount}"
    }
    val sealedKClass =
        kClassOf(kType, serialName)
            ?: throw IllegalStateException(
                "Cannot load sealed class '$serialName' to build its schema (subclass discovery, " +
                    "examples and type description). Ensure the class is on the classpath or has " +
                    "a resolvable owner type."
            )
    return registry.define(
        sealedKClass.qualifiedName ?: serialName,
        sealedKClass.simpleName ?: serialName.substringAfterLast('.'),
    ) {
      sealedParentSchema(descriptor, sealedKClass)
    }
  }

  private fun sealedParentSchema(descriptor: SerialDescriptor, sealedKClass: KClass<*>): NODE {
    // Discriminator name: prefer @JsonClassDiscriminator on the sealed parent (which the
    // JSON encoder uses at runtime), fall back to the global Json.classDiscriminator config.
    // Reading descriptor.getElementName(0) would always return the SealedClassSerializer's
    // generator default ("type") and miss per-hierarchy overrides.
    val classDiscriminator =
        descriptor.annotations
            .filterIsInstance<kotlinx.serialization.json.JsonClassDiscriminator>()
            .firstOrNull()
            ?.discriminator ?: kotlinxJson.configuration.classDiscriminator

    // The descriptor only carries each subclass's @SerialName, which can repeat across
    // hierarchies. The leaf KClass supplies what the descriptor cannot: a unique identity, the
    // simple name, and the KType for property traversal.
    val leavesBySerialName = collectLeafSubclasses(sealedKClass).associateBy { serialNameOf(it) }
    val examplesBySerialName =
        sealedClassExampleProvider.getExamples(sealedKClass).associateBy { serialNameOf(it::class) }

    val subclassContainer = descriptor.getElementDescriptor(1)
    val identityByDiscriminator =
        (0 until subclassContainer.elementsCount)
            .map { subclassContainer.getElementDescriptor(it) }
            .associate { subclass ->
              subclass.serialName to
                  defineSealedSubclass(
                      subclass,
                      classDiscriminator,
                      leavesBySerialName[subclass.serialName],
                      examplesBySerialName[subclass.serialName],
                  )
            }

    return withClassDescription(
        json.obj(
            "oneOf" to json.array(identityByDiscriminator.values.map { registry.ref(it) }),
            "discriminator" to
                json.obj(
                    "propertyName" to json.string(classDiscriminator),
                    "mapping" to
                        json.obj(
                            identityByDiscriminator.map { (value, identity) ->
                              value to json.string(registry.refPath(identity))
                            }
                        ),
                ),
        ),
        sealedKClass,
    )
  }

  /** Registers the definition for one sealed subclass and returns its identity. */
  private fun defineSealedSubclass(
      descriptor: SerialDescriptor,
      classDiscriminator: String,
      leaf: KClass<*>?,
      example: Any?,
  ): String {
    val discriminatorValue = descriptor.serialName
    // The identity is the qualified class name, not the discriminator value: two unrelated
    // hierarchies can both have a `@SerialName("created")` subclass, and the class name keeps
    // them distinct while letting naming disambiguate when the simple names collide too.
    val identity = leaf?.qualifiedName ?: discriminatorValue
    registry.define(identity, leaf?.simpleName ?: discriminatorValue.substringAfterLast('.')) {
      val exampleJson =
          example?.let {
            kotlinxJson.encodeToJsonElement(
                kotlinxJson.serializersModule.serializer(it::class.java),
                it,
            ) as? JsonObject
          }
      val discriminatorMember =
          ObjectMembers(
              listOf(
                  classDiscriminator to
                      json.obj(
                          "type" to json.string("string"),
                          "enum" to json.array(listOf(json.string(discriminatorValue))),
                      )
              ),
              listOf(classDiscriminator),
          )
      val members =
          discriminatorMember +
              buildObjectProperties(descriptor, exampleJson, leaf?.starProjectedType)
      withClassDescription(members.toSchema(), leaf)
    }
    return identity
  }

  private fun serialNameOf(kClass: KClass<*>): String =
      kotlinxJson.serializersModule.serializer(kClass.java).descriptor.serialName
}
