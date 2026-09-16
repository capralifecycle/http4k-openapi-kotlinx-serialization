package no.liflig.http4k.kotlinx.openapi

import no.liflig.http4k.kotlinx.jsonschema.KotlinxSerializationJsonSchemaCreator
import no.liflig.http4k.kotlinx.jsonschema.NullableStrategy
import org.http4k.contract.ErrorResponseRenderer
import org.http4k.contract.JsonErrorResponseRenderer
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.ApiRenderer
import org.http4k.contract.openapi.OpenApiExtension
import org.http4k.contract.openapi.OpenApiVersion
import org.http4k.contract.openapi.SecurityRenderer
import org.http4k.contract.openapi.cached
import org.http4k.contract.openapi.v3.Api
import org.http4k.contract.openapi.v3.ApiServer
import org.http4k.contract.openapi.v3.OpenApi3
import org.http4k.contract.openapi.v3.OpenApi3SecurityRenderer
import org.http4k.format.Json

/**
 * Creates an [OpenApi3] contract renderer that uses kotlinx.serialization for both schema
 * generation and document rendering, with no Jackson dependency.
 *
 * Defaults to OpenAPI 3.1.0. Nullable representation is controlled by [NullableStrategy] on the
 * schema creator (defaults to [NullableStrategy.TYPE_ARRAY] for code generator compatibility).
 *
 * The [ApiRenderer] is wrapped in [cached], which memoizes the final `api(...)` rendering step
 * only: [OpenApi3] still builds its `Api` model, and calls `toSchema` for every body, on each
 * request to `/openapi-schema.json`. This assumes the set of contract routes is **static** — built
 * once at startup and not mutated per-request. If a service needs dynamic contracts (e.g.
 * per-tenant routes registered after startup, feature-flag-toggled endpoints) the cached document
 * will serve the first-rendered snapshot indefinitely; construct [KotlinxOpenApi3Renderer] and pass
 * it to [OpenApi3] manually without `.cached()` in that case.
 *
 * Array-typed body examples (`List<...>` request or response bodies) need http4k 6.59.0.0 or newer:
 * older versions of http4k's kotlinx.serialization format reject a top-level JSON array when
 * [OpenApi3] re-parses the example, and silently drop it.
 *
 * Usage:
 * ```kotlin
 * val routes = contract {
 *     renderer = openApi3WithKotlinx(
 *         apiInfo = ApiInfo("My API", "1.0.0"),
 *         json = KotlinxSerialization,
 *         schema = schema,
 *     )
 * }
 * ```
 */
fun <NODE : Any> openApi3WithKotlinx(
    apiInfo: ApiInfo,
    json: Json<NODE>,
    schema: KotlinxSerializationJsonSchemaCreator<NODE>,
    extensions: List<OpenApiExtension> = emptyList(),
    servers: List<ApiServer> = emptyList(),
    securityRenderer: SecurityRenderer = OpenApi3SecurityRenderer,
    errorResponseRenderer: ErrorResponseRenderer = JsonErrorResponseRenderer(json),
    version: OpenApiVersion = OpenApiVersion._3_1_0,
): OpenApi3<NODE> {
  val renderer: ApiRenderer<Api<NODE>, NODE> = KotlinxOpenApi3Renderer(json, schema).cached()
  return OpenApi3(
      apiInfo = apiInfo,
      json = json,
      extensions = extensions,
      apiRenderer = renderer,
      securityRenderer = securityRenderer,
      errorResponseRenderer = errorResponseRenderer,
      servers = servers,
      version = version,
  )
}
