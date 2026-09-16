package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType
import kotlin.reflect.full.starProjectedType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
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
    val node = walk.schemaFor(descriptor, jsonElement, resolveRootKType(obj))

    // Enums keep their own name even under an override. http4k renders an enum query or path
    // parameter by calling `toSchema(enumConstants[0], meta.name, null)` - the *parameter* name
    // becomes the override. Honouring it would key the enum's definition by the parameter
    // (`status`) rather than the type (`TaskStatusDto`), and since the same enum is normally also
    // reached through a request or response body, the document would carry two identical
    // components under different names. An enum always resolves to a named definition from its
    // serial name, so there is nothing an override can usefully add.
    val effectiveOverride = overrideDefinitionId.takeIf { descriptor.kind != SerialKind.ENUM }
    return registry.finish(node, effectiveOverride)
  }

  /**
   * Resolves the [KType] for the root example object, to seed the walk's type threading.
   *
   * `obj::class.starProjectedType` alone is not enough for a top-level collection or map: the
   * runtime class of `listOf(dto)` star-projects to `List<*>`, whose single argument has a `null`
   * type, so the list handler has nothing to thread to its elements. Everything downstream that
   * needs the Kotlin declaration rather than the descriptor — property annotations such as
   * [Deprecated], inline value class inner types — then degrades.
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
}
