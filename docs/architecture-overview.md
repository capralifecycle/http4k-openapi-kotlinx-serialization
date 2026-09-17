# Architecture overview

## Purpose

Generate OpenAPI 3.x schemas for [http4k](https://www.http4k.org/) contract endpoints
from [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) `@Serializable`
DTOs, **without Jackson on the classpath**.

http4k's stock `AutoJsonToJsonSchema` walks objects via JVM reflection. That misses
`@SerialName`, mishandles sealed-class polymorphism, and gets nullability wrong.
Liflig services using kotlinx.serialization previously carried ~100–250 lines of
per-service override code to compensate. This library replaces that boilerplate with a
single drop-in `JsonSchemaCreator` + `ApiRenderer` pair.

**Not in scope:** OpenAPI runtime serving (left to http4k's `OpenApi3`), HTTP routing,
runtime validation against the schema, code generation for clients.

## Position in the larger system

```
Liflig service
    └── http4k contract { ... }
            └── renderer = openApi3WithKotlinx(...)   ← THIS LIBRARY
                    ├── KotlinxOpenApi3Renderer       (ApiRenderer)
                    │     └── KotlinxSerializationJsonSchemaCreator  (JsonSchemaCreator)
                    └── http4k OpenApi3 (renders the OpenAPI document tree)
```

Upstream: any http4k service that wants kotlinx.serialization-based OpenAPI. Downstream:
http4k's `OpenApi3` contract renderer (re-used as-is) and the kotlinx.serialization
runtime (`Json`, `SerialDescriptor`, `KSerializer`).

## Module / package layout

Two Maven modules, `annotations` and `core`, published as two artifacts (AGENTS.md's
Source layout table is the canonical module map). Two packages:

| Package                                       | Responsibility                                                                 |
| --------------------------------------------- | ------------------------------------------------------------------------------ |
| `no.liflig.http4k.kotlinx.jsonschema`         | Schema generation from `SerialDescriptor` trees. Sealed-class example wiring.  |
| `no.liflig.http4k.kotlinx.openapi`            | http4k `ApiRenderer` adapter + `openApi3WithKotlinx` factory.                  |

Source files:

- `Description.kt` (module `annotations`) — the `@Description` annotation. Zero dependencies
  by design; every file below lives in module `core`.
- `KotlinxSerializationJsonSchemaCreator.kt` — the public entry point (implements http4k's
  `JsonSchemaCreator<Any, NODE>`). Resolves the root example's serializer, JSON and
  `KType`, then runs one `SchemaWalk` over a fresh `DefinitionRegistry`.
- `SchemaWalk.kt` — the descriptor traversal. One instance per `toSchema` call; every
  handler takes `(descriptor, jsonElement, kType)` and the per-call state lives in fields.
- `DefinitionRegistry.kt` — collects definitions by identity during the walk and names
  them once at the end (see Name resolution).
- `KotlinDeclarations.kt` — the reflection lookups a descriptor cannot answer:
  properties, annotations, class loading, sealed leaves.
- `SealedClassExampleProvider.kt` — interface + `DefaultSealedClassExampleProvider` that
  discovers examples via `companion.example` on sealed leaves, walking through nested
  sealed levels.
- `NullableStrategy.kt` — `TYPE_ARRAY` (default, generator-friendly) vs `ANYOF`
  (spec-strict).
- `KotlinxOpenApi3Renderer.kt` — `ApiRenderer`: tries `JsonToJsonSchema` for NODE bodies,
  hands everything else to the creator, and strips nulls from the rendered document
  outside body examples.
- `OpenApi3WithKotlinx.kt` — `openApi3WithKotlinx(...)` factory; wraps the renderer in
  `cached()`.

## End-to-end flow — `toSchema(dto)` for a `@Serializable` DTO

1. http4k's `OpenApi3` walks contract endpoints and calls
   `KotlinxOpenApi3Renderer.toSchema(obj, overrideDefinitionId, refModelNamePrefix)`
   for every body/response example.
2. **Step 1 — NODE shortcut.** Cast `obj as NODE` and try `JsonToJsonSchema`. Succeeds
   for raw JSON-tree bodies (`Json<NODE>`). On `ClassCastException`, fall through.
3. **Step 2 — kotlinx path.** `KotlinxSerializationJsonSchemaCreator.toSchema(obj)`:
   1. `rootExample` resolves the serializer, encodes the example to a `JsonElement`, and
      builds the root `KType`. A top-level collection or map is typed from its first entry.
      An enum constant is looked up by its declaring enum, since a constant with a body is
      an instance of a synthetic subclass that has no serializer. (`SerializationException`
      from http4k's `object {}` sentinel yields an empty schema; any other propagates.)
   2. `SchemaWalk.schemaFor` walks the `SerialDescriptor` recursively, emitting:
      - Primitives → `{"type": ...}` with optional `format` from `formatMappings`.
      - Classes → `{"$ref": ...}` via `DefinitionRegistry.define`, which builds and
        stores the definition unless it already exists or is being built further up the
        stack (recursion).
      - Lists / Maps / Enums / Sealed classes → kind-specific node shape (`oneOf` +
        `discriminator` for sealed).
      - Nullables → applied by `NullableStrategy`.
      - Properties annotated `@Deprecated` → `"deprecated": true` appended to the
        finished property schema (after `NullableStrategy`, so it lands on the outer
        object in every shape).
   3. Use the encoded `JsonElement` in parallel to extract example values by field name
      (descriptor gives structure, JSON tree gives values — reflection alone can't
      because of `@SerialName` invisibility).
   4. `DefinitionRegistry.finish` names every definition and rewrites the refs (see Name
      resolution). `overrideDefinitionId` renames the root — **except for enums**, which
      keep their serial name. http4k passes the *parameter* name as the override when
      rendering an enum query/path parameter, so honouring it would key the definition by
      the parameter (`status`) instead of the type (`TaskStatusDto`) and duplicate the
      component whenever the same enum also appears in a body.
4. `OpenApi3` assembles all schemas into the final document and calls
   `KotlinxOpenApi3Renderer.api(api)`, which delegates to `OpenApi3ApiRenderer` then
   **strips null fields** (http4k emits `"description": null` for unset descriptions,
   which is invalid OpenAPI). Two positions are exempt —
   `requestBody.content.<media-type>.example` and
   `responses.<status>.content.<media-type>.example` — the only places http4k writes
   body examples. Those subtrees are payloads, not scaffolding, and a `null` in one is
   data: the value the endpoint actually serializes for a nullable field. Stripping it
   produced examples that omitted properties their own schema listed as `required`. The
   exemption is decided by position in the document, not by key name, so a
   `@Serializable` property called `example` under `components/schemas` is stripped like
   any other structure.

## Sealed class handling

`SealedClassSerializer` produces a `SerialDescriptor` with a known structure:

- `element[0]` = discriminator property (string).
- `element[1]` = container whose elements are each subclass's descriptor.

The schema creator validates only `elementsCount >= 2` and throws a descriptive error if
that fails, guarding against a shape change in kotlinx.serialization internals. It
deliberately does not compare `element[0].serialName` to the class discriminator:
`element[0]` is always named `type`, the serializer's generator default, and the check
would falsely reject any hierarchy using `@JsonClassDiscriminator`. The discriminator name
is read from that annotation, falling back to `Json.classDiscriminator`. Example values for subclasses come from the
`SealedClassExampleProvider`:

- `data object` subclasses → used directly via `objectInstance`.
- `data class` subclasses → look up a `companion object` with an `example` property.
  Missing examples are silently skipped (the schema is still generated, just without
  example values for that subclass).
- A sealed subtype under the sealed parent is recursed into, matching
  `collectLeafSubclasses`; its own companion is not read.

`PolymorphicKind.OPEN` is rejected outright — the runtime-dynamic subclass set can't be
expressed as static JSON Schema.

## Nullable strategy

`NullableStrategy.TYPE_ARRAY` (default):

- Nullable primitives → `{"type": ["string", "null"]}` (OpenAPI 3.1 / JSON Schema
  2020-12).
- Nullable `$ref` types → plain `{"$ref": "..."}`, field excluded from `required`.

`NullableStrategy.ANYOF` (opt-in):

- Everything wrapped in `{"anyOf": [<schema>, {"type": "null"}]}`.

Default is `TYPE_ARRAY`. An earlier, unrecorded version of `openapi-generator-cli` (the
dominant TypeScript generator) was seen to emit empty wrapper interfaces for
`anyOf`-nullable fields; that does not reproduce with 7.21.0 or newer, which emit
`T | null`. `TYPE_ARRAY` stays the default regardless: it is equally valid OpenAPI 3.1 and
renders correctly across generators old and new. Strict validators that distinguish
"absent" from "null" should switch to `ANYOF`.

## Format mappings

Custom serializers (e.g. `LocalDateSerializer`) produce a `PrimitiveSerialDescriptor`
with a `serialName` but no OpenAPI `format`. Pass `formatMappings` (or
`COMMON_FORMAT_MAPPINGS`) to map short names → format strings (`Instant` → `date-time`,
`UUID` → `uuid`, etc.). Matching is on the short name (after the last `.`) of the
descriptor's `serialName`.

## Name resolution

During the walk, `DefinitionRegistry` keys every definition, and writes every `$ref`, by
its *identity*: the serial name, or for sealed parents and subclasses the qualified class
name. Identities are unique by construction, so nothing collides while walking.

`finish` then assigns published names in one pass. A definition whose short name (the
part after the last `.`) no other definition shares gets the short name:
`com.example.UserDto` → `UserDto`. Where two or more share it, each gets its identity
with `.` replaced by `_` (`com_example_UserDto`). `refModelNamePrefix` is prepended to
every name, and `overrideDefinitionId` replaces the root's. Finally every ref in the root
node and the definitions is rewritten from identity path to published path.

Naming once, after the walk, is what keeps this simple: an earlier design named
definitions as they were registered, and a later collision then had to evict the earlier
entry and repair refs already emitted into parents on the call stack.

## Caching

`openApi3WithKotlinx` wraps the renderer in http4k's `ApiRenderer.cached()`. That
memoises only the final `api(model)` step: `OpenApi3` still builds its `Api` model on
every request to `/openapi-schema.json`, which calls `toSchema` for every body. So
`KotlinxOpenApi3Renderer.api` runs once per process while `toSchema` runs per request;
`KotlinxSerializationJsonSchemaCreator` is internally stateless (each `toSchema`
allocates its own `SchemaWalk` and `DefinitionRegistry`). Measured in
a service on a warm JVM the per-request cost is a few milliseconds.

## Build & test

- `mvn test` — unit + approval tests, split by concern: schema structure, sealed
  hierarchies, definition naming and property annotations under `jsonschema`; end-to-end
  rendering and example payloads under `openapi`. 93 tests.
- `mvn verify` — full build (format check + tests).
- `mvn spotless:apply` — apply ktfmt formatting.
- `mvn versions:display-dependency-updates versions:display-parent-updates versions:display-property-updates`
  — check for newer versions of http4k, kotest, kotlinx-serialization, swagger-parser,
  and the `no.liflig:kotlin-parent` parent.

Tests use `io.swagger.parser.v3:swagger-parser` to assert the rendered OpenAPI document
parses cleanly (no schema-validity bugs slip through).

## Pitfalls / non-obvious bits

- **Array body examples need http4k 6.59.0.0 or newer.** `OpenApi3` builds each
  media-type example as `safeParse(message.bodyString())`, and `safeParse` swallows every
  exception into `null`. `Json.parse` defaults to `asJsonObject(String)`, which in
  `ConfigurableKotlinxSerialization` decodes with `JsonObject.serializer()` up to http4k
  6.58 and so throws on `[...]`. Object bodies keep their example, `List<...>` bodies lose
  it, with no error anywhere. 6.59.0.0 parses with `parseToJsonElement` and accepts
  arrays. The format class comes from the adopter's own http4k, so this library cannot
  fix it for them; the list-example tests in `KotlinxOpenApi3RendererTest` pin the
  behaviour against the http4k version in `pom.xml`. A `Json` decorator overriding `parse`
  was considered and rejected as public API that would outlive its purpose.

- **DO NOT** rely on reflection for kotlinx.serialization DTOs. `@SerialName` is
  invisible to reflection; nullability is unreliable; sealed-class polymorphism breaks.
  The whole reason this library exists is to bypass that path.
- **DO NOT** use `OpenApi3`'s secondary constructor with `KotlinxSerialization`. It
  picks `ApiRenderer.Auto`, which uses Jackson. Always pass `apiRenderer` as a **named
  parameter** so Kotlin selects the primary constructor — `openApi3WithKotlinx` does
  this for you.
- **`SealedClassSerializer` element shape is an implementation detail of
  kotlinx.serialization.** If a future version reshapes the descriptor, tests fail with
  a clear error (defensive validation in `KotlinxSerializationJsonSchemaCreator`). Read
  the thrown message before assuming a deeper bug.
- **`@Deprecated` and `@Description` detection is reflection-based, and has to be.** This
  does not contradict the first pitfall above, which is about *structure* — names,
  nullability, polymorphism — all of which the descriptor owns. kotlinx.serialization only
  retains annotations marked `@SerialInfo` in the generated descriptor, so
  `getElementAnnotations(i)` never sees `kotlin.Deprecated`; reflection is the only
  source. `buildObjectProperties` already resolves each element to its `KProperty` via
  `resolveProperty` (to thread generic `KType`s), so the annotation check rides along on
  a lookup that was happening anyway. Same rationale as
  `SealedClassExampleProvider`'s `companion.example` discovery.

  `@Description` is a library annotation, so `@SerialInfo` was available and deliberately
  not used: it is `@ExperimentalSerializationApi`, and serial-info annotations on
  constructor-parameters-as-properties are silently dropped without an explicit target —
  which is how essentially every DTO in Liflig services declares its fields.

- **A type's description goes to the definition or the use site, never both.** A type that
  renders as `$ref` owns a definition, so `withClassDescription` puts its `@Description`
  there. A type flattened into the property — an inline value class, or one whose custom
  serializer yields a primitive — has no definition, so `descriptionOf` falls back to it at
  the use site. `rendersAsReference` decides which, and it has to look **inside `anyOf`
  branches**: under `NullableStrategy.ANYOF` a nullable `$ref` sits one level down, and a
  top-level-only check reads it as inlined and copies the definition's description onto
  every field holding one.
- **Resolve a class from the `KType` before `loadKClass`.** `loadKClass` goes through
  `Class.forName`, which takes a *binary* name: a nested type is `Outer$Inner`, while its
  serial name is dotted (`com.example.Outer.Inner`). The lookup therefore fails for any
  nested declaration and, since both failure branches return `null`, does so silently —
  a `@Description` on a nested enum simply never appears. `kClassOf(kType, serialName)`
  encodes the right order and is the only way the walk resolves a class; the enum handler
  once had no `KType` to try, which is why one is threaded everywhere.
- **Enum constants with bodies are instances of a synthetic subclass.** http4k passes
  `enumConstants[0]` for a query or path enum parameter. When that constant declares a
  body, its runtime class is `Priority$HIGH`, a named (not anonymous) subclass with no
  serializer, and asking kotlinx for one throws. `rootExample` therefore resolves the
  serializer and `KType` from the declaring enum. A renderer-side Java-enum fallback used
  to exist for this and never ran, because it waited for the anonymous-class sentinel.
- **`@Transient` fields disappear silently** — kotlinx.serialization's compiler plugin
  excludes them from both the descriptor and the encoded JSON. No special handling
  required; they simply don't appear in schemas.
- **Generic type information is erased** at the entry point
  (`serializer(obj::class.java)`). If a serializer is not registered, kotlinx throws
  `SerializationException` and we propagate it as-is (fail-fast).

  Contract routes **do** pass bare `List<T>` at the top level in practice (a list-returning
  endpoint whose example is a `List<Dto>`). `obj::class.starProjectedType` is useless there —
  it yields `ArrayList<*>`, whose single argument is a star projection with a `null` `type`,
  so the list handler would have nothing to thread to its elements and `ownerKClass` in
  `buildObjectProperties` would be `null`. Anything needing the Kotlin declaration rather
  than the descriptor (property annotations, inline value class inner types) then silently
  degrades. `rootExample` avoids that by building the root type from the first entry's
  runtime class — the same source it uses for the element serializer.

  This matters across routes, not just within one walk. The `DefinitionRegistry` is
  created per `toSchema` call, so each route builds its own definitions
  independently; http4k concatenates all of them —
  `Components(json.obj(pathDefs + webhookPathDefs), …)` in `OpenApi3` — and settles a
  duplicate key by map-build order. A DTO reachable both directly *and* as a list element
  would otherwise produce two different definitions, with route order picking the winner.
  Both paths now start from a real element type, so they agree.

  `buildObjectProperties` still falls back to `loadKClass(descriptor.serialName)` when no
  `KType` arrived at all. After the above, that is a genuine last resort — a property whose
  declared type is a generic type parameter — and it resolves nothing for a class-level
  `@SerialName`. See `loadKClass` for the case it resolves wrongly.
- **`null` description fields in OpenAPI.** http4k's `OpenApi3ApiRenderer` emits
  `"description": null` for unset descriptions, which trips strict OpenAPI parsers.
  `KotlinxOpenApi3Renderer.api()` recursively strips null object values from the
  rendered tree before returning — everywhere except the two body-example positions,
  where a `null` is payload data rather than scaffolding (see step 5 of the data flow
  above). The rendered document is therefore null-free outside example payloads, not
  null-free everywhere. `isExamplePayload` anchors on the enclosing `requestBody` /
  `responses` member rather than on the key name, so structure that happens to use the
  name keeps being stripped — a DTO property called `example`, including one under a
  definition named `content`, and the `summary` / `description` members of an Example
  Object. The check fails closed: if http4k ever writes body examples somewhere else,
  their nulls are stripped again, and the tests pinning both positions are what catches
  the move.
- **Preserved example nulls vs. nullable `$ref` schemas.** The example-payload
  exemption above keeps `null` in example payloads, but under the default
  `NullableStrategy.TYPE_ARRAY` a nullable `$ref` property renders as a plain `$ref`
  (see `wrapNullable` — there is no `type` field to merge `"null"` into). A DTO with a
  nullable sealed-class, enum or nested-object field serialized as `null` therefore
  produces an example that its own schema rejects, and tools that validate examples
  against schemas report a type mismatch. Nullable primitives are fine —
  `{"type": ["string", "null"]}` accepts the null. This is the documented `TYPE_ARRAY`
  trade-off surfacing in examples rather than a separate defect; `NullableStrategy.ANYOF`
  makes both the schema and the example consistent.
- **`required` vs. a property's `default` value tracks `encodeDefaults`, not just
  whether the Kotlin property has a default.** `descriptor.isElementOptional(i)` is true
  for any property with a Kotlin default (unless annotated `@Required`), but that alone
  doesn't tell you what a consumer can rely on: under `encodeDefaults = false` (the
  kotlinx.serialization default) a value equal to its default is omitted from the wire, so
  the field is only reliably present when the caller sets it — it stays `required`. Under
  `encodeDefaults = true` the encoder always emits it, so the field is safe to omit on
  write and is marked optional, with its actual default surfaced as the JSON Schema
  `default` keyword. `buildObjectProperties` reads `kotlinxJson.configuration.encodeDefaults`
  to decide. The default's *value* isn't available from the descriptor (Kotlin default
  expressions aren't reflectable), so `defaultValueOf` recovers it generically: decode the
  current jsonObj with that one key removed (kotlinx fills the omitted key with the real
  default regardless of `encodeDefaults` — that flag only governs encoding), then re-encode
  the decoded instance with the same (encodeDefaults = true, by construction here)
  `kotlinxJson` and read the field back out. A `null` default is deliberately never emitted
  as `"default": null` — it would be indistinguishable from scaffolding and stripped by
  `KotlinxOpenApi3Renderer`'s null-stripping pass outside example payloads (see below), so
  it's silently omitted instead, same as a missing example.
- **Empty-schema sentinel from http4k.** http4k calls `toSchema(object {})` in
  `exampleSchemaIsValid` to test the comparator path. Resolving a serializer for an
  anonymous object throws `SerializationException`; the schema creator catches and
  returns an empty `JsonSchema(json.obj(), emptyMap())` so the comparison succeeds.
- **Bypass of http4k's `SchemaNode`.** `SchemaNode` is a `MutableMap<String,Any?>` with
  no support for `oneOf`/`discriminator`. Schemas are built directly via http4k's `json`
  DSL (`obj`/`string`/`array`) — a supported extension point, not a hack.
- **No published artifact for non-Liflig consumers.** The library is published to
  `maven.pkg.github.com/capralifecycle` (private). External consumers need to vendor or
  re-publish.
