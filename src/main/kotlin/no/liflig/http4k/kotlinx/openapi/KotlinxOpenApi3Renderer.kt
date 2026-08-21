package no.liflig.http4k.kotlinx.openapi

import no.liflig.http4k.kotlinx.jsonschema.KotlinxSerializationJsonSchemaCreator
import org.http4k.contract.jsonschema.JsonSchema
import org.http4k.contract.jsonschema.v3.JsonToJsonSchema
import org.http4k.contract.openapi.ApiRenderer
import org.http4k.contract.openapi.v3.Api
import org.http4k.contract.openapi.v3.OpenApi3ApiRenderer
import org.http4k.format.Json
import org.http4k.format.JsonType

/**
 * [ApiRenderer] that combines http4k's manual JSON rendering for the OpenAPI document structure
 * with kotlinx.serialization-based JSON Schema generation for DTO models.
 *
 * The [toSchema] fallback chain mirrors [ApiRenderer.Auto]:
 * 1. Try [JsonToJsonSchema] for values that are already [NODE] (raw JSON bodies)
 * 2. Fall back to [KotlinxSerializationJsonSchemaCreator] for `@Serializable` DTOs
 * 3. Fall back to reflection-based enum schema for Java Enum constants
 *
 * Use this with [org.http4k.contract.openapi.v3.OpenApi3]'s primary constructor to render OpenAPI
 * documents without Jackson:
 * ```kotlin
 * val renderer = KotlinxOpenApi3Renderer(json = KotlinxSerialization, schema = schema)
 * OpenApi3(apiInfo = apiInfo, json = KotlinxSerialization, apiRenderer = renderer)
 * ```
 *
 * Passing `apiRenderer` as a named parameter forces Kotlin to use the `OpenApi3` primary
 * constructor (which takes `Json<NODE>`), because the secondary constructor (which takes
 * `AutoMarshallingJson<NODE>` and uses `ApiRenderer.Auto`) does not have an `apiRenderer`
 * parameter.
 */
class KotlinxOpenApi3Renderer<NODE : Any>(
    private val json: Json<NODE>,
    private val schema: KotlinxSerializationJsonSchemaCreator<NODE>,
    private val refLocationPrefix: String = "components/schemas",
) : ApiRenderer<Api<NODE>, NODE> {

  private val delegate = OpenApi3ApiRenderer(json, refLocationPrefix)
  private val jsonToJsonSchema = JsonToJsonSchema(json, refLocationPrefix)

  private companion object {
    /** Operation member holding the request body. */
    const val REQUEST_BODY_KEY = "requestBody"
    /** Operation member holding the responses, keyed by status code. */
    const val RESPONSES_KEY = "responses"
    /** Media-type map of a request body or a single response. */
    const val CONTENT_KEY = "content"
    /** Single example payload of a media-type object. */
    const val EXAMPLE_KEY = "example"
  }

  override fun api(api: Api<NODE>): NODE = stripNullValues(delegate.api(api), emptyList())

  override fun toSchema(
      obj: Any,
      overrideDefinitionId: String?,
      refModelNamePrefix: String?,
  ): JsonSchema<NODE> {
    // 1. Try JsonToJsonSchema for values that are already NODE (raw JSON bodies).
    try {
      @Suppress("UNCHECKED_CAST")
      return jsonToJsonSchema.toSchema(obj as NODE, overrideDefinitionId, refModelNamePrefix)
    } catch (_: ClassCastException) {
      // Not a NODE — fall through to kotlinx.serialization path.
    }

    // 2. Try KotlinxSerializationJsonSchemaCreator for @Serializable DTOs.
    val result = schema.toSchema(obj, overrideDefinitionId, refModelNamePrefix)

    // 3. If the schema creator returned an empty schema and the object is a Java Enum,
    // fall back to reflection-based enum schema generation.
    // This handles the case where OpenApi3 passes Java Enum constants for query/path
    // parameter schemas (e.g. paramMeta.clz.java.enumConstants[0]).
    if (isEmptySchema(result) && obj is Enum<*>) {
      return toEnumSchema(obj, refModelNamePrefix, overrideDefinitionId)
    }

    return result
  }

  private fun isEmptySchema(schema: JsonSchema<NODE>): Boolean =
      schema.definitions.isEmpty() && json.fields(schema.node).none()

  /**
   * Recursively strips null values from JSON objects. http4k's [OpenApi3ApiRenderer] emits
   * `"description": null` for unset fields, which is invalid in the OpenAPI spec.
   *
   * Example payloads (see [isExamplePayload]) are left untouched. Those subtrees are *data* rather
   * than document scaffolding, and a `null` in one is what the endpoint actually serializes for a
   * nullable field. Stripping it produced an example that omitted properties its own schema listed
   * as `required`, and that under-reported the response body.
   *
   * [path] is the chain of object keys leading to [node], so the exemption can be decided by
   * position in the document. Arrays are transparent: their elements share the path of the array
   * itself.
   */
  private fun stripNullValues(node: NODE, path: List<String>): NODE =
      when (json.typeOf(node)) {
        JsonType.Object ->
            json.obj(
                json.fields(node).mapNotNull { (key, value) ->
                  when {
                    json.typeOf(value) == JsonType.Null -> null
                    isExamplePayload(path + key) -> key to value
                    else -> key to stripNullValues(value, path + key)
                  }
                },
            )
        JsonType.Array -> json.array(json.elements(node).map { stripNullValues(it, path) })
        else -> node
      }

  /**
   * True for the two document positions that hold an example payload, both written by http4k's
   * `SchemaContent`:
   * ```
   * …requestBody.content.<media-type>.example
   * …responses.<status>.content.<media-type>.example
   * ```
   *
   * Anchoring on the enclosing `requestBody` / `responses` member rather than on the key name keeps
   * the exemption off scaffolding that reuses the name: a `@Serializable` property called `example`
   * is document structure and must stay null-free, including under a definition named `content`,
   * which a trailing `content.*.example` match would let through.
   *
   * The check fails closed — were http4k to emit body examples elsewhere, their nulls would be
   * stripped again, reopening the bug this exemption exists for. Both positions are pinned by
   * tests, so a version bump that moves them fails loudly.
   *
   * The sibling `examples` member (a map of Example Objects) is not exempt: http4k 6.57.1.0 never
   * emits it, and its `summary` / `description` members are scaffolding. Should http4k start
   * emitting it, only each entry's `value` is a payload.
   */
  private fun isExamplePayload(path: List<String>): Boolean =
      path.endsWithSegments(REQUEST_BODY_KEY, CONTENT_KEY, null, EXAMPLE_KEY) ||
          path.endsWithSegments(RESPONSES_KEY, null, CONTENT_KEY, null, EXAMPLE_KEY)

  /**
   * True when the trailing segments of the receiver match [pattern], where a `null` in [pattern]
   * stands for any single segment.
   */
  private fun List<String>.endsWithSegments(vararg pattern: String?): Boolean =
      size >= pattern.size &&
          pattern.withIndex().all { (index, segment) ->
            segment == null || this[size - pattern.size + index] == segment
          }

  private fun toEnumSchema(
      obj: Enum<*>,
      refModelNamePrefix: String?,
      overrideDefinitionId: String?,
  ): JsonSchema<NODE> {
    // `javaClass` is a synthetic subclass for constants that declare a body - `isEnum` is false
    // and `enumConstants` is null on it - so step up to the declaring class in that case.
    val enumClass: Class<*> = obj.javaClass.let { if (it.isEnum) it else it.superclass }
    val constants = enumClass.enumConstants.orEmpty().filterIsInstance<Enum<*>>()
    val newDefinition =
        json.obj(
            "example" to json.string(obj.name),
            "type" to json.string("string"),
            "enum" to json.array(constants.map { json.string(it.name) }),
        )
    // Named after the type, not after [overrideDefinitionId]. See the enum note in
    // [KotlinxSerializationJsonSchemaCreator.toSchema]: http4k passes a parameter name as the
    // override, which would key this definition by the parameter rather than the enum.
    val definitionId =
        (refModelNamePrefix.orEmpty()) +
            (enumClass.simpleName.ifEmpty { null }
                ?: overrideDefinitionId
                ?: ("object" + newDefinition.hashCode()))
    return JsonSchema(
        json { obj("\$ref" to string("#/$refLocationPrefix/$definitionId")) },
        mapOf(definitionId to newDefinition),
    )
  }
}
