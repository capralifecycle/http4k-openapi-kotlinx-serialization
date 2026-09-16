package no.liflig.http4k.kotlinx.openapi

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json as KotlinxJson
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method.GET
import org.http4k.core.Method.POST
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status.Companion.OK
import org.http4k.format.KotlinxSerialization.auto
import org.http4k.lens.Query
import org.http4k.lens.enum
import org.junit.jupiter.api.Test

/** End-to-end rendering through `openApi3WithKotlinx`: bodies, parameters, enums, validity. */
class KotlinxOpenApi3RendererTest {

  @Test
  fun `renders openapi document without jackson`() {
    val requestLens = Body.auto<CreateRequest>().toLens()
    val responseLens = Body.auto<CreateResponse>().toLens()

    val app = buildContract {
      routes +=
          "/items" meta
              {
                summary = "Create item"
                receiving(requestLens to CreateRequest("test", 42))
                returning(OK, responseLens to CreateResponse("id-1", true))
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)

    spec["openapi"]?.jsonPrimitive?.content shouldBe "3.1.0"

    val info = spec["info"]?.jsonObject.shouldNotBeNull()
    info["title"]?.jsonPrimitive?.content shouldBe "Test API"

    val paths = spec["paths"]?.jsonObject.shouldNotBeNull()
    paths shouldContainKey "/items"

    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()
    schemas.shouldNotBeEmpty()
  }

  @Test
  fun `renders sealed class in response body`() {
    val responseLens = Body.auto<EventResponse>().toLens()

    val app = buildContract {
      routes +=
          "/events" meta
              {
                summary = "Get events"
                returning(OK, responseLens to EventResponse(EventPayload.example))
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    schemas shouldContainKey "EventPayload"
    val eventPayload = schemas["EventPayload"]?.jsonObject.shouldNotBeNull()
    eventPayload shouldContainKey "oneOf"
    schemas shouldContainKey "Created"
  }

  @Test
  fun `renders enum query parameter`() {
    val statusLens = Query.enum<StatusFilter>().required("status")

    val app = buildContract {
      routes +=
          "/items" meta
              {
                summary = "List items"
                queries += statusLens
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    // Keyed by the enum type, not by the "status" parameter name that http4k passes as
    // overrideDefinitionId.
    schemas shouldContainKey "StatusFilter"
    schemas shouldNotContainKey "status"

    val enumValues =
        schemas["StatusFilter"]
            ?.jsonObject
            ?.get("enum")
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            .shouldNotBeNull()
    enumValues shouldContain "ACTIVE"
    enumValues shouldContain "INACTIVE"
    enumValues shouldContain "ALL"

    val paramSchema =
        spec["paths"]
            ?.jsonObject
            ?.get("/items")
            ?.jsonObject
            ?.get("get")
            ?.jsonObject
            ?.get("parameters")
            ?.jsonArray
            ?.single()
            ?.jsonObject
            ?.get("schema")
            ?.jsonObject
    paramSchema?.get("\$ref")?.jsonPrimitive?.content shouldBe "#/components/schemas/StatusFilter"
  }

  @Test
  fun `renders enum query parameter whose constants declare bodies`() {
    val priorityLens = Query.enum<Priority>().required("priority")

    val app = buildContract {
      routes +=
          "/items" meta
              {
                summary = "List items"
                queries += priorityLens
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    // Keyed by the enum type, not by the constant's anonymous class or the parameter name.
    schemas shouldContainKey "Priority"
    schemas shouldNotContainKey "priority"
    schemas["Priority"]?.jsonObject?.get("enum")?.jsonArray?.map {
      it.jsonPrimitive.content
    } shouldBe listOf("HIGH", "LOW")
  }

  @Test
  fun `enum used as both query parameter and body field yields one component`() {
    val statusLens = Query.enum<TaskStatus>().required("status")
    val bodyLens = Body.auto<TaskDto>().toLens()

    val app = buildContract {
      routes +=
          "/tasks" meta
              {
                summary = "List tasks"
                queries += statusLens
                returning(OK, bodyLens to TaskDto("id-1", TaskStatus.OPEN))
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    // Both the parameter and the body field resolve to the same definition. Honouring the
    // parameter name as overrideDefinitionId would emit a second, identical "status" component.
    schemas shouldContainKey "TaskStatus"
    schemas shouldNotContainKey "status"
    schemas.keys.filter { it.contains("TaskStatus", ignoreCase = true) } shouldBe
        listOf("TaskStatus")
  }

  @Test
  fun `renders raw json object body via JsonToJsonSchema`() {
    val bodyLens = json.body("raw json").toLens()
    val example = json.obj("name" to json.string("test"), "count" to json.number(42))

    val app = buildContract {
      routes +=
          "/raw" meta
              {
                summary = "Raw JSON body"
                receiving(bodyLens to example)
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    // Raw JSON object should produce a schema with properties derived from the JSON structure
    val rawDef =
        schemas.values.firstOrNull { def ->
          val obj = def.jsonObject
          obj["type"]?.jsonPrimitive?.content == "object" && obj.containsKey("properties")
        }
    rawDef.shouldNotBeNull()

    val properties = rawDef.jsonObject["properties"]?.jsonObject.shouldNotBeNull()
    properties shouldContainKey "name"
    properties shouldContainKey "count"
  }

  @Test
  fun `nullable inline property uses type array in 3_1 document`() {
    val responseLens = Body.auto<NullableFieldDto>().toLens()

    val app = buildContract {
      routes +=
          "/nullable" meta
              {
                summary = "Nullable fields"
                returning(OK, responseLens to NullableFieldDto("hello"))
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    spec["openapi"]?.jsonPrimitive?.content shouldBe "3.1.0"

    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    val dto = schemas["NullableFieldDto"]?.jsonObject.shouldNotBeNull()

    val properties = dto["properties"]?.jsonObject.shouldNotBeNull()

    // The "optional" field should use type array with "null" (TYPE_ARRAY strategy default)
    val optional = properties["optional"]?.jsonObject.shouldNotBeNull()
    val typeArray = optional["type"]?.jsonArray.shouldNotBeNull()
    val types = typeArray.map { it.jsonPrimitive.content }
    types shouldContain "null"
    types shouldContain "string"
  }

  @Test
  fun `nullable ref property uses plain ref in 3_1 document`() {
    val responseLens = Body.auto<NullableFieldDto>().toLens()

    val app = buildContract {
      routes +=
          "/nullable-ref" meta
              {
                summary = "Nullable ref fields"
                returning(
                    OK,
                    responseLens to
                        NullableFieldDto("hello", optionalEvent = EventPayload.Created("x")),
                )
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)

    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()

    val dto = schemas["NullableFieldDto"]?.jsonObject.shouldNotBeNull()
    val properties = dto["properties"]?.jsonObject.shouldNotBeNull()

    // The "optionalEvent" field references a sealed class — TYPE_ARRAY strategy emits plain $ref
    val optionalEvent = properties["optionalEvent"]?.jsonObject.shouldNotBeNull()
    optionalEvent["\$ref"]?.jsonPrimitive?.content.shouldNotBeNull()
    optionalEvent["anyOf"] shouldBe null
  }

  @Test
  fun `deprecated properties survive rendering and validate as openapi 3_1`() {
    val responseLens = Body.auto<DeprecatedPropertyDto>().toLens()

    val app = buildContract {
      routes +=
          "/deprecated" meta
              {
                summary = "Deprecated properties"
                returning(
                    OK,
                    responseLens to DeprecatedPropertyDto("old", "new", EventPayload.Created("x")),
                )
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val response = app(Request(GET, "/"))
    response.status.code shouldBe 200
    val spec = KotlinxJson.parseToJsonElement(response.bodyString()).jsonObject

    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()
    val dto = schemas["DeprecatedPropertyDto"]?.jsonObject.shouldNotBeNull()
    val properties = dto["properties"]?.jsonObject.shouldNotBeNull()

    properties["legacy"]?.jsonObject?.get("deprecated")?.jsonPrimitive?.boolean shouldBe true
    properties["replacement"]?.jsonObject?.get("deprecated") shouldBe null

    // A $ref sibling must survive the null-stripping pass in KotlinxOpenApi3Renderer.api().
    val legacyEvent = properties["legacyEvent"]?.jsonObject.shouldNotBeNull()
    legacyEvent["deprecated"]?.jsonPrimitive?.boolean shouldBe true
    legacyEvent["\$ref"]?.jsonPrimitive?.content.shouldNotBeNull()

    val parseResult =
        io.swagger.parser.OpenAPIParser().readContents(response.bodyString(), null, null)
    parseResult.messages.orEmpty().shouldBeEmpty()
  }

  @Test
  fun `descriptions survive rendering and validate as openapi 3_1`() {
    val responseLens = Body.auto<DescribedPropertyDto>().toLens()

    val app = buildContract {
      routes +=
          "/described" meta
              {
                summary = "Described properties"
                returning(
                    OK,
                    responseLens to
                        DescribedPropertyDto(
                            "a",
                            "b",
                            DescribedCodeDto("NO201"),
                            EventPayload.Created("x"),
                        ),
                )
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val response = app(Request(GET, "/"))
    response.status.code shouldBe 200
    val spec = KotlinxJson.parseToJsonElement(response.bodyString()).jsonObject

    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()
    val dto = schemas["DescribedPropertyDto"]?.jsonObject.shouldNotBeNull()
    val properties = dto["properties"]?.jsonObject.shouldNotBeNull()

    properties["described"]?.jsonObject?.get("description")?.jsonPrimitive?.content shouldBe
        "What this field is for."
    properties["undescribed"]?.jsonObject?.get("description") shouldBe null

    // Inherited from the value class, which is flattened into the property and so has no
    // definition of its own to carry it.
    properties["fromType"]?.jsonObject?.get("description")?.jsonPrimitive?.content shouldBe
        "A code, as the traffic systems write it."

    // A $ref sibling must survive the null-stripping pass in KotlinxOpenApi3Renderer.api().
    val describedEvent = properties["describedEvent"]?.jsonObject.shouldNotBeNull()
    describedEvent["description"]?.jsonPrimitive?.content shouldBe
        "Described alongside a reference."
    describedEvent["\$ref"]?.jsonPrimitive?.content.shouldNotBeNull()

    val parseResult =
        io.swagger.parser.OpenAPIParser().readContents(response.bodyString(), null, null)
    parseResult.messages.orEmpty().shouldBeEmpty()
  }

  @Test
  fun `list-returning route marks deprecated on a dto with a class-level SerialName`() {
    val listLens = Body.auto<List<RenamedResponseDto>>().toLens()

    val app = buildContract {
      routes +=
          "/items" meta
              {
                summary = "List items"
                returning(
                    OK,
                    listLens to listOf(RenamedResponseDto("id-1", "old-code", "new-code")),
                )
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val schemas = spec["components"]?.jsonObject?.get("schemas")?.jsonObject.shouldNotBeNull()
    val dto = schemas["renamed_response"]?.jsonObject.shouldNotBeNull()
    val properties = dto["properties"]?.jsonObject.shouldNotBeNull()

    properties["legacyCode"]?.jsonObject?.get("deprecated")?.jsonPrimitive?.boolean shouldBe true
    properties["code"]?.jsonObject?.get("deprecated") shouldBe null
  }

  @Test
  fun `rendered spec is valid openapi 3_1`() {
    val requestLens = Body.auto<CreateRequest>().toLens()
    val responseLens = Body.auto<CreateResponse>().toLens()
    val eventLens = Body.auto<EventResponse>().toLens()
    val nullableLens = Body.auto<NullableFieldDto>().toLens()
    val statusLens = Query.enum<StatusFilter>().required("status")

    val app = buildContract {
      routes +=
          "/items" meta
              {
                summary = "Create item"
                receiving(requestLens to CreateRequest("test", 42))
                returning(OK, responseLens to CreateResponse("id-1", true))
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
      routes +=
          "/events" meta
              {
                summary = "Get events"
                returning(OK, eventLens to EventResponse(EventPayload.Created("test")))
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
      routes +=
          "/nullable" meta
              {
                summary = "Nullable fields"
                returning(
                    OK,
                    nullableLens to
                        NullableFieldDto("hello", optionalEvent = EventPayload.Created("x")),
                )
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
      routes +=
          "/filtered" meta
              {
                summary = "Filtered list"
                queries += statusLens
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
    }

    val response = app(Request(GET, "/"))

    // Write spec file for optional manual validation (e.g. `npx @redocly/cli lint
    // target/openapi-spec.json`)
    val specFile = java.io.File("target/openapi-spec.json")
    specFile.parentFile.mkdirs()
    specFile.writeText(response.bodyString())

    // Validate with swagger-parser
    val parseResult =
        io.swagger.parser.OpenAPIParser().readContents(response.bodyString(), null, null)
    val errors = parseResult.messages.orEmpty()
    errors.shouldBeEmpty()

    val openApi = parseResult.openAPI.shouldNotBeNull()
    openApi.openapi shouldBe "3.1.0"
  }

  @Test
  fun `renders raw json array body via JsonToJsonSchema`() {
    val bodyLens = json.body("raw json array").toLens()
    val example = json.array(listOf(json.obj("id" to json.string("1"))))

    val app = buildContract {
      routes +=
          "/raw-array" meta
              {
                summary = "Raw JSON array body"
                receiving(bodyLens to example)
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
    }

    val spec = fetchSpec(app)
    val paths = spec["paths"]?.jsonObject.shouldNotBeNull()
    paths shouldContainKey "/raw-array"
    requestExample(spec, "/raw-array", "post") shouldBe example
  }
}
