package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection.Companion.invariant
import kotlin.reflect.full.createType
import kotlin.reflect.full.starProjectedType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import org.http4k.contract.jsonschema.JsonSchema
import org.http4k.contract.jsonschema.JsonSchemaCreator
import org.http4k.format.AutoMarshallingJson

/**
 * [JsonSchemaCreator] that derives JSON Schema from kotlinx.serialization's [SerialDescriptor] tree
 * of an example object, so `@SerialName`, nullability and sealed polymorphism come from the
 * serializer rather than reflection. Each call runs one [SchemaWalk] over a fresh
 * [DefinitionRegistry].
 */
@OptIn(ExperimentalSerializationApi::class)
class KotlinxSerializationJsonSchemaCreator<NODE : Any>(
    private val json: AutoMarshallingJson<NODE>,
    private val kotlinxJson: kotlinx.serialization.json.Json,
    private val refLocationPrefix: String = "components/schemas",
    private val sealedClassExampleProvider: SealedClassExampleProvider =
        DefaultSealedClassExampleProvider(),
    private val formatMappings: Map<String, String> = emptyMap(),
    private val nullableStrategy: NullableStrategy = NullableStrategy.TYPE_ARRAY,
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

  override fun toSchema(
      obj: Any,
      overrideDefinitionId: String?,
      refModelNamePrefix: String?,
  ): JsonSchema<NODE> {
    val root =
        try {
          rootExample(obj)
        } catch (e: kotlinx.serialization.SerializationException) {
          // http4k passes `object {}` as a sentinel value in exampleSchemaIsValid.
          // Only swallow that case; real serializer-registration failures must propagate
          // so missing or broken `@Serializable` DTOs surface at OpenAPI-build time.
          if (obj::class.java.isAnonymousClass) {
            return JsonSchema(json.obj(), emptyMap())
          }
          throw e
        }

    val registry = DefinitionRegistry(json, refLocationPrefix, refModelNamePrefix)
    val walk =
        SchemaWalk(
            json,
            kotlinxJson,
            registry,
            sealedClassExampleProvider,
            formatMappings,
            nullableStrategy,
        )
    val node = walk.schemaFor(root.descriptor, root.jsonElement, root.kType)

    // Enums keep their own name even under an override. http4k renders an enum query or path
    // parameter by calling `toSchema(enumConstants[0], meta.name, null)` - the *parameter* name
    // becomes the override. Honouring it would key the enum's definition by the parameter
    // (`status`) rather than the type (`TaskStatusDto`), and since the same enum is normally also
    // reached through a request or response body, the document would carry two identical
    // components under different names. An enum always resolves to a named definition from its
    // serial name, so there is nothing an override can usefully add.
    val effectiveOverride = overrideDefinitionId.takeIf { root.descriptor.kind != SerialKind.ENUM }
    return registry.finish(node, effectiveOverride)
  }

  /** What the walk needs from the root example: its descriptor, its encoded JSON, and its type. */
  private class RootExample(
      val descriptor: SerialDescriptor,
      val jsonElement: JsonElement,
      val kType: KType?,
  )

  /**
   * Generic type information is erased at the entry point, so a top-level collection or map is
   * typed from the runtime class of its first entry — for the serializer and for the [KType] alike.
   * `obj::class.starProjectedType` would give `List<*>`, whose argument has a `null` type, and the
   * walk would have nothing to thread to the elements: property annotations and inline value class
   * inner types would silently degrade. The container classifier is synthetic; only the type
   * arguments are read downstream.
   */
  private fun rootExample(obj: Any): RootExample =
      when (obj) {
        is Collection<*> -> {
          val first =
              obj.firstOrNull()
                  ?: throw IllegalArgumentException("Cannot generate schema from empty collection")
          val serializer = ListSerializer(serializerFor(first))
          RootExample(
              serializer.descriptor,
              kotlinxJson.encodeToJsonElement(
                  serializer,
                  obj.map {
                    requireNotNull(it) {
                      "Cannot generate schema from a collection example with a null element"
                    }
                  },
              ),
              List::class.createType(listOf(invariant(first::class.starProjectedType))),
          )
        }
        is Map<*, *> -> {
          val (key, value) =
              obj.entries.firstOrNull()
                  ?: throw IllegalArgumentException("Cannot generate schema from empty map")
          requireNotNull(key) { "Cannot generate schema from a map example with a null key" }
          requireNotNull(value) { "Cannot generate schema from a map example with a null value" }
          val serializer = MapSerializer(serializerFor(key), serializerFor(value))
          val entries =
              obj.entries.associate { (k, v) ->
                requireNotNull(k) { "Cannot generate schema from a map example with a null key" } to
                    requireNotNull(v) {
                      "Cannot generate schema from a map example with a null value"
                    }
              }
          RootExample(
              serializer.descriptor,
              kotlinxJson.encodeToJsonElement(serializer, entries),
              Map::class.createType(
                  listOf(
                      invariant(key::class.starProjectedType),
                      invariant(value::class.starProjectedType),
                  )
              ),
          )
        }
        else -> {
          val serializer = serializerFor(obj)
          RootExample(
              serializer.descriptor,
              kotlinxJson.encodeToJsonElement(serializer, obj),
              obj::class.starProjectedType,
          )
        }
      }

  private fun serializerFor(obj: Any) = kotlinxJson.serializersModule.serializer(obj::class.java)
}
