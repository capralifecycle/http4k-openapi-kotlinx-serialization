package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.starProjectedType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import org.http4k.contract.jsonschema.JsonSchema
import org.http4k.contract.jsonschema.JsonSchemaCreator
import org.http4k.format.AutoMarshallingJson
import org.http4k.format.JsonType

@OptIn(ExperimentalSerializationApi::class)
class KotlinxSerializationJsonSchemaCreator<NODE : Any>(
    private val json: AutoMarshallingJson<NODE>,
    private val kotlinxJson: kotlinx.serialization.json.Json,
    private val refLocationPrefix: String = "components/schemas",
    private val sealedClassExampleProvider: SealedClassExampleProvider =
        DefaultSealedClassExampleProvider(),
    private val formatMappings: Map<String, String> = emptyMap(),
    private val nullableStrategy: NullableStrategy = NullableStrategy.TYPE_ARRAY,
    /**
     * Whether a property's [Deprecated] message is appended to its `description`.
     *
     * Off by default, and deliberately: `@Deprecated` messages already exist throughout every
     * service, and they are written for Kotlin callers rather than for API consumers — "Legacy from
     * the 2019 import, ask before touching" is as likely as "Use originPlc instead". Defaulting
     * this on would turn a routine version bump into a change in what a service publishes, which is
     * not something anyone reviews a dependency update expecting.
     *
     * Turn it on where the messages are known to read well to a consumer. `"deprecated": true` is
     * rendered either way; this only governs the prose.
     */
    private val includeDeprecationMessages: Boolean = false,
) : JsonSchemaCreator<Any, NODE> {

  companion object {
    /** Common format mappings for well-known types serialized as strings. */
    val COMMON_FORMAT_MAPPINGS =
        mapOf(
            "Instant" to "date-time",
            "LocalDate" to "date",
            "LocalDateTime" to "date-time",
            "ZonedDateTime" to "date-time",
            "UUID" to "uuid",
            "URI" to "uri",
        )
  }

  private class DefinitionAccumulator<NODE>(
      val schemas: MutableMap<String, NODE> = mutableMapOf(),
      val serialNames: MutableMap<String, String> = mutableMapOf(),
      /**
       * Tracks definitions that were renamed mid-walk due to short-name collisions. Maps the
       * *original* definition key (what already-emitted refs are pointing at) to the *new* key.
       * Applied as a post-walk sweep in [toSchema] so stale `$ref`s are rewritten consistently
       * across [schemas] and the root node.
       */
      val collisionRenames: MutableMap<String, String> = mutableMapOf(),
  )

  override fun toSchema(
      obj: Any,
      overrideDefinitionId: String?,
      refModelNamePrefix: String?,
  ): JsonSchema<NODE> {
    val (serializer, jsonElement) =
        try {
          resolveSerializerAndEncode(obj)
        } catch (e: kotlinx.serialization.SerializationException) {
          // http4k passes `object {}` as a sentinel value in exampleSchemaIsValid.
          // Only swallow that case; real serializer-registration failures must propagate
          // so missing or broken `@Serializable` DTOs surface at OpenAPI-build time.
          if (obj::class.java.isAnonymousClass) {
            return JsonSchema(json.obj(), emptyMap())
          }
          throw e
        }
    val descriptor = serializer.descriptor

    val defs = DefinitionAccumulator<NODE>()
    val visited = mutableSetOf<String>()

    val rootKType = resolveRootKType(obj)

    val rawNode =
        descriptorToSchema(
            descriptor = descriptor,
            jsonElement = jsonElement,
            defs = defs,
            visited = visited,
            refModelNamePrefix = refModelNamePrefix,
            kType = rootKType,
        )

    // Apply any pending collision renames recorded mid-walk: a `$ref` to a key that
    // was renamed during a later collision must now point at the new key. Without
    // this sweep, refs emitted before the collision would be dangling.
    val node = applyCollisionRenames(rawNode, defs)

    // Enums keep their own name even under an override. http4k renders an enum query or path
    // parameter by calling `toSchema(enumConstants[0], meta.name, null)` - the *parameter* name
    // becomes the override. Honouring it would key the enum's definition by the parameter
    // (`status`) rather than the type (`TaskStatusDto`), and since the same enum is normally also
    // reached through a request or response body, the document would carry two identical
    // components under different names. An enum always resolves to a named definition from its
    // serial name, so there is nothing an override can usefully add.
    if (overrideDefinitionId != null && descriptor.kind != SerialKind.ENUM) {
      return applyOverrideDefinitionId(node, defs, overrideDefinitionId, refModelNamePrefix)
    }

    return JsonSchema(node, defs.schemas)
  }

  private fun applyCollisionRenames(node: NODE, defs: DefinitionAccumulator<NODE>): NODE {
    if (defs.collisionRenames.isEmpty()) return node
    val pathRenames =
        defs.collisionRenames.entries.associate { (oldKey, newKey) ->
          "#/$refLocationPrefix/$oldKey" to "#/$refLocationPrefix/$newKey"
        }
    val rewrittenDefs = defs.schemas.mapValues { (_, v) -> rewriteRefPaths(v, pathRenames) }
    defs.schemas.clear()
    defs.schemas.putAll(rewrittenDefs)
    defs.collisionRenames.clear()
    return rewriteRefPaths(node, pathRenames)
  }

  /**
   * Recursively walks [node] and rewrites every string whose content exactly matches one of the old
   * paths in [pathRenames] to the corresponding new path. Covers both `$ref` values and OpenAPI
   * `discriminator.mapping` values (which are paths under arbitrary keys, not under `$ref`).
   * Exact-match-only, so an unrelated description string can't be rewritten by accident.
   */
  private fun rewriteRefPaths(node: NODE, pathRenames: Map<String, String>): NODE =
      when (json.typeOf(node)) {
        JsonType.Object ->
            json.obj(json.fields(node).map { (k, v) -> k to rewriteRefPaths(v, pathRenames) })
        JsonType.Array -> json.array(json.elements(node).map { rewriteRefPaths(it, pathRenames) })
        JsonType.String -> {
          val text = json.text(node)
          if (pathRenames.containsKey(text)) json.string(pathRenames.getValue(text)) else node
        }
        else -> node
      }

  private fun applyOverrideDefinitionId(
      node: NODE,
      defs: DefinitionAccumulator<NODE>,
      overrideDefinitionId: String,
      refModelNamePrefix: String?,
  ): JsonSchema<NODE> {
    val refField = json.fields(node).firstOrNull { (k, _) -> k == "\$ref" }
    if (refField == null) {
      // Inline schema (primitives, arrays, maps) — no definition to rename.
      return JsonSchema(node, defs.schemas)
    }

    val originalRefPath = json.text(refField.second)
    val originalDefKey = originalRefPath.removePrefix("#/$refLocationPrefix/")
    val newDefKey = (refModelNamePrefix ?: "") + overrideDefinitionId

    if (originalDefKey == newDefKey) {
      return JsonSchema(node, defs.schemas)
    }

    val newRefPath = "#/$refLocationPrefix/$newDefKey"
    val pathRenames = mapOf(originalRefPath to newRefPath)

    // Move the renamed definition under the new key, then rewrite every inner $ref
    // so recursive self-references and cross-definition refs stay consistent.
    val moved = defs.schemas.remove(originalDefKey)
    defs.serialNames.remove(originalDefKey)
    val rewrittenDefs = defs.schemas.mapValues { (_, v) -> rewriteRefPaths(v, pathRenames) }
    defs.schemas.clear()
    defs.schemas.putAll(rewrittenDefs)
    if (moved != null) {
      defs.schemas[newDefKey] = rewriteRefPaths(moved, pathRenames)
    }

    return JsonSchema(json.obj("\$ref" to json.string(newRefPath)), defs.schemas)
  }

  /**
   * Resolves the [KType] for the root example object, to seed the walk's type threading.
   *
   * `obj::class.starProjectedType` alone is not enough for a top-level collection or map: the
   * runtime class of `listOf(dto)` star-projects to `List<*>`, whose single argument has a `null`
   * type, so [listToSchema] has nothing to thread to its elements. Everything downstream that needs
   * the Kotlin declaration rather than the descriptor — property annotations such as [Deprecated],
   * inline value class inner types — then degrades.
   *
   * Element types are recovered the same way [resolveSerializerAndEncode] recovers element
   * serializers: from the runtime class of the first entry. The container classifier is synthetic
   * ([List] / [Map]); only the type arguments are read downstream.
   */
  private fun resolveRootKType(obj: Any): KType? =
      try {
        when (obj) {
          is Collection<*> ->
              obj.firstOrNull()?.let { element ->
                List::class.createType(
                    listOf(KTypeProjection.invariant(element::class.starProjectedType))
                )
              }
          is Map<*, *> ->
              obj.entries.firstOrNull()?.let { (key, value) ->
                if (key == null || value == null) {
                  null
                } else {
                  Map::class.createType(
                      listOf(
                          KTypeProjection.invariant(key::class.starProjectedType),
                          KTypeProjection.invariant(value::class.starProjectedType),
                      )
                  )
                }
              }
          else -> obj::class.starProjectedType
        }
      } catch (_: Exception) {
        null
      }

  @Suppress("UNCHECKED_CAST")
  private fun resolveSerializerAndEncode(obj: Any): Pair<KSerializer<Any>, JsonElement> {
    return when (obj) {
      is Collection<*> -> {
        val firstElement =
            obj.firstOrNull()
                ?: throw IllegalArgumentException("Cannot generate schema from empty collection")
        val elementSerializer = kotlinxJson.serializersModule.serializer(firstElement::class.java)
        val listSerializer = ListSerializer(elementSerializer) as KSerializer<Any>
        listSerializer to kotlinxJson.encodeToJsonElement(listSerializer, obj)
      }
      is Map<*, *> -> {
        val firstEntry =
            obj.entries.firstOrNull()
                ?: throw IllegalArgumentException("Cannot generate schema from empty map")
        val keySerializer = kotlinxJson.serializersModule.serializer(firstEntry.key!!::class.java)
        val valueSerializer =
            kotlinxJson.serializersModule.serializer(firstEntry.value!!::class.java)
        val mapSerializer = MapSerializer(keySerializer, valueSerializer) as KSerializer<Any>
        mapSerializer to kotlinxJson.encodeToJsonElement(mapSerializer, obj)
      }
      else -> {
        val serializer =
            kotlinxJson.serializersModule.serializer(obj::class.java) as KSerializer<Any>
        serializer to kotlinxJson.encodeToJsonElement(serializer, obj)
      }
    }
  }

  /**
   * @param kType Optional Kotlin type corresponding to this descriptor, threaded through the
   *   traversal to recover the declared KClass when `descriptor.serialName` is not a loadable class
   *   name (e.g. sealed parents with `@SerialName`). Also used to resolve generic type arguments
   *   for List/Map/Set element types and inline value class inner types.
   */
  private fun descriptorToSchema(
      descriptor: SerialDescriptor,
      jsonElement: JsonElement?,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): NODE {
    // Nullable descriptors have "?" appended to serialName by kotlinx.serialization.
    // Normalize once here so individual handlers don't need to strip it.
    val serialName = descriptor.serialName.removeSuffix("?")

    val baseSchema =
        when (descriptor.kind) {
          is PrimitiveKind ->
              primitiveToSchema(
                  descriptor.kind as PrimitiveKind,
                  serialName,
                  jsonElement,
              )
          SerialKind.ENUM -> enumToSchema(descriptor, serialName, defs, refModelNamePrefix)
          SerialKind.CONTEXTUAL -> {
            val contextualDescriptor =
                kotlinxJson.serializersModule.getContextualDescriptor(descriptor)
                    ?: throw IllegalArgumentException(
                        "Unregistered contextual type: ${descriptor.serialName}"
                    )
            descriptorToSchema(
                contextualDescriptor,
                jsonElement,
                defs,
                visited,
                refModelNamePrefix,
                kType = kType,
            )
          }
          StructureKind.CLASS ->
              if (descriptor.isInline) {
                val innerKType = resolveInlineInnerType(kType)
                descriptorToSchema(
                    descriptor.getElementDescriptor(0),
                    jsonElement,
                    defs,
                    visited,
                    refModelNamePrefix,
                    kType = innerKType,
                )
              } else {
                classToSchema(
                    descriptor,
                    serialName,
                    jsonElement,
                    defs,
                    visited,
                    refModelNamePrefix,
                    kType,
                )
              }
          StructureKind.OBJECT -> json.obj("type" to json.string("object"))
          StructureKind.LIST ->
              listToSchema(descriptor, jsonElement, defs, visited, refModelNamePrefix, kType)
          StructureKind.MAP ->
              mapToSchema(descriptor, jsonElement, defs, visited, refModelNamePrefix, kType)
          is PolymorphicKind.SEALED ->
              sealedToSchema(descriptor, serialName, defs, visited, refModelNamePrefix, kType)
          is PolymorphicKind.OPEN ->
              throw IllegalArgumentException(
                  "PolymorphicKind.OPEN is not supported for JSON Schema generation"
              )
          else -> throw IllegalArgumentException("Unsupported descriptor kind: ${descriptor.kind}")
        }

    return if (descriptor.isNullable) {
      wrapNullable(baseSchema)
    } else {
      baseSchema
    }
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

  private fun enumToSchema(
      descriptor: SerialDescriptor,
      serialName: String,
      defs: DefinitionAccumulator<NODE>,
      refModelNamePrefix: String?,
  ): NODE {
    val shortName = serialName.substringAfterLast('.')
    val defName =
        addDefinition(
            defs = defs,
            serialName = serialName,
            shortName = shortName,
            schema = {
              val elementNames =
                  (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
              withClassDescription(
                  json.obj(
                      "type" to json.string("string"),
                      "enum" to json.array(elementNames.map { json.string(it) }),
                  ),
                  loadKClass(serialName),
              )
            },
            refModelNamePrefix = refModelNamePrefix,
        )

    val refPath = buildRefPath(defName)
    return json.obj("\$ref" to json.string(refPath))
  }

  private fun classToSchema(
      descriptor: SerialDescriptor,
      serialName: String,
      jsonElement: JsonElement?,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): NODE {

    if (visited.contains(serialName)) {
      val shortName = serialName.substringAfterLast('.')
      val refName = resolveDefinitionName(defs, serialName, shortName, refModelNamePrefix)
      val refPath = buildRefPath(refName)
      return json.obj("\$ref" to json.string(refPath))
    }

    visited.add(serialName)

    val jsonObj = jsonElement as? JsonObject
    val (properties, requiredFields) =
        buildObjectProperties(descriptor, jsonObj, defs, visited, refModelNamePrefix, kType)

    val schemaFields = mutableListOf<Pair<String, NODE>>()
    schemaFields.add("type" to json.string("object"))
    schemaFields.add("properties" to json.obj(properties))
    if (requiredFields.isNotEmpty()) {
      schemaFields.add("required" to json.array(requiredFields.map { json.string(it) }))
    }

    val objectSchema =
        withClassDescription(
            json.obj(schemaFields),
            (kType?.classifier as? KClass<*>) ?: loadKClass(serialName),
        )

    val shortName = serialName.substringAfterLast('.')
    val defName =
        addDefinition(
            defs = defs,
            serialName = serialName,
            shortName = shortName,
            schema = { objectSchema },
            refModelNamePrefix = refModelNamePrefix,
        )

    val refPath = buildRefPath(defName)
    return json.obj("\$ref" to json.string(refPath))
  }

  private fun listToSchema(
      descriptor: SerialDescriptor,
      jsonElement: JsonElement?,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): NODE {
    val elementDescriptor = descriptor.getElementDescriptor(0)
    val elementKType = kType?.arguments?.firstOrNull()?.type

    val itemJsonElement = (jsonElement as? JsonArray)?.firstOrNull()
    val itemSchema =
        descriptorToSchema(
            descriptor = elementDescriptor,
            jsonElement = itemJsonElement,
            defs = defs,
            visited = visited,
            refModelNamePrefix = refModelNamePrefix,
            kType = elementKType,
        )

    return json.obj("type" to json.string("array"), "items" to itemSchema)
  }

  private fun mapToSchema(
      descriptor: SerialDescriptor,
      jsonElement: JsonElement?,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): NODE {
    val keyDescriptor = descriptor.getElementDescriptor(0)
    if (keyDescriptor.kind !is PrimitiveKind.STRING) {
      throw IllegalArgumentException(
          "Map keys must be strings for JSON Schema generation. Found: ${keyDescriptor.kind}"
      )
    }

    val valueDescriptor = descriptor.getElementDescriptor(1)
    val valueKType = kType?.arguments?.getOrNull(1)?.type

    val valueJsonElement = (jsonElement as? JsonObject)?.values?.firstOrNull()
    val valueSchema =
        descriptorToSchema(
            descriptor = valueDescriptor,
            jsonElement = valueJsonElement,
            defs = defs,
            visited = visited,
            refModelNamePrefix = refModelNamePrefix,
            kType = valueKType,
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
   * True when [property] carries `@Deprecated`. Checks the getter's annotations as well as the
   * property's own, since the `@get:Deprecated` use-site form lands on the getter — `Deprecated`
   * permits `PROPERTY_GETTER` as a target.
   */
  private fun isDeprecated(property: KProperty1<*, *>?): Boolean =
      property != null &&
          (property.annotations + property.getter.annotations).any { it is Deprecated }

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
   * The description for a property: what the field is, plus what to use instead when it is
   * deprecated.
   *
   * What the field is comes from its own [Description] or, failing that, its declared type's — two
   * answers to the same question, so the more specific one wins. A deprecation message answers a
   * different question, so it is *appended* rather than competing: a property that gained a
   * description would otherwise silently lose its migration hint, which turns writing documentation
   * into deleting documentation.
   *
   * The type-level fallback applies only where [schema] renders the type inlined — a value class,
   * or one whose custom serializer produces a primitive. Those have no definition of their own to
   * carry a shared description, so the use site is the only place one can go. A type that renders
   * as `$ref` does have a definition, and [withClassDescription] puts its description there
   * instead, once, rather than repeating it at every field holding it.
   *
   * Both the property's own annotations and its getter's are read, so the `@get:Description`
   * use-site form works, as it does for [Deprecated].
   */
  private fun descriptionOf(property: KProperty1<*, *>?, schema: NODE): String? {
    if (property == null) return null

    val whatItIs =
        (property.annotations + property.getter.annotations)
            .filterIsInstance<Description>()
            .firstOrNull()
            ?.value
            ?: property.returnType.classifier
                .takeIf { !rendersAsReference(schema) }
                .let { descriptionOn(it as? KClass<*>) }

    // Blank line between them, so a markdown renderer treats the two as separate paragraphs. The
    // message is appended verbatim, with no "Deprecated:" prefix: `"deprecated": true` sits in the
    // same schema object and already says that, and inventing a prefix would put words the author
    // did not write into a document their consumers read.
    return listOfNotNull(whatItIs, deprecationMessageOf(property)).joinToString("\n\n").takeIf {
      it.isNotEmpty()
    }
  }

  /**
   * The message from a property's [Deprecated], when it carries one.
   *
   * A deprecation notice is the one description this library can produce without being asked: the
   * annotation is already read to render `"deprecated": true`, and its message is the very thing a
   * consumer seeing that marker needs — what to use instead. Discarding it would leave the document
   * saying a field is deprecated and nothing more.
   *
   * Appended to whatever description the property already has rather than replacing or being
   * replaced by it — see [descriptionOf].
   *
   * Blank messages are ignored: `@Deprecated("")` and the default carry nothing to say. Returns
   * `null` altogether unless [includeDeprecationMessages] is on.
   */
  private fun deprecationMessageOf(property: KProperty1<*, *>): String? =
      if (!includeDeprecationMessages) null
      else
          (property.annotations + property.getter.annotations)
              .filterIsInstance<Deprecated>()
              .firstOrNull()
              ?.message
              ?.takeIf { it.isNotBlank() }

  /** The [Description] declared on [kClass], if any. */
  private fun descriptionOn(kClass: KClass<*>?): String? =
      kClass?.annotations?.filterIsInstance<Description>()?.firstOrNull()?.value

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
            content.toBigIntegerOrNull()?.let { json.number(it) }
                ?: content.toBigDecimalOrNull()?.let { json.number(it) }
                ?: json.string(content)
          }
        }
      }
      is JsonArray -> json.array(element.map { convertJsonElement(it) })
      is JsonObject -> json.obj(element.entries.map { (k, v) -> k to convertJsonElement(v) })
      is JsonNull -> json.nullNode()
    }
  }

  private fun addDefinition(
      defs: DefinitionAccumulator<NODE>,
      serialName: String,
      shortName: String,
      schema: () -> NODE,
      refModelNamePrefix: String?,
  ): String {
    val existingKey =
        defs.schemas.keys.find { key ->
          val strippedKey = refModelNamePrefix?.let { key.removePrefix(it) } ?: key
          strippedKey == shortName || strippedKey == serialName.replace('.', '_')
        }

    if (existingKey != null) {
      val existingSerialName = defs.serialNames[existingKey]
      if (existingSerialName != serialName) {
        val oldFullName = existingSerialName?.replace('.', '_') ?: existingKey
        val oldPrefixedName = refModelNamePrefix?.let { "$it$oldFullName" } ?: oldFullName
        val existing =
            defs.schemas.remove(existingKey)
                ?: throw IllegalStateException(
                    "Expected definition '$existingKey' not found during collision resolution"
                )
        defs.schemas[oldPrefixedName] = existing
        defs.serialNames.remove(existingKey)
        defs.serialNames[oldPrefixedName] = existingSerialName ?: existingKey
        // Record the rename so any `$ref` emitted earlier (pointing at existingKey)
        // can be rewritten to oldPrefixedName by the post-walk sweep in toSchema.
        if (existingKey != oldPrefixedName) {
          defs.collisionRenames[existingKey] = oldPrefixedName
        }

        val newFullName = serialName.replace('.', '_')
        val newPrefixedName = refModelNamePrefix?.let { "$it$newFullName" } ?: newFullName
        defs.schemas[newPrefixedName] = schema()
        defs.serialNames[newPrefixedName] = serialName
        return newPrefixedName
      } else {
        return existingKey
      }
    } else {
      val defName = refModelNamePrefix?.let { "$it$shortName" } ?: shortName
      defs.schemas[defName] = schema()
      defs.serialNames[defName] = serialName
      return defName
    }
  }

  private fun resolveDefinitionName(
      defs: DefinitionAccumulator<NODE>,
      serialName: String,
      shortName: String,
      refModelNamePrefix: String?,
  ): String {
    return defs.schemas.keys.find { key ->
      val strippedKey = refModelNamePrefix?.let { key.removePrefix(it) } ?: key
      strippedKey == shortName || strippedKey == serialName.replace('.', '_')
    } ?: (refModelNamePrefix?.let { "$it$shortName" } ?: shortName)
  }

  private fun buildRefPath(defName: String): String {
    return "#/$refLocationPrefix/$defName"
  }

  private fun buildObjectProperties(
      descriptor: SerialDescriptor,
      jsonObj: JsonObject?,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
  ): Pair<List<Pair<String, NODE>>, List<String>> {
    val properties = mutableListOf<Pair<String, NODE>>()
    val requiredFields = mutableListOf<String>()
    val ownerKClass = (kType?.classifier as? KClass<*>) ?: loadKClass(descriptor.serialName)

    for (i in 0 until descriptor.elementsCount) {
      val elementName = descriptor.getElementName(i)
      val elementDescriptor = descriptor.getElementDescriptor(i)
      val elementJsonValue = jsonObj?.get(elementName)
      val property = resolveProperty(ownerKClass, elementName)

      val elementSchema =
          descriptorToSchema(
              descriptor = elementDescriptor,
              jsonElement = elementJsonValue,
              defs = defs,
              visited = visited,
              refModelNamePrefix = refModelNamePrefix,
              kType = property?.returnType,
          )

      val describedSchema =
          descriptionOf(property, elementSchema)?.let { withDescription(elementSchema, it) }
              ?: elementSchema

      properties.add(
          elementName to
              if (isDeprecated(property)) withDeprecatedMarker(describedSchema) else describedSchema
      )

      val isOptional = descriptor.isElementOptional(i)
      if (!isOptional) {
        requiredFields.add(elementName)
      }
    }

    return properties to requiredFields
  }

  private fun sealedToSchema(
      descriptor: SerialDescriptor,
      serialName: String,
      defs: DefinitionAccumulator<NODE>,
      visited: MutableSet<String>,
      refModelNamePrefix: String?,
      kType: KType? = null,
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
          buildObjectProperties(
              subclassDescriptor,
              exampleJson,
              defs,
              visited,
              refModelNamePrefix,
              subclassKType,
          )
      properties.addAll(subclassProperties)
      requiredFields.addAll(subclassRequiredFields)

      val schemaFields = mutableListOf<Pair<String, NODE>>()
      schemaFields.add("type" to json.string("object"))
      schemaFields.add("properties" to json.obj(properties))
      if (requiredFields.isNotEmpty()) {
        schemaFields.add("required" to json.array(requiredFields.map { json.string(it) }))
      }

      val subclassSchema = json.obj(schemaFields)

      // Use the subclass's qualified class name (not the @SerialName discriminator value)
      // as the identity passed to addDefinition. The discriminator value can be reused
      // across sealed hierarchies (e.g. two unrelated trees both with a `@SerialName("created")`
      // subclass); FQCN keeps them distinct and lets collision resolution rename when
      // their simple names also collide.
      val subclassIdentity =
          subclassKClasses[discriminatorValue]?.qualifiedName ?: discriminatorValue
      val subclassDefName =
          addDefinition(
              defs = defs,
              serialName = subclassIdentity,
              shortName = shortName,
              schema = { subclassSchema },
              refModelNamePrefix = refModelNamePrefix,
          )

      val subclassRefPath = buildRefPath(subclassDefName)
      oneOfRefs.add(json.obj("\$ref" to json.string(subclassRefPath)))
      discriminatorMapping[discriminatorValue] = subclassRefPath
    }

    val parentQualifiedName = sealedKClass.qualifiedName ?: serialName
    val parentShortName = sealedKClass.simpleName ?: serialName.substringAfterLast('.')
    val parentSchema =
        json.obj(
            "oneOf" to json.array(oneOfRefs),
            "discriminator" to
                json.obj(
                    "propertyName" to json.string(classDiscriminator),
                    "mapping" to
                        json.obj(discriminatorMapping.map { (k, v) -> k to json.string(v) }),
                ),
        )

    val parentDefName =
        addDefinition(
            defs = defs,
            serialName = parentQualifiedName,
            shortName = parentShortName,
            schema = { parentSchema },
            refModelNamePrefix = refModelNamePrefix,
        )

    val refPath = buildRefPath(parentDefName)
    return json.obj("\$ref" to json.string(refPath))
  }

  /** Recursively collects all concrete (non-sealed) subclasses from a sealed hierarchy. */
  private fun collectLeafSubclasses(klass: KClass<*>): List<KClass<*>> =
      klass.sealedSubclasses.flatMap { sub ->
        if (sub.isSealed) collectLeafSubclasses(sub) else listOf(sub)
      }

  /**
   * Resolves the Kotlin property backing a serialized element name. Matches both the Kotlin
   * property name and any `@SerialName` annotation on the property, since the serialized name may
   * differ from the Kotlin name.
   *
   * The property carries two things the [SerialDescriptor] cannot: its [KType], used to thread
   * generic type arguments through the walk, and its annotations, used to detect [Deprecated].
   */
  private fun resolveProperty(ownerKClass: KClass<*>?, elementName: String): KProperty1<*, *>? =
      ownerKClass?.memberProperties?.find { prop ->
        prop.name == elementName ||
            prop.annotations.filterIsInstance<kotlinx.serialization.SerialName>().any {
              it.value == elementName
            }
      }

  /**
   * Loads the [KClass] named by a descriptor's `serialName`.
   *
   * Last resort when no [KType] reached this point in the walk. [resolveRootKType] covers the
   * collection-passed-straight-to-[toSchema] case and properties carry their own [KType], so what
   * remains is a property whose declared type is a generic type parameter: the classifier is a
   * `KTypeParameter` rather than a [KClass], and the concrete argument was erased upstream.
   *
   * Returns `null` when the name is not a loadable class, in which case the caller degrades to
   * descriptor-only information — as it did everywhere before this fallback existed. Any
   * class-level `@SerialName` falls here.
   *
   * **Known limitation.** A `@SerialName` that *is* a loadable class name resolves — to that class,
   * not to the one being rendered — so its property annotations are read instead. Nothing available
   * here distinguishes the two: the alias is, by construction, the other class's serial name. This
   * requires one `@Serializable` type to alias another's fully-qualified name, and such a pair
   * already collides in [addDefinition], which keys definitions by serial name too. See
   * `KotlinxSerializationJsonSchemaCreatorTest`.
   */
  private fun loadKClass(serialName: String): KClass<*>? =
      try {
        // initialize = false: this is a best-effort metadata lookup, and running a consumer's
        // static initialisers as a side effect of rendering documentation is not worth the risk.
        Class.forName(serialName.removeSuffix("?"), false, javaClass.classLoader).kotlin
      } catch (_: ClassNotFoundException) {
        null
      } catch (_: LinkageError) {
        // A half-resolvable class is still just a failed lookup here — degrade to
        // descriptor-only information rather than failing the whole document.
        null
      }

  /** Resolves the inner type of an inline value class. */
  private fun resolveInlineInnerType(kType: KType?): KType? {
    val kClass = kType?.classifier as? KClass<*> ?: return null
    return kClass.memberProperties.firstOrNull()?.returnType
  }
}
