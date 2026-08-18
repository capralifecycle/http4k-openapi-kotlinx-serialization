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

Single Maven module. Two packages:

| Package                                       | Responsibility                                                                 |
| --------------------------------------------- | ------------------------------------------------------------------------------ |
| `no.liflig.http4k.kotlinx.jsonschema`         | Schema generation from `SerialDescriptor` trees. Sealed-class example wiring.  |
| `no.liflig.http4k.kotlinx.openapi`            | http4k `ApiRenderer` adapter + `openApi3WithKotlinx` factory.                  |

Five source files total:

- `KotlinxSerializationJsonSchemaCreator.kt` — core schema walker (implements http4k's
  `JsonSchemaCreator<Any, NODE>`).
- `SealedClassExampleProvider.kt` — interface + `DefaultSealedClassExampleProvider` that
  discovers examples via `companion.example` on sealed subclasses.
- `NullableStrategy.kt` — `TYPE_ARRAY` (default, generator-friendly) vs `ANYOF`
  (spec-strict).
- `KotlinxOpenApi3Renderer.kt` — `ApiRenderer` with a three-step fallback chain (NODE
  bodies → `@Serializable` DTOs → Java Enum reflection).
- `OpenApi3WithKotlinx.kt` — `openApi3WithKotlinx(...)` factory; wraps the renderer in
  `cached()` so rendered docs are memoised across requests.

## End-to-end flow — `toSchema(dto)` for a `@Serializable` DTO

1. http4k's `OpenApi3` walks contract endpoints and calls
   `KotlinxOpenApi3Renderer.toSchema(obj, overrideDefinitionId, refModelNamePrefix)`
   for every body/response example.
2. **Step 1 — NODE shortcut.** Cast `obj as NODE` and try `JsonToJsonSchema`. Succeeds
   for raw JSON-tree bodies (`Json<NODE>`). On `ClassCastException`, fall through.
3. **Step 2 — kotlinx path.** `KotlinxSerializationJsonSchemaCreator.toSchema(obj)`:
   1. Resolve the serializer via `kotlinxJson.serializersModule.serializer(obj::class.java)`
      and encode the example to a `JsonElement`. (Catches `SerializationException` from
      http4k's `object {}` sentinel and returns an empty schema.)
   2. Walk the `SerialDescriptor` recursively (`descriptorToSchema`), emitting:
      - Primitives → `{"type": ...}` with optional `format` from `formatMappings`.
      - Classes → `{"$ref": "#/components/schemas/<name>"}` and accumulate the
        definition in `DefinitionAccumulator`.
      - Lists / Maps / Enums / Sealed classes → kind-specific node shape (`oneOf` +
        `discriminator` for sealed).
      - Nullables → applied by `NullableStrategy`.
      - Properties annotated `@Deprecated` → `"deprecated": true` appended to the
        finished property schema (after `NullableStrategy`, so it lands on the outer
        object in every shape).
   3. Use the encoded `JsonElement` in parallel to extract example values by field name
      (descriptor gives structure, JSON tree gives values — reflection alone can't
      because of `@SerialName` invisibility).
   4. Apply `overrideDefinitionId` after collision resolution if set — **except for
      enums**, which keep their serial name. http4k passes the *parameter* name as the
      override when rendering an enum query/path parameter, so honouring it would key
      the definition by the parameter (`status`) instead of the type (`TaskStatusDto`)
      and duplicate the component whenever the same enum also appears in a body.
4. **Step 3 — Enum fallback.** If step 2 returned an empty schema and `obj` is a Java
   `Enum<*>` (http4k passes `paramMeta.clz.java.enumConstants[0]` for query/path enum
   parameters), generate `{"type": "string", "enum": [...]}` via reflection, named after
   the declaring class for the same reason. Read the constants off the declaring class,
   not `javaClass` — the latter is a synthetic subclass for constants with a body, where
   `enumConstants` is null.
5. `OpenApi3` assembles all schemas into the final document and calls
   `KotlinxOpenApi3Renderer.api(api)`, which delegates to `OpenApi3ApiRenderer` then
   **strips null fields** (http4k emits `"description": null` for unset descriptions,
   which is invalid OpenAPI). Subtrees under `example` / `examples` are exempt: those
   are payloads, not scaffolding, and a `null` in one is data — the value the endpoint
   actually serializes for a nullable field. Stripping them produced examples that
   omitted properties their own schema listed as `required`.

## Sealed class handling

`SealedClassSerializer` produces a `SerialDescriptor` with a known structure:

- `element[0]` = discriminator property (string).
- `element[1]` = container whose elements are each subclass's descriptor.

The schema creator validates this shape (`elementsCount >= 2` and
`element[0].serialName == classDiscriminator`) and throws a descriptive error if
kotlinx.serialization changes internals. Example values for subclasses come from the
`SealedClassExampleProvider`:

- `data object` subclasses → used directly via `objectInstance`.
- `data class` subclasses → look up a `companion object` with an `example` property.
  Missing examples are silently skipped (the schema is still generated, just without
  example values for that subclass).

`PolymorphicKind.OPEN` is rejected outright — the runtime-dynamic subclass set can't be
expressed as static JSON Schema.

## Nullable strategy

`NullableStrategy.TYPE_ARRAY` (default):

- Nullable primitives → `{"type": ["string", "null"]}` (OpenAPI 3.1 / JSON Schema
  2020-12).
- Nullable `$ref` types → plain `{"$ref": "..."}`, field excluded from `required`.

`NullableStrategy.ANYOF` (opt-in):

- Everything wrapped in `{"anyOf": [<schema>, {"type": "null"}]}`.

Default is `TYPE_ARRAY` because `openapi-generator-cli` (the dominant TypeScript
generator) emits empty wrapper interfaces for `anyOf`-nullable fields. Strict validators
that distinguish "absent" from "null" should switch to `ANYOF`.

## Format mappings

Custom serializers (e.g. `LocalDateSerializer`) produce a `PrimitiveSerialDescriptor`
with a `serialName` but no OpenAPI `format`. Pass `formatMappings` (or
`COMMON_FORMAT_MAPPINGS`) to map short names → format strings (`Instant` → `date-time`,
`UUID` → `uuid`, etc.). Matching is on the short name (after the last `.`) of the
descriptor's `serialName`.

## Name-collision resolution

Definition names default to the `@Serializable` class short name (or `@SerialName` if
present), e.g. `com.example.UserDto` → `UserDto`. On collision (different `serialName`,
same short name), both the existing and new definitions are re-keyed to full
underscore-separated names (`com_example_UserDto`). `refModelNamePrefix` is applied
**after** collision resolution.

## Caching

`openApi3WithKotlinx` wraps the renderer in http4k's `ApiRenderer.cached()` so the
serialised OpenAPI document is memoised across requests to `/openapi-schema.json`. The
underlying `KotlinxOpenApi3Renderer` is therefore expected to be called once per
process; `KotlinxSerializationJsonSchemaCreator` is internally stateless (no `defs` map
shared across calls — each `toSchema` allocates its own `DefinitionAccumulator`).

## Build & test

- `mvn test` — unit + approval tests (`KotlinxSerializationJsonSchemaCreatorTest`,
  `KotlinxOpenApi3RendererTest`). 47 tests.
- `mvn verify` — full build (format check + tests).
- `mvn spotless:apply` — apply ktfmt formatting.
- `mvn versions:display-dependency-updates versions:display-parent-updates versions:display-property-updates`
  — check for newer versions of http4k, kotest, kotlinx-serialization, swagger-parser,
  and the `no.liflig:kotlin-parent` parent.

Tests use `io.swagger.parser.v3:swagger-parser` to assert the rendered OpenAPI document
parses cleanly (no schema-validity bugs slip through).

## Pitfalls / non-obvious bits

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
- **`@Deprecated` detection is reflection-based, and has to be.** This does not
  contradict the first pitfall above, which is about *structure* — names, nullability,
  polymorphism — all of which the descriptor owns. kotlinx.serialization only retains
  annotations marked `@SerialInfo` in the generated descriptor, so
  `getElementAnnotations(i)` never sees `kotlin.Deprecated`; reflection is the only
  source. `buildObjectProperties` already resolves each element to its `KProperty` via
  `resolveProperty` (to thread generic `KType`s), so the annotation check rides along on
  a lookup that was happening anyway. Same rationale as
  `SealedClassExampleProvider`'s `companion.example` discovery.
- **`@Transient` fields disappear silently** — kotlinx.serialization's compiler plugin
  excludes them from both the descriptor and the encoded JSON. No special handling
  required; they simply don't appear in schemas.
- **Generic type information is erased** at the entry point
  (`serializer(obj::class.java)`). If a serializer is not registered, kotlinx throws
  `SerializationException` and we propagate it as-is (fail-fast).

  Contract routes **do** pass bare `List<T>` at the top level in practice (a list-returning
  endpoint whose example is a `List<Dto>`). `obj::class.starProjectedType` is useless there —
  it yields `ArrayList<*>`, whose single argument is a star projection with a `null` `type`,
  so `listToSchema` would have nothing to thread to its elements and `ownerKClass` in
  `buildObjectProperties` would be `null`. Anything needing the Kotlin declaration rather
  than the descriptor (property annotations, inline value class inner types) then silently
  degrades. `resolveRootKType` avoids that by building the root type from the first entry's
  runtime class — the same source `resolveSerializerAndEncode` already uses for element
  serializers.

  This matters across routes, not just within one walk. `DefinitionAccumulator` and
  `visited` are created per `toSchema` call, so each route builds its own definitions
  independently; http4k concatenates all of them —
  `Components(json.obj(pathDefs + webhookPathDefs), …)` in `OpenApi3` — and settles a
  duplicate key by map-build order. A DTO reachable both directly *and* as a list element
  would otherwise produce two different definitions, with route order picking the winner.
  Both paths now start from a real element type, so they agree.

  `buildObjectProperties` still falls back to `Class.forName(descriptor.serialName)` when no
  `KType` arrived at all. After the above, that is a genuine last resort — a property whose
  declared type is a generic type parameter — and it resolves nothing for a class-level
  `@SerialName`. See `loadKClass` for the case it resolves wrongly.
- **`null` description fields in OpenAPI.** http4k's `OpenApi3ApiRenderer` emits
  `"description": null` for unset descriptions, which trips strict OpenAPI parsers.
  `KotlinxOpenApi3Renderer.api()` recursively strips null object values from the
  rendered tree before returning — everywhere except under `example` / `examples`,
  where a `null` is payload data rather than scaffolding (see step 5 of the data flow
  above). The rendered document is therefore null-free outside example payloads, not
  null-free everywhere.
- **Preserved example nulls vs. nullable `$ref` schemas.** The `example` / `examples`
  exemption above keeps `null` in example payloads, but under the default
  `NullableStrategy.TYPE_ARRAY` a nullable `$ref` property renders as a plain `$ref`
  (see `wrapNullable` — there is no `type` field to merge `"null"` into). A DTO with a
  nullable sealed-class, enum or nested-object field serialized as `null` therefore
  produces an example that its own schema rejects, and tools that validate examples
  against schemas report a type mismatch. Nullable primitives are fine —
  `{"type": ["string", "null"]}` accepts the null. This is the documented `TYPE_ARRAY`
  trade-off surfacing in examples rather than a separate defect; `NullableStrategy.ANYOF`
  makes both the schema and the example consistent.
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
