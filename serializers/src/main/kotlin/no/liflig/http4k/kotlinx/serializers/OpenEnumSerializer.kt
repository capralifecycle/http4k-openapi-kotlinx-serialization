package no.liflig.http4k.kotlinx.serializers

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Serializer for an enum whose set of values the producer may extend: a value this version does not
 * know decodes to [fallback] instead of failing.
 *
 * Without it, adding an entry on the producer side is a breaking change: every consumer still on
 * the previous version fails to decode the whole message, even when it never reads the field.
 *
 * ```kotlin
 * @Serializable(with = IdentityType.Serializer::class)
 * enum class IdentityType {
 *   Email,
 *   @SerialName("phone") PhoneNumber,
 *   Unsupported;
 *
 *   object Serializer : KSerializer<IdentityType> by OpenEnumSerializer(fallback = Unsupported)
 * }
 * ```
 *
 * Names follow the plugin-generated enum serializer: `@SerialName` on an entry renames it, and on
 * the enum class renames the type. The OpenAPI schema generator renders these enums as open (known
 * values listed, any string accepted) and leaves [fallback] out of the known values; see
 * [openEnumFallback].
 *
 * Decoding:
 * - A known name decodes to its entry, honouring `Json { decodeEnumsCaseInsensitive = true }`.
 * - Any other string decodes to [fallback].
 * - A JSON value that is not a string (number, object, array) throws [SerializationException]: the
 *   tolerance is for new *values*, not for a payload that breaks the contract. Under `isLenient`,
 *   unquoted literals count as strings, as they do for plugin-generated enums.
 * - Formats other than JSON decode through [Decoder.decodeEnum], and any failure there falls back.
 *
 * Encoding writes the entry's serial name, [fallback] included. A value decoded as [fallback] and
 * encoded again therefore comes out as the fallback's name, not the value originally received. When
 * the original value has to survive such a round trip, model the field as a type that keeps it
 * instead, such as a sealed `Known(entry)` / `Unknown(raw: String)`.
 *
 * The descriptor is built with [buildSerialDescriptor], which is `@InternalSerializationApi`:
 * kotlinx.serialization has no public builder for an enum descriptor, and its own enum serializer
 * builds one the same way.
 */
@OptIn(InternalSerializationApi::class, ExperimentalSerializationApi::class)
class OpenEnumSerializer<T : Enum<T>>(
    enumClass: Class<T>,
    private val fallback: T,
    serialName: String = defaultSerialName(enumClass),
) : KSerializer<T> {

  private val entries: List<T> = enumClass.enumConstants.toList()
  private val entrySerialNames: List<String> = entries.map { serialNameOf(enumClass, it) }
  // Serial names are unique: the serialization compiler plugin rejects duplicate `@SerialName`s
  // on enum entries, and the descriptor builder rejects duplicate element names.
  private val entryBySerialName: Map<String, T> = entrySerialNames.zip(entries).toMap()

  /** Lowercased serial name to its entries. More than one entry means the name is ambiguous. */
  private val entriesByLowercaseName: Map<String, List<T>> =
      entrySerialNames.zip(entries).groupBy({ it.first.lowercase() }, { it.second })

  override val descriptor: SerialDescriptor =
      buildSerialDescriptor(serialName, SerialKind.ENUM) {
        annotations = listOf(OpenEnum(entrySerialNames[fallback.ordinal]))
        entrySerialNames.forEach { name ->
          element(name, buildSerialDescriptor("$serialName.$name", StructureKind.OBJECT))
        }
      }

  override fun serialize(encoder: Encoder, value: T) {
    encoder.encodeEnum(descriptor, value.ordinal)
  }

  override fun deserialize(decoder: Decoder): T =
      if (decoder is JsonDecoder) deserializeJson(decoder) else deserializeOther(decoder)

  /**
   * Reads the whole value as a tree first, so that a value of the wrong type is consumed cleanly
   * and reported, rather than aborting half-way through the token.
   */
  private fun deserializeJson(decoder: JsonDecoder): T {
    val configuration = decoder.json.configuration
    val element = decoder.decodeJsonElement()
    val name =
        (element as? JsonPrimitive)
            ?.takeIf { it !is JsonNull && (it.isString || configuration.isLenient) }
            ?.content
            ?: throw SerializationException(
                "Expected a string for enum ${descriptor.serialName}, but found $element"
            )
    return entryBySerialName[name]
        ?: if (configuration.decodeEnumsCaseInsensitive) caseInsensitiveEntry(name) else fallback
  }

  private fun caseInsensitiveEntry(name: String): T {
    val candidates = entriesByLowercaseName[name.lowercase()] ?: return fallback
    return candidates.singleOrNull()
        ?: throw SerializationException(
            "'$name' matches more than one entry of enum ${descriptor.serialName} when compared " +
                "case-insensitively: $candidates"
        )
  }

  private fun deserializeOther(decoder: Decoder): T {
    val index =
        try {
          decoder.decodeEnum(descriptor)
        } catch (_: SerializationException) {
          return fallback
        }
    return entries.getOrNull(index) ?: fallback
  }

  override fun toString(): String = "OpenEnumSerializer<${descriptor.serialName}>"

  companion object {
    /** The serializer for the enum of [fallback], named as the plugin would name it. */
    inline operator fun <reified T : Enum<T>> invoke(fallback: T): OpenEnumSerializer<T> =
        OpenEnumSerializer(T::class.java, fallback)

    /**
     * The serial name the plugin-generated serializer would give [enumClass]: its `@SerialName`,
     * else its qualified name (dotted for a nested enum, like `Outer.Inner`).
     */
    fun defaultSerialName(enumClass: Class<*>): String =
        enumClass.getAnnotation(SerialName::class.java)?.value ?: enumClass.canonicalName

    private fun <T : Enum<T>> serialNameOf(enumClass: Class<T>, entry: T): String =
        enumClass.getField(entry.name).getAnnotation(SerialName::class.java)?.value ?: entry.name
  }
}
