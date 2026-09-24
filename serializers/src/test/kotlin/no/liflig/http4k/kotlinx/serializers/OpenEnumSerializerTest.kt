package no.liflig.http4k.kotlinx.serializers

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import org.junit.jupiter.api.Test

@Serializable(with = Channel.Serializer::class)
enum class Channel {
  Email,
  @SerialName("sms") Sms,
  Unsupported;

  object Serializer : KSerializer<Channel> by OpenEnumSerializer(fallback = Unsupported)
}

@SerialName("renamed.Channel")
@Serializable(with = RenamedChannel.Serializer::class)
enum class RenamedChannel {
  Email,
  Unknown;

  object Serializer : KSerializer<RenamedChannel> by OpenEnumSerializer(fallback = Unknown)
}

/** Serialized names that differ only by case, which only a case-insensitive decode rejects. */
@Serializable(with = CaseClash.Serializer::class)
enum class CaseClash {
  @SerialName("ab") Lower,
  @SerialName("AB") Upper,
  Other;

  object Serializer : KSerializer<CaseClash> by OpenEnumSerializer(fallback = Other)
}

@Serializable
enum class PluginEnum {
  A,
  B,
}

@Serializable
data class Message(
    val channel: Channel,
    val optional: Channel? = null,
    val many: List<Channel> = emptyList(),
    val byChannel: Map<Channel, Int> = emptyMap(),
    val after: String = "",
)

class OpenEnumSerializerTest {

  private val json = Json

  @Test
  fun `decodes a known value, honouring SerialName on the entry`() {
    json.decodeFromString<Channel>("\"Email\"") shouldBe Channel.Email
    json.decodeFromString<Channel>("\"sms\"") shouldBe Channel.Sms
  }

  @Test
  fun `decodes an unknown value to the fallback`() {
    json.decodeFromString<Channel>("\"Pigeon\"") shouldBe Channel.Unsupported
    // The Kotlin name of a renamed entry is not a serialized name, so it is unknown too.
    json.decodeFromString<Channel>("\"Sms\"") shouldBe Channel.Unsupported
  }

  @Test
  fun `decodes unknown values inside fields, lists and map keys`() {
    val message =
        json.decodeFromString<Message>(
            """
            {"channel": "Pigeon", "optional": "Fax", "many": ["Email", "Pigeon"],
             "byChannel": {"sms": 1, "Pigeon": 2}, "after": "still read"}
            """
        )

    message shouldBe
        Message(
            channel = Channel.Unsupported,
            optional = Channel.Unsupported,
            many = listOf(Channel.Email, Channel.Unsupported),
            byChannel = mapOf(Channel.Sms to 1, Channel.Unsupported to 2),
            after = "still read",
        )
  }

  @Test
  fun `decodes null for a nullable field`() {
    json.decodeFromString<Message>("""{"channel": "Email", "optional": null}""").optional shouldBe
        null
  }

  @Test
  fun `decodes from a JSON tree as well as from a string`() {
    json.decodeFromJsonElement<Channel>(JsonPrimitive("sms")) shouldBe Channel.Sms
    json.decodeFromJsonElement<Channel>(JsonPrimitive("Pigeon")) shouldBe Channel.Unsupported
  }

  @Test
  fun `rejects a value that is not a string`() {
    for (value in listOf("123", "true", "{}", "[\"Email\"]")) {
      val error =
          shouldThrow<SerializationException> {
            json.decodeFromString<Message>("""{"channel": $value, "after": "x"}""")
          }
      error.message shouldContain "Expected a string for enum"
    }
  }

  @Test
  fun `accepts an unquoted literal under isLenient`() {
    val lenient = Json { isLenient = true }

    lenient.decodeFromString<Message>("{channel: Email}").channel shouldBe Channel.Email
    lenient.decodeFromString<Message>("{channel: Pigeon}").channel shouldBe Channel.Unsupported
  }

  @Test
  fun `honours decodeEnumsCaseInsensitive`() {
    val caseInsensitive = Json { decodeEnumsCaseInsensitive = true }

    caseInsensitive.decodeFromString<Channel>("\"EMAIL\"") shouldBe Channel.Email
    caseInsensitive.decodeFromString<Channel>("\"SMS\"") shouldBe Channel.Sms
    caseInsensitive.decodeFromString<Channel>("\"pigeon\"") shouldBe Channel.Unsupported
    // Case-sensitive by default, like the plugin-generated serializer.
    json.decodeFromString<Channel>("\"EMAIL\"") shouldBe Channel.Unsupported
  }

  @Test
  fun `rejects a case-insensitive match that is ambiguous, but not an exact one`() {
    val caseInsensitive = Json { decodeEnumsCaseInsensitive = true }

    caseInsensitive.decodeFromString<CaseClash>("\"AB\"") shouldBe CaseClash.Upper
    caseInsensitive.decodeFromString<CaseClash>("\"ab\"") shouldBe CaseClash.Lower
    caseInsensitive.decodeFromString<CaseClash>("\"other\"") shouldBe CaseClash.Other
    shouldThrow<SerializationException> { caseInsensitive.decodeFromString<CaseClash>("\"Ab\"") }
        .message shouldContain "more than one entry"
  }

  @Test
  fun `encodes serial names, the fallback included`() {
    json.encodeToString(Channel.Sms) shouldBe "\"sms\""
    json.encodeToString(Channel.Unsupported) shouldBe "\"Unsupported\""
    json.encodeToString(Message(Channel.Email, many = listOf(Channel.Sms))) shouldContain
        "\"many\":[\"sms\"]"
  }

  @Test
  fun `round trips an unknown value as the fallback`() {
    val decoded = json.decodeFromString<Channel>("\"Pigeon\"")

    json.encodeToString(decoded) shouldBe "\"Unsupported\""
  }

  @Test
  fun `names the type as the plugin would`() {
    Channel.serializer().descriptor.serialName shouldBe
        "no.liflig.http4k.kotlinx.serializers.Channel"
    RenamedChannel.serializer().descriptor.serialName shouldBe "renamed.Channel"
    Channel.serializer().descriptor.getElementName(1) shouldBe "sms"
  }

  @Test
  fun `marks the descriptor with the fallback's serial name`() {
    Channel.serializer().descriptor.openEnumFallback() shouldBe "Unsupported"
    Channel.serializer().nullable.descriptor.openEnumFallback() shouldBe "Unsupported"
  }

  @Test
  fun `leaves other descriptors unmarked`() {
    PluginEnum.serializer().descriptor.openEnumFallback() shouldBe null
    Message.serializer().descriptor.openEnumFallback() shouldBe null
  }
}
