package no.liflig.http4k.kotlinx.jsonschema

import kotlinx.serialization.json.Json as KotlinxJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.http4k.format.KotlinxSerialization

// Schema creators and readers shared by the jsonschema tests.

internal val kotlinxJson = KotlinxJson { ignoreUnknownKeys = true }

internal val schemaCreator =
    KotlinxSerializationJsonSchemaCreator<JsonElement>(
        json = KotlinxSerialization,
        kotlinxJson = kotlinxJson,
    )

internal val schemaCreatorWithFormats =
    KotlinxSerializationJsonSchemaCreator<JsonElement>(
        json = KotlinxSerialization,
        kotlinxJson = kotlinxJson,
        formatMappings = KotlinxSerializationJsonSchemaCreator.COMMON_FORMAT_MAPPINGS,
    )

internal val kotlinxJsonEncodeDefaults = KotlinxJson {
  ignoreUnknownKeys = true
  encodeDefaults = true
}

internal val schemaCreatorEncodeDefaults =
    KotlinxSerializationJsonSchemaCreator<JsonElement>(
        json = KotlinxSerialization,
        kotlinxJson = kotlinxJsonEncodeDefaults,
    )

internal val prettyJson = KotlinxJson { prettyPrint = true }

internal fun prettyPrint(schema: org.http4k.contract.jsonschema.JsonSchema<JsonElement>): String {
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

/** Reads `properties.legacy.deprecated` out of a rendered definition. */
internal fun deprecatedFlagOfLegacy(definition: JsonElement?): Boolean? {
  val properties = (definition as JsonObject)["properties"] as JsonObject
  val legacy = properties["legacy"] as JsonObject
  return (legacy["deprecated"] as? JsonPrimitive)?.content?.toBoolean()
}

/** Reads a property's `description` out of a rendered definition. */
internal fun descriptionOf(definition: JsonElement?, property: String): String? {
  val properties = (definition as JsonObject)["properties"] as JsonObject
  return ((properties[property] as JsonObject)["description"] as? JsonPrimitive)?.content
}
