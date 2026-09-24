package no.liflig.http4k.kotlinx.openapi

import io.kotest.matchers.shouldBe
import java.io.File
import kotlinx.serialization.json.Json as KotlinxJson
import kotlinx.serialization.json.JsonElement
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

private const val SNAPSHOT_PATH =
    "src/test/resources/no/liflig/http4k/kotlinx/openapi/openapi-snapshot.json"

private val prettyJson = KotlinxJson {
  prettyPrint = true
  prettyPrintIndent = "  "
}

/**
 * Renders one contract exercising every major shape the renderer handles -- plain bodies, a
 * sealed-class oneOf, nullable fields (both the inline and the $ref representation), a closed and
 * an open enum query parameter, and a raw JSON body -- and diffs the full document against a
 * checked-in fixture.
 *
 * Unlike [KotlinxOpenApi3RendererTest], which asserts individual fields so a change to one
 * feature's rendering doesn't fail tests for unrelated features, this test catches anything those
 * targeted assertions don't happen to check: stray whitespace, a shifted field order, an extra or
 * missing key anywhere in the document.
 *
 * To update the fixture after an intentional rendering change, run this test with
 * `-Dsnapshot.update=true` (e.g. `mvn test -pl core -Dtest=OpenApiSnapshotTest
 * -Dsnapshot.update=true`), inspect the diff with `git diff`, and commit it.
 */
class OpenApiSnapshotTest {

  @Test
  fun `rendered document matches the checked-in snapshot`() {
    val requestLens = Body.auto<CreateRequest>().toLens()
    val responseLens = Body.auto<CreateResponse>().toLens()
    val eventLens = Body.auto<EventResponse>().toLens()
    val nullableLens = Body.auto<NullableFieldDto>().toLens()
    val statusLens = Query.enum<StatusFilter>().required("status")
    val deliveryLens = Body.auto<DeliveryDto>().toLens()
    val channelLens = Query.enum<DeliveryChannel>().required("channel")
    val rawLens = json.body("raw json").toLens()

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
      routes +=
          "/deliveries" meta
              {
                summary = "List deliveries"
                queries += channelLens
                returning(OK, deliveryLens to DeliveryDto("id-1", DeliveryChannel.SMS))
              } bindContract
              GET to
              { _ ->
                Response(OK)
              }
      routes +=
          "/raw" meta
              {
                summary = "Raw JSON body"
                receiving(rawLens to json.obj("name" to json.string("test")))
              } bindContract
              POST to
              { _ ->
                Response(OK)
              }
    }

    val response = app(Request(GET, "/"))
    response.status.code shouldBe 200
    val actual = prettyPrinted(response.bodyString())

    val fixture = File(SNAPSHOT_PATH)
    if (System.getProperty("snapshot.update") == "true") {
      fixture.parentFile.mkdirs()
      fixture.writeText(actual)
      println("Updated snapshot fixture at ${fixture.path}")
    }

    if (!fixture.exists()) {
      throw AssertionError(
          "No snapshot fixture at $SNAPSHOT_PATH yet. Run with -Dsnapshot.update=true to " +
              "create it, review the diff, and commit it.",
      )
    }

    actual shouldBe fixture.readText()
  }
}

private fun prettyPrinted(rawJson: String): String =
    prettyJson.encodeToString(JsonElement.serializer(), KotlinxJson.parseToJsonElement(rawJson)) +
        "\n"
