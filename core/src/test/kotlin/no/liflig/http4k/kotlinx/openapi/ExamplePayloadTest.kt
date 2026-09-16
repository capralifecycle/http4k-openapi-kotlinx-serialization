package no.liflig.http4k.kotlinx.openapi

import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.contract.meta
import org.http4k.contract.openapi.ApiInfo
import org.http4k.contract.openapi.v3.Api
import org.http4k.contract.openapi.v3.ApiServer
import org.http4k.contract.openapi.v3.Components
import org.http4k.core.Body
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.core.Uri
import org.http4k.format.KotlinxSerialization.auto
import org.junit.jupiter.api.Test

/** Body examples in the rendered document: null preservation and array bodies. */
class ExamplePayloadTest {

  @Test
  fun `null values in a response example are preserved`() {
    val responseLens = Body.auto<OverridesDto>().toLens()

    val app = buildContract {
      routes +=
          "/overrides" meta
              {
                summary = "Get overrides"
                returning(
                    OK,
                    responseLens to OverridesDto(overriddenName = null, overriddenCount = 3),
                )
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)

    // The document-level null stripping must not reach into example payloads: `overriddenName` is
    // required by the schema, so dropping it would leave the example failing its own validation.
    val example =
        spec["paths"]
            ?.jsonObject
            ?.get("/overrides")
            ?.jsonObject
            ?.get("get")
            ?.jsonObject
            ?.get("responses")
            ?.jsonObject
            ?.get("200")
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.values
            ?.first()
            ?.jsonObject
            ?.get("example")
            ?.jsonObject
    example.shouldNotBeNull()
    example.keys shouldBe setOf("overriddenName", "overriddenCount")
    example["overriddenName"] shouldBe JsonNull
    example["overriddenCount"]?.jsonPrimitive?.content shouldBe "3"

    val required =
        spec["components"]
            ?.jsonObject
            ?.get("schemas")
            ?.jsonObject
            ?.get("OverridesDto")
            ?.jsonObject
            ?.get("required")
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
    required shouldBe listOf("overriddenName", "overriddenCount")
  }

  @Test
  fun `null scaffolding values are stripped from the document`() {
    val renderer = KotlinxOpenApi3Renderer(json = json, schema = schema)

    // The whole point of stripNullValues: a "description": null trips strict OpenAPI parsers.
    // Driven through a hand-built document because http4k 6.57.1.0 guards every null-capable
    // emission of its own (`description?.let`, non-null ApiPath.summary), so no route fixture can
    // produce one — the strip defends against http4k versions that do not. The nulls sit at an
    // object, a nested object and an array element to cover all three arms of the walk.
    val schemas =
        json.obj(
            "WidgetDto" to
                json.obj(
                    "type" to json.string("object"),
                    "description" to json.nullNode(),
                    "properties" to
                        json.obj(
                            "name" to
                                json.obj(
                                    "type" to json.string("string"),
                                    "description" to json.nullNode(),
                                ),
                        ),
                    "oneOf" to
                        json.array(
                            listOf(
                                json.obj(
                                    "type" to json.string("object"),
                                    "title" to json.nullNode(),
                                ),
                            ),
                        ),
                ),
        )

    val document =
        renderer.api(
            Api(
                info = ApiInfo("Test API", "1.0.0"),
                tags = emptyList(),
                paths = emptyMap(),
                components = Components(schemas = schemas, securitySchemes = json.obj()),
                servers = listOf(ApiServer(Uri.of("http://localhost:8080"))),
                webhooks = null,
                openapi = "3.1.0",
            ),
        )

    nullPaths(document) shouldBe emptyList()
  }

  @Test
  fun `rendered document keeps nulls only inside example payloads`() {
    // Nullable without defaults, so kotlinx encodes the nulls into the example rather than
    // omitting them the way it does for fields that have one.
    val responseLens = Body.auto<OverridesDto>().toLens()

    val app = buildContract {
      routes +=
          "/overrides" meta
              {
                summary = "Get overrides"
                returning(
                    OK,
                    responseLens to OverridesDto(overriddenName = null, overriddenCount = 3),
                )
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val nulls = nullPaths(fetchSpec(app))

    // The fixture serializes explicit nulls, so the walk has something to find — a document-wide
    // "contains no null" assertion would fail on those, and on the "null" in the type arrays that
    // NullableStrategy.TYPE_ARRAY renders for nullable primitives.
    nulls.isEmpty() shouldBe false
    nulls.filterNot { exampleBodyPath.containsMatchIn(it) } shouldBe emptyList()
  }

  @Test
  fun `nulls under a schema property named example are stripped`() {
    val renderer = KotlinxOpenApi3Renderer(json = json, schema = schema)

    // A DTO property called "example" lands in components/schemas, where it is document structure
    // rather than an example payload. The exemption keys off position, so this subtree is stripped
    // like any other. Under a definition named "content" the property also completes a trailing
    // content.<any>.example match, which is why the check anchors on requestBody / responses.
    // Built by hand because the schema creator never emits nulls of its own.
    val definitionIds = listOf("ConfigDto", "content")
    val schemas =
        json.obj(
            definitionIds.map { definitionId ->
              definitionId to
                  json.obj(
                      "type" to json.string("object"),
                      "properties" to
                          json.obj(
                              "example" to
                                  json.obj(
                                      "type" to json.string("string"),
                                      "description" to json.nullNode(),
                                  ),
                          ),
                  )
            },
        )

    val document =
        renderer.api(
            Api(
                info = ApiInfo("Test API", "1.0.0"),
                tags = emptyList(),
                paths = emptyMap(),
                components = Components(schemas = schemas, securitySchemes = json.obj()),
                servers = listOf(ApiServer(Uri.of("http://localhost:8080"))),
                webhooks = null,
                openapi = "3.1.0",
            ),
        )

    definitionIds.forEach { definitionId ->
      val property =
          document.jsonObject["components"]
              ?.jsonObject
              ?.get("schemas")
              ?.jsonObject
              ?.get(definitionId)
              ?.jsonObject
              ?.get("properties")
              ?.jsonObject
              ?.get("example")
              ?.jsonObject
      property.shouldNotBeNull()
      property shouldNotContainKey "description"
      property["type"]?.jsonPrimitive?.content shouldBe "string"
    }
  }

  @Test
  fun `null values in a request example are preserved`() {
    val requestLens = Body.auto<OverridesDto>().toLens()

    val app = buildContract {
      routes +=
          "/overrides" meta
              {
                summary = "Create overrides"
                receiving(requestLens to OverridesDto(overriddenName = null, overriddenCount = 3))
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)

    // Request bodies sit at requestBody.content.<media-type>.example rather than under responses,
    // and are exempt from the strip for the same reason.
    val example =
        spec["paths"]
            ?.jsonObject
            ?.get("/overrides")
            ?.jsonObject
            ?.get("post")
            ?.jsonObject
            ?.get("requestBody")
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.values
            ?.first()
            ?.jsonObject
            ?.get("example")
            ?.jsonObject
    example.shouldNotBeNull()
    example["overriddenName"] shouldBe JsonNull
    example["overriddenCount"]?.jsonPrimitive?.content shouldBe "3"
  }

  @Test
  fun `a payload property named example does not confuse the example exemption`() {
    val responseLens = Body.auto<ExampleNamedFieldDto>().toLens()

    val app = buildContract {
      routes +=
          "/example-named" meta
              {
                summary = "Property named example"
                returning(OK, responseLens to ExampleNamedFieldDto(example = null, label = "x"))
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)

    // Inside the payload the key is data, so its null survives — the position under
    // content.<media-type>.example decides, not the name of the property it holds.
    val example =
        spec["paths"]
            ?.jsonObject
            ?.get("/example-named")
            ?.jsonObject
            ?.get("get")
            ?.jsonObject
            ?.get("responses")
            ?.jsonObject
            ?.get("200")
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?.values
            ?.first()
            ?.jsonObject
            ?.get("example")
            ?.jsonObject
    example.shouldNotBeNull()
    example["example"] shouldBe JsonNull
    example["label"]?.jsonPrimitive?.content shouldBe "x"
  }

  @Test
  fun `list response body keeps its example`() {
    val listLens = Body.auto<List<CreateResponse>>().toLens()

    val app = buildContract {
      routes +=
          "/items" meta
              {
                summary = "List items"
                returning(
                    OK,
                    listLens to listOf(CreateResponse("id-1", true), CreateResponse("id-2", false)),
                )
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val example = responseExample(fetchSpec(app), "/items", "get").shouldNotBeNull().jsonArray
    example.map { it.jsonObject["id"]?.jsonPrimitive?.content } shouldBe listOf("id-1", "id-2")
  }

  @Test
  fun `list request body keeps its example`() {
    val listLens = Body.auto<List<CreateRequest>>().toLens()

    val app = buildContract {
      routes +=
          "/items" meta
              {
                summary = "Create items"
                receiving(listLens to listOf(CreateRequest("first", 1)))
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
    }

    val example = requestExample(fetchSpec(app), "/items", "post").shouldNotBeNull().jsonArray
    example.map { it.jsonObject["name"]?.jsonPrimitive?.content } shouldBe listOf("first")
  }

  @Test
  fun `null values inside a list example are preserved`() {
    val listLens = Body.auto<List<OverridesDto>>().toLens()

    val app = buildContract {
      routes +=
          "/overrides" meta
              {
                summary = "List overrides"
                returning(OK, listLens to listOf(OverridesDto(null, 3)))
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val example = responseExample(fetchSpec(app), "/overrides", "get").shouldNotBeNull().jsonArray
    example.single().jsonObject["overriddenName"] shouldBe JsonNull
  }
}
