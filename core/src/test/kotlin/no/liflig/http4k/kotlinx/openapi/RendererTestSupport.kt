package no.liflig.http4k.kotlinx.openapi

import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json as KotlinxJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import no.liflig.http4k.kotlinx.jsonschema.KotlinxSerializationJsonSchemaCreator
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.ApiServer
import org.http4k.core.Method.GET
import org.http4k.core.Request
import org.http4k.core.Uri
import org.http4k.format.KotlinxSerialization

// DTOs, the contract under test, and document readers shared by the openapi tests.

@Serializable
data class CreateRequest(
    val name: String,
    val value: Int,
)

@Serializable
data class CreateResponse(
    val id: String,
    val created: Boolean,
)

@Serializable
data class NullableFieldDto(
    val required: String,
    val optional: String? = null,
    val optionalEvent: EventPayload? = null,
)

@Serializable
sealed class EventPayload {
  @Serializable @SerialName("created") data class Created(val name: String) : EventPayload()

  @Serializable @SerialName("deleted") data object Deleted : EventPayload()

  companion object {
    val example: EventPayload = Created("test-item")
  }
}

@Serializable
data class EventResponse(
    val event: EventPayload,
)

@no.liflig.http4k.kotlinx.jsonschema.Description("A code, as the traffic systems write it.")
@Serializable
@JvmInline
value class DescribedCodeDto(val value: String)

@Serializable
data class DescribedPropertyDto(
    @no.liflig.http4k.kotlinx.jsonschema.Description("What this field is for.")
    val described: String,
    val undescribed: String,
    val fromType: DescribedCodeDto,
    @no.liflig.http4k.kotlinx.jsonschema.Description("Described alongside a reference.")
    val describedEvent: EventPayload,
)

@Suppress("DEPRECATION")
@Serializable
data class DeprecatedPropertyDto(
    @Deprecated("Use replacement instead") val legacy: String,
    val replacement: String,
    @Deprecated("Use replacement instead") val legacyEvent: EventPayload,
)

@Suppress("DEPRECATION")
@Serializable
@SerialName("renamed_response")
data class RenamedResponseDto(
    val id: String,
    @Deprecated("Use code instead") val legacyCode: String,
    val code: String,
)

enum class StatusFilter {
  ACTIVE,
  INACTIVE,
  ALL,
}

/**
 * Constants with bodies compile to named subclasses of their enum, which kotlinx.serialization has
 * no serializer for. Exercises the creator's resolution of the declaring enum class.
 */
enum class Priority {
  HIGH {
    override fun weight() = 2
  },
  LOW {
    override fun weight() = 1
  };

  abstract fun weight(): Int
}

@Serializable
enum class TaskStatus {
  OPEN,
  DONE,
}

@Serializable
data class TaskDto(
    val id: String,
    val status: TaskStatus,
)

/**
 * Nullable, but without Kotlin defaults, so both fields are `required` and an example that omits
 * them would not validate against the schema.
 */
@Serializable
data class OverridesDto(
    val overriddenName: String?,
    val overriddenCount: Int?,
)

/** Carries a property whose name collides with the `example` key of a media-type object. */
@Serializable
data class ExampleNamedFieldDto(
    val example: String?,
    val label: String,
)

internal val json = KotlinxSerialization

internal val kotlinxJson = KotlinxJson { ignoreUnknownKeys = true }

internal val schema =
    KotlinxSerializationJsonSchemaCreator<JsonElement>(
        json = json,
        kotlinxJson = kotlinxJson,
    )

internal fun buildContract(block: org.http4k.contract.ContractBuilder.() -> Unit) = contract {
  this.renderer =
      openApi3WithKotlinx(
          apiInfo = ApiInfo("Test API", "1.0.0"),
          json = json,
          schema = schema,
          servers = listOf(ApiServer(Uri.of("http://localhost:8080"))),
      )
  block()
}

internal fun fetchSpec(app: org.http4k.core.HttpHandler): kotlinx.serialization.json.JsonObject {
  val response = app(Request(GET, "/"))
  response.status.code shouldBe 200
  return KotlinxJson.parseToJsonElement(response.bodyString()).jsonObject
}

/** Matches the body-example positions, the only ones allowed to hold a null. */
internal val exampleBodyPath = Regex("""\.content\.[^.]+\.example(\.|$)""")

/** Dot-joined path of every JSON null in [element], array indices rendered as `[n]`. */
internal fun nullPaths(element: JsonElement, path: String = ""): List<String> =
    when (element) {
      is JsonNull -> listOf(path)
      is JsonObject -> element.entries.flatMap { (key, value) -> nullPaths(value, "$path.$key") }
      is JsonArray -> element.flatMapIndexed { index, value -> nullPaths(value, "$path[$index]") }
      else -> emptyList()
    }

internal fun requestExample(spec: JsonObject, path: String, method: String): JsonElement? =
    spec["paths"]
        ?.jsonObject
        ?.get(path)
        ?.jsonObject
        ?.get(method)
        ?.jsonObject
        ?.get("requestBody")
        ?.jsonObject
        ?.get("content")
        ?.jsonObject
        ?.get("application/json")
        ?.jsonObject
        ?.get("example")

internal fun responseExample(spec: JsonObject, path: String, method: String): JsonElement? =
    spec["paths"]
        ?.jsonObject
        ?.get(path)
        ?.jsonObject
        ?.get(method)
        ?.jsonObject
        ?.get("responses")
        ?.jsonObject
        ?.get("200")
        ?.jsonObject
        ?.get("content")
        ?.jsonObject
        ?.get("application/json")
        ?.jsonObject
        ?.get("example")
