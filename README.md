# http4k-openapi-kotlinx-serialization

OpenAPI schema generation for http4k contract endpoints using kotlinx.serialization descriptors instead of reflection.

## Why This Exists

http4k's `AutoJsonToJsonSchema` uses Java/Kotlin reflection to walk object properties. This breaks with kotlinx.serialization where:

- `@SerialName` annotations are invisible to reflection
- Sealed class polymorphism requires manual workarounds (SealedClassSchemaOverride or full forks)
- Nullable fields are not reliably detected from reflected properties

Services using kotlinx.serialization carry 100-250 lines of per-service override code to work around these limitations.

## Architecture Decisions

### SchemaNode Bypass

http4k's `SchemaNode` is a `MutableMap<String,Any?>` that gets serialized via `json.asJsonObject()`. It has no support for `oneOf`/`discriminator` schema constructs needed for sealed classes.

**Decision**: Build JSON schema NODE directly via http4k's json DSL (`obj`/`string`/`array`) instead of going through `SchemaNode`. This gives full control over schema structure without forking http4k internals.

**Tradeoff**: We bypass `SchemaNode` entirely. If http4k changes its json DSL API, we need to adapt. However, the json DSL is stable and used widely across http4k.

### ApiRenderer Fallback Chain

`ApiRenderer.Auto()` has an internal fallback chain:
1. Try treating the value as NODE (`JsonToJsonSchema`)
2. On `ClassCastException`, fall back to schema creator

Our implementation is the schema creator path. This is a supported extension point, not a hack.

### SEALED Descriptor Internals

kotlinx.serialization's `SealedClassSerializer` builds descriptors with:
- `element[0]` = discriminator property
- `element[1]` = container of all subclass descriptors

**Risk**: This is an implementation detail, not a stable API.

**Mitigation**: Defensive validation at runtime:
- Verify `elementsCount >= 2`
- Verify `element[0].serialName == classDiscriminator`
- Throw descriptive error on structure mismatch

If kotlinx.serialization changes this structure, tests fail immediately with clear error messages.

### Dual Walk Strategy

Schema structure comes from `SerialDescriptor` traversal. Example values come from serializing the example object to JSON and navigating the resulting `JsonObject` by field name.

For each descriptor element at index `i`, look up `descriptor.getElementName(i)` in the `JsonObject` to get the field's JSON value.

**Why not use descriptor for both?**: `SerialDescriptor` provides structure but not runtime values. The example object contains the values but reflection can't extract them correctly (see `@SerialName` issue above).

### Generic Type Erasure

We obtain `SerialDescriptor` via `kotlinxJson.serializersModule.serializer(obj::class.java)`. Generic type information is erased at this entry point.

**Why acceptable**: `toSchema` receives concrete `@Serializable` wrapper DTOs from http4k contract routes. These are always concrete types, not generic List<T> or Map<K,V> in the abstract. Inside the descriptor walk, generic argument types and inline value-class inner types are recovered by threading a `KType` through traversal, so collection element types and inline-class wrappers resolve correctly even though the top-level entry point can't see them.

**`object {}` sentinel**: http4k calls `toSchema(object {})` from `exampleSchemaIsValid` to probe the comparator path. Resolving a serializer for an anonymous object throws `SerializationException`; we catch it and return an empty `JsonSchema` so the comparison succeeds. All other unregistered-serializer cases propagate the exception as-is (fail-fast).

### @Transient Fields

kotlinx.serialization's compiler plugin excludes `@Transient` fields from both:
- `SerialDescriptor.elementDescriptors`
- `encodeToJsonElement` output

**Decision**: No special handling needed. They're invisible to our code automatically.

### PolymorphicKind.OPEN Not Supported

OPEN polymorphism has a runtime-dynamic subclass set incompatible with static JSON Schema generation. No known Liflig service uses OPEN for API DTOs.

**Decision**: Throw `IllegalArgumentException` immediately if encountered. Better to fail fast than silently generate incomplete schemas.

### DtoCompanion Pattern

Liflig services follow a pattern where DTOs expose example instances via companion object `example` property. `SealedClassExampleProvider` discovers these via reflection on `companionObjectInstance`.

The schema creator doesn't care about this pattern - it just receives whatever example instance arrives via `toSchema(obj)`. The example provider is a separate concern.

### Name Collision Resolution

Short definition names are derived from `serialName` (e.g., `com.example.UserDto` -> `UserDto`). On collision (different `serialName`, same short name), both the existing and new definitions are re-keyed to full underscore-separated names (`com_example_UserDto`).

`refModelNamePrefix` is applied after collision resolution, not before.

## Adding to your project

Add the dependency to your `pom.xml`:

```xml
<dependency>
  <groupId>no.liflig</groupId>
  <artifactId>http4k-openapi-kotlinx-serialization</artifactId>
  <version>${http4k-openapi-kotlinx-serialization.version}</version>
</dependency>
```

You also need http4k's OpenAPI and kotlinx.serialization dependencies. See the project's `pom.xml` for the full list.

## Integration

### Recommended: `openApi3WithKotlinx` helper

The `openApi3WithKotlinx` helper wires everything needed for Jackson-free OpenAPI rendering and defaults to OpenAPI 3.1.0.

By default, nullable fields use `type` arrays (`{"type": ["string", "null"]}`) for primitives and plain `$ref` for reference types. This ensures compatibility with widely used code generators like `openapi-generator-cli`. See [Nullable strategy](#nullable-strategy) for details and alternatives.

```kotlin
import no.liflig.http4k.kotlinx.jsonschema.KotlinxSerializationJsonSchemaCreator
import no.liflig.http4k.kotlinx.openapi.openApi3WithKotlinx
import org.http4k.contract.contract
import org.http4k.contract.openapi.ApiInfo
import org.http4k.format.KotlinxSerialization

val kotlinxJson = Json { ignoreUnknownKeys = true }

val schema = KotlinxSerializationJsonSchemaCreator<JsonElement>(
    json = KotlinxSerialization,
    kotlinxJson = kotlinxJson,
)

val routes = contract {
    renderer = openApi3WithKotlinx(
        apiInfo = ApiInfo("My API", "1.0.0"),
        json = KotlinxSerialization,
        schema = schema,
    )
    routes += "/user" / userId meta {
        summary = "Get user"
    } bindContract GET to ::getUser
}
```

The helper internally creates a `KotlinxOpenApi3Renderer` and passes it to http4k's `OpenApi3` constructor with `version = OpenApiVersion._3_1_0`. You can override the version if needed:

```kotlin
openApi3WithKotlinx(
    apiInfo = apiInfo,
    json = KotlinxSerialization,
    schema = schema,
    version = OpenApiVersion._3_0_0, // opt in to 3.0 explicitly
)
```

### Manual wiring with `KotlinxOpenApi3Renderer`

For full control over the `OpenApi3` constructor, use `KotlinxOpenApi3Renderer` directly:

```kotlin
val renderer = KotlinxOpenApi3Renderer(
    json = KotlinxSerialization,
    schema = schema,
)

val routes = contract {
    this.renderer = OpenApi3(
        apiInfo = ApiInfo("My API", "1.0.0"),
        json = KotlinxSerialization,
        apiRenderer = renderer,
        version = OpenApiVersion._3_1_0,
    )
}
```

Passing `apiRenderer` as a named parameter forces Kotlin to use the `OpenApi3` primary constructor (which takes `Json<NODE>`), avoiding the secondary constructor that uses `ApiRenderer.Auto` (which would fail with KotlinxSerialization).

The renderer's `toSchema()` fallback chain mirrors `ApiRenderer.Auto`:
1. Raw JSON bodies (`json.body().toLens()` with NODE examples) are handled via `JsonToJsonSchema`
2. `@Serializable` DTOs are handled via `KotlinxSerializationJsonSchemaCreator`
3. Java Enum constants (for query/path parameters) are handled via reflection-based enum schema generation

### Format mappings

Custom serializers (e.g. for `LocalDate`, `Instant`) produce `PrimitiveSerialDescriptor` with a `serialName` but no OpenAPI `format`. The `formatMappings` parameter lets you map descriptor names to OpenAPI format strings:

```kotlin
val schema = KotlinxSerializationJsonSchemaCreator<JsonElement>(
    json = KotlinxSerialization,
    kotlinxJson = kotlinxJson,
    formatMappings = KotlinxSerializationJsonSchemaCreator.COMMON_FORMAT_MAPPINGS,
)
```

`COMMON_FORMAT_MAPPINGS` includes:

| Descriptor name | OpenAPI format |
|---|---|
| `Instant` | `date-time` |
| `LocalDate` | `date` |
| `LocalDateTime` | `date-time` |
| `ZonedDateTime` | `date-time` |
| `UUID` | `uuid` |
| `URI` | `uri` |

You can provide your own map or extend the common mappings:

```kotlin
formatMappings = KotlinxSerializationJsonSchemaCreator.COMMON_FORMAT_MAPPINGS + mapOf(
    "Duration" to "duration",
)
```

The mapping matches against the short name (after the last `.`) of the serializer's `serialName`.

### Nullable strategy

The `nullableStrategy` parameter controls how nullable fields are represented in the generated JSON Schema. The default (`TYPE_ARRAY`) is designed for maximum compatibility with code generators.

```kotlin
val schema = KotlinxSerializationJsonSchemaCreator<JsonElement>(
    json = KotlinxSerialization,
    kotlinxJson = kotlinxJson,
    nullableStrategy = NullableStrategy.TYPE_ARRAY, // default
)
```

| Strategy | Nullable primitives | Nullable `$ref` types | Best for |
|---|---|---|---|
| `TYPE_ARRAY` (default) | `{"type": ["string", "null"]}` | Plain `{"$ref": "..."}` (field excluded from `required`) | Code generators (openapi-generator-cli, openapi-typescript, etc.) |
| `ANYOF` | `{"anyOf": [{"type": "string"}, {"type": "null"}]}` | `{"anyOf": [{"$ref": "..."}, {"type": "null"}]}` | Strict JSON Schema validators that distinguish "absent" from "null" |

**Why `TYPE_ARRAY` is the default**: The `anyOf` pattern, while semantically precise per OpenAPI 3.1 / JSON Schema 2020-12, is not handled correctly by `openapi-generator-cli` (the most widely used TypeScript code generator). It generates empty wrapper interfaces instead of proper nullable types. The `type` array form is equally valid OpenAPI 3.1 and produces correct output from all major generators.

**Trade-off with `TYPE_ARRAY`**: For nullable `$ref` types (objects, enums, sealed classes), there is no `type` array equivalent. Instead, the `$ref` is emitted without a nullable wrapper, and the field is excluded from `required`. This means strict JSON Schema validators won't know the field accepts `null` (only that it's optional). In practice, TypeScript consumers treat optional and nullable identically (`field?: Type`), so this is acceptable for code generation use cases.

**Limitation — nullable `$ref` inside collections**: `TYPE_ARRAY` only suppresses nullability for `$ref` types at the field level (where excluding from `required` makes the field optional). For `$ref` types inside collection elements — `List<Child?>`, `Map<String, Child?>`, `Set<Child?>` — there is no field-level `required` to elide; the element schema is just a `$ref` and the rendered schema documents elements that don't accept `null`. If a DTO uses nullable refs inside collections AND consumers need to distinguish missing from null at the element level, switch to `ANYOF`.

Use `ANYOF` if your schema consumers are strict validators that need to distinguish "field absent" from "field present with value null", or if you have nullable refs inside collection elements:

```kotlin
val schema = KotlinxSerializationJsonSchemaCreator<JsonElement>(
    json = KotlinxSerialization,
    kotlinxJson = kotlinxJson,
    nullableStrategy = NullableStrategy.ANYOF,
)
```

### Required fields and default values

A field is added to the OpenAPI `required` array based on kotlinx.serialization's `descriptor.isElementOptional(i)`, **not** Kotlin's notion of nullability. The rule:

- **No default value** → required (regardless of nullability).
- **Has a Kotlin default value** (e.g. `val x: String = "..."`) → optional. The compiler plugin marks it `isElementOptional = true`, so kotlinx can deserialize JSON that omits the field.
- **Has a default value AND is annotated with `@Required`** → required again. Use this when the default is a fallback for in-process construction but the JSON wire format must always include the field.

```kotlin
@Serializable
data class CreateOrderRequest(
    val customerId: UUID,                  // required (no default)
    val notes: String? = null,             // optional (has default)
    @Required val source: String = "web",  // required (default + @Required)
)
```

Nullable fields without a default are still required — they just accept `null` as a value (see [Nullable strategy](#nullable-strategy) for how that's encoded).

### Inline value classes

Kotlinx-serialization's compiler plugin generates a `descriptor.isInline = true` descriptor for `@JvmInline value class X(val value: Y)`. The schema creator unwraps these: the schema for `X` is the schema for `Y`. This matters for Liflig services that use inline value classes for type-safe IDs:

```kotlin
@JvmInline value class UserId(override val value: UUID) : UuidEntityId
@Serializable data class User(val id: UserId, val name: String)
```

`User.id` appears in the schema as `{"type": "string", "format": "uuid"}` (assuming `formatMappings` includes `UUID`), not as a wrapping object. Definition names also don't gain a `UserId` indirection.

### Supported map types

The map key serializer must have `kind = PrimitiveKind.STRING`. What this means in practice:

- `Map<String, T>` works.
- `Map<UUID, T>` works (the standard kotlinx UUID serializer has `PrimitiveKind.STRING`). Any custom serializer that writes its value as a JSON string is also fine — what matters is the descriptor's `kind`, not the underlying Kotlin type.
- `Map<EnumX, T>` throws (enum key descriptor has `SerialKind.ENUM`).
- `Map<Int, T>` and other non-string primitives throw (`PrimitiveKind.INT`, etc.).
- `Map<UserId, T>` where `UserId` is an inline `@JvmInline value class` throws — the inline descriptor has `StructureKind.CLASS`, even though the runtime representation is a string. Inline value classes are unwrapped in property positions (see above), but the map-key check looks at the declared descriptor kind, not the unwrapped type.

For maps with enum or inline-value-class keys, serialize them as `List<{key, value}>` records, or use kotlinx-serialization's `MapAsArraySerializer` for the wire format.

### `overrideDefinitionId`

By default, schema definitions are named after the `@Serializable` class (or `@SerialName` if present). The `overrideDefinitionId` parameter on `toSchema()` lets you rename the top-level definition:

```kotlin
schema.toSchema(myDto, overrideDefinitionId = "CreateUserRequest")
```

This is useful when the same DTO class is used for different endpoints and you want distinct schema names in the OpenAPI document. For sealed classes, only the top-level definition is renamed — subclass definitions keep their original names.

### Sealed class example discovery

When the schema creator encounters a sealed class field, it needs example instances of the subclasses to generate complete schemas. The `sealedClassExampleProvider` parameter controls how these are discovered.

The default (`DefaultSealedClassExampleProvider`) looks for examples in two places:
1. `object` subclasses (data objects) are used directly
2. `data class` subclasses: looks for a `companion object` with an `example` property

```kotlin
@Serializable
sealed class EventPayload {
  @Serializable @SerialName("created")
  data class Created(val name: String) : EventPayload() {
    companion object {
      val example = Created("test-item")
    }
  }

  @Serializable @SerialName("deleted")
  data object Deleted : EventPayload()
}
```

If a sealed `data class` subclass is missing a `companion object` with an `example` property, the schema will still generate but without example values for that subclass. There is no compile-time enforcement — make sure to add examples for all data class subclasses used in API contracts.

You can replace this with a custom `SealedClassExampleProvider` if your DTOs follow a different pattern.

## Development

```bash
mvn spotless:apply   # format code
mvn test             # run tests
mvn verify           # full build (format check + tests)
```
