package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.starProjectedType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
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
          // The KType first, as in classToSchema: loadKClass goes through
          // Class.forName, which cannot resolve a nested enum's dotted serial name
          // against its `Outer$Inner` binary name and would silently drop the
          // description.
          (kType?.classifier as? KClass<*>) ?: loadKClass(serialName),
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
      val (properties, requiredFields) =
          buildObjectProperties(descriptor, jsonElement as? JsonObject, kType)

      val schemaFields = mutableListOf<Pair<String, NODE>>()
      schemaFields.add("type" to json.string("object"))
      schemaFields.add("properties" to json.obj(properties))
      if (requiredFields.isNotEmpty()) {
        schemaFields.add("required" to json.array(requiredFields.map { json.string(it) }))
      }

      withClassDescription(
          json.obj(schemaFields),
          (kType?.classifier as? KClass<*>) ?: loadKClass(serialName),
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

  private fun wrapNullable(schema: NODE): NODE {
    return when (nullableStrategy) {
      NullableStrategy.ANYOF ->
          json.obj("anyOf" to json.array(listOf(schema, json.obj("type" to json.string("null")))))

      NullableStrategy.TYPE_ARRAY -> {
        val fields = json.fields(schema).toList()
        val hasRef = fields.any { (k, _) -> k == "\$ref" }

        if (hasRef) {
          // $ref types: return as-is — no "type" field exists to merge "null" into
          schema
        } else {
          // Primitive/inline types: merge "null" into type array
          val typeField = fields.find { (k, _) -> k == "type" }
          if (typeField != null) {
            val newFields =
                fields.map { (k, v) ->
                  if (k == "type") k to json.array(listOf(v, json.string("null"))) else k to v
                }
            json.obj(newFields)
          } else {
            // Fallback for schemas without type or $ref (shouldn't occur in practice)
            json.obj("anyOf" to json.array(listOf(schema, json.obj("type" to json.string("null")))))
          }
        }
      }
    }
  }

  /**
   * Appends `"deprecated": true` to an already-built property schema.
   *
   * Applied after [wrapNullable], so the marker lands on the outer object in every shape the walk
   * produces: alongside `type` for primitives, as a sibling of `$ref` for reference types (which
   * OpenAPI 3.1 / JSON Schema 2020-12 permits), and as a sibling of `anyOf` under
   * [NullableStrategy.ANYOF] rather than inside one of its branches.
   */
  private fun withDeprecatedMarker(schema: NODE): NODE =
      json.obj(json.fields(schema).toList() + ("deprecated" to json.boolean(true)))

  /**
   * Appends `"description"` to an already-built property schema.
   *
   * Applied after [wrapNullable] for the same reason as [withDeprecatedMarker]: the description
   * belongs to the property, so it lands on the outer object in every shape the walk produces
   * rather than inside one branch of an `anyOf`.
   */
  private fun withDescription(schema: NODE, description: String): NODE =
      json.obj(json.fields(schema).toList() + ("description" to json.string(description)))

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
    if (json.fields(schema).any { (name, _) -> name == "\$ref" }) return true

    val branches = json.fields(schema).firstOrNull { (name, _) -> name == "anyOf" }?.second
    return branches != null &&
        json.typeOf(branches) == JsonType.Array &&
        json.elements(branches).any { branch ->
          json.typeOf(branch) == JsonType.Object &&
              json.fields(branch).any { (name, _) -> name == "\$ref" }
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

  private fun buildObjectProperties(
      descriptor: SerialDescriptor,
      jsonObj: JsonObject?,
      kType: KType?,
  ): Pair<List<Pair<String, NODE>>, List<String>> {
    val properties = mutableListOf<Pair<String, NODE>>()
    val requiredFields = mutableListOf<String>()
    val ownerKClass = (kType?.classifier as? KClass<*>) ?: loadKClass(descriptor.serialName)

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

      properties.add(
          elementName to
              if (isDeprecated(property)) withDeprecatedMarker(describedSchema) else describedSchema
      )

      if (!descriptor.isElementOptional(i)) {
        requiredFields.add(elementName)
      }
    }

    return properties to requiredFields
  }

  private fun sealedToSchema(
      descriptor: SerialDescriptor,
      serialName: String,
      kType: KType?,
  ): NODE {
    require(descriptor.elementsCount >= 2) {
      "Unexpected SEALED descriptor structure: elementsCount=${descriptor.elementsCount}"
    }

    // Discriminator name: prefer @JsonClassDiscriminator on the sealed parent (which the
    // JSON encoder uses at runtime), fall back to the global Json.classDiscriminator config.
    // Reading descriptor.getElementName(0) would always return the SealedClassSerializer's
    // generator default ("type") and miss per-hierarchy overrides.
    val classDiscriminator =
        descriptor.annotations
            .filterIsInstance<kotlinx.serialization.json.JsonClassDiscriminator>()
            .firstOrNull()
            ?.discriminator ?: kotlinxJson.configuration.classDiscriminator

    val subclassContainerDescriptor = descriptor.getElementDescriptor(1)

    val sealedKClass =
        try {
          Class.forName(serialName).kotlin
        } catch (_: ClassNotFoundException) {
          // @SerialName on the sealed parent makes serialName differ from the FQ class name.
          // Fall back to the KType threaded through the traversal.
          (kType?.classifier as? KClass<*>)
              ?: throw IllegalStateException(
                  "Cannot load sealed class '${serialName}' for example discovery. " +
                      "Ensure the class is on the classpath or has a resolvable owner type.",
              )
        }
    val parentIdentity = sealedKClass.qualifiedName ?: serialName
    val parentShortName = sealedKClass.simpleName ?: serialName.substringAfterLast('.')
    return registry.define(parentIdentity, parentShortName) {
      sealedParentSchema(subclassContainerDescriptor, classDiscriminator, sealedKClass)
    }
  }

  private fun sealedParentSchema(
      subclassContainerDescriptor: SerialDescriptor,
      classDiscriminator: String,
      sealedKClass: KClass<*>,
  ): NODE {
    val examples = sealedClassExampleProvider.getExamples(sealedKClass)
    val examplesBySerialName =
        examples.associateBy { example ->
          kotlinxJson.serializersModule.serializer(example::class.java).descriptor.serialName
        }

    // Map @SerialName values to Kotlin class names and KClasses to avoid definition key
    // collisions when multiple sealed hierarchies share the same @SerialName discriminator values,
    // and to thread KType context to subclass property traversal.
    val leafSubclasses = collectLeafSubclasses(sealedKClass)
    val subclassClassNames: Map<String, String> =
        leafSubclasses.associate { subclass ->
          val serializer = kotlinxJson.serializersModule.serializer(subclass.java)
          serializer.descriptor.serialName to subclass.simpleName!!
        }
    val subclassKClasses: Map<String, KClass<*>> =
        leafSubclasses.associate { subclass ->
          val serializer = kotlinxJson.serializersModule.serializer(subclass.java)
          serializer.descriptor.serialName to subclass
        }

    val oneOfRefs = mutableListOf<NODE>()
    val discriminatorMapping = mutableMapOf<String, String>()

    for (i in 0 until subclassContainerDescriptor.elementsCount) {
      val subclassDescriptor = subclassContainerDescriptor.getElementDescriptor(i)
      val discriminatorValue = subclassDescriptor.serialName
      val shortName =
          subclassClassNames[discriminatorValue] ?: discriminatorValue.substringAfterLast('.')

      val example = examplesBySerialName[discriminatorValue]
      val exampleJson =
          example?.let {
            @Suppress("UNCHECKED_CAST")
            kotlinxJson.encodeToJsonElement(
                kotlinxJson.serializersModule.serializer(it::class.java) as KSerializer<Any>,
                it,
            ) as? JsonObject
          }

      val properties = mutableListOf<Pair<String, NODE>>()
      val requiredFields = mutableListOf<String>()

      properties.add(
          classDiscriminator to
              json.obj(
                  "type" to json.string("string"),
                  "enum" to json.array(listOf(json.string(discriminatorValue))),
              )
      )
      requiredFields.add(classDiscriminator)

      val subclassKType = subclassKClasses[discriminatorValue]?.starProjectedType
      val (subclassProperties, subclassRequiredFields) =
          buildObjectProperties(subclassDescriptor, exampleJson, subclassKType)
      properties.addAll(subclassProperties)
      requiredFields.addAll(subclassRequiredFields)

      val schemaFields = mutableListOf<Pair<String, NODE>>()
      schemaFields.add("type" to json.string("object"))
      schemaFields.add("properties" to json.obj(properties))
      if (requiredFields.isNotEmpty()) {
        schemaFields.add("required" to json.array(requiredFields.map { json.string(it) }))
      }

      val subclassSchema =
          withClassDescription(json.obj(schemaFields), subclassKClasses[discriminatorValue])

      // Use the subclass's qualified class name (not the @SerialName discriminator value)
      // as the identity passed to the registry. The discriminator value can be reused
      // across sealed hierarchies (e.g. two unrelated trees both with a `@SerialName("created")`
      // subclass); FQCN keeps them distinct and lets collision resolution rename when
      // their simple names also collide.
      val subclassIdentity =
          subclassKClasses[discriminatorValue]?.qualifiedName ?: discriminatorValue
      oneOfRefs.add(registry.define(subclassIdentity, shortName) { subclassSchema })
      discriminatorMapping[discriminatorValue] = registry.refPath(subclassIdentity)
    }

    return withClassDescription(
        json.obj(
            "oneOf" to json.array(oneOfRefs),
            "discriminator" to
                json.obj(
                    "propertyName" to json.string(classDiscriminator),
                    "mapping" to
                        json.obj(discriminatorMapping.map { (k, v) -> k to json.string(v) }),
                ),
        ),
        sealedKClass,
    )
  }
}
