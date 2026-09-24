# http4k-openapi-kotlinx-serialization — agent guide

Liflig library: OpenAPI 3.x schema generation for http4k contract endpoints driven by
kotlinx.serialization `SerialDescriptor` trees instead of Jackson reflection.

## Where to start

- [docs/architecture-overview.md](docs/architecture-overview.md) — **read first for
  code work in this repo.** Internal data flow, package layout, `toSchema(...)` walk,
  sealed-class handling, nullable strategy, pitfalls.
- [README.md](README.md) — **read for downstream-consumer questions.** User-facing
  integration guide, format mappings, nullable strategy choices, sealed-class example
  pattern.

## Source layout

Four published modules. `annotations` holds `@Description` alone and **must stay
dependency-free** — that is the whole point of it, so a library of pure domain types can
annotate its value classes without taking http4k onto its compile path. `serializers` holds
general-purpose kotlinx serializers for DTO libraries (`OpenEnumSerializer` so far) and may
depend on `kotlinx-serialization-json` **and nothing else** — no http4k, no kotlin-reflect;
new reusable serializers go here, not in `core`. `core` keeps the
`http4k-openapi-kotlinx-serialization` coordinates and depends on both at compile scope, so
consumers of the main artifact see no difference; it recognises the serializers that render
specially through public descriptor queries (`openEnumFallback()`), never by serializer type.
`annotations` and `core` share the package `no.liflig.http4k.kotlinx.jsonschema`, which keeps
every existing import working; the repo has no `module-info.java`, so the split package is a
classpath detail only. `serializers` uses `no.liflig.http4k.kotlinx.serializers`, since it is
runtime serialization rather than schema generation. `bom` has no source — it's a
`dependencyManagement`-only pom pinning the other three to the version they were released
together with, for consumers who'd rather import one BOM coordinate than pin each artifact
separately (see "BOM and release versioning" below).

| File                                                                                  | What                                                              |
| ------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `annotations/src/main/kotlin/no/liflig/http4k/kotlinx/jsonschema/Description.kt` | `@Description`. No dependencies — keep it that way.               |
| `serializers/src/main/kotlin/no/liflig/http4k/kotlinx/serializers/OpenEnumSerializer.kt` | Enum serializer that decodes unknown values to a fallback entry.  |
| `serializers/src/main/kotlin/no/liflig/http4k/kotlinx/serializers/OpenEnum.kt`        | Internal descriptor marker, read through `openEnumFallback()`.    |
| `core/src/main/kotlin/no/liflig/http4k/kotlinx/jsonschema/KotlinxSerializationJsonSchemaCreator.kt` | Entry point. Implements `JsonSchemaCreator<Any, NODE>`; resolves the root example, runs one walk. |
| `core/src/main/kotlin/no/liflig/http4k/kotlinx/jsonschema/SchemaWalk.kt`                   | The descriptor traversal: one instance per `toSchema` call. Its class KDoc holds the rendering-rules table — **update it with every new rendering path.** |
| `core/src/main/kotlin/no/liflig/http4k/kotlinx/jsonschema/DefinitionRegistry.kt`           | Collects definitions by identity; names them once after the walk. |
| `core/src/main/kotlin/no/liflig/http4k/kotlinx/jsonschema/KotlinDeclarations.kt`           | Reflection lookups the descriptor cannot answer.                  |
| `core/src/main/kotlin/no/liflig/http4k/kotlinx/jsonschema/SealedClassExampleProvider.kt`   | `companion.example` discovery for sealed subclasses.              |
| `core/src/main/kotlin/no/liflig/http4k/kotlinx/jsonschema/NullableStrategy.kt`             | `TYPE_ARRAY` (default) vs `ANYOF`.                                |
| `core/src/main/kotlin/no/liflig/http4k/kotlinx/openapi/KotlinxOpenApi3Renderer.kt`         | `ApiRenderer`: NODE shortcut, then kotlinx; strips nulls outside examples. |
| `core/src/main/kotlin/no/liflig/http4k/kotlinx/openapi/OpenApi3WithKotlinx.kt`             | `openApi3WithKotlinx(...)` factory, wraps the renderer in `cached()`. |
| `core/src/test/kotlin/no/liflig/http4k/kotlinx/jsonschema/TestDtos.kt`                     | Test DTOs covering primitives, nullables, sealed classes, maps.   |
| `core/src/test/kotlin/no/liflig/http4k/kotlinx/jsonschema/*Test.kt`                        | Schema tests by concern: structure, sealed, naming, annotations, open enums. Shared creators in `SchemaTestSupport.kt`. |
| `serializers/src/test/kotlin/no/liflig/http4k/kotlinx/serializers/*Test.kt`               | Serializer behaviour (decode, encode, descriptor), with their own DTOs. |
| `core/src/test/kotlin/no/liflig/http4k/kotlinx/openapi/*Test.kt`                           | End-to-end via `openApi3WithKotlinx`, plus `ExamplePayloadTest`. Shared DTOs in `RendererTestSupport.kt`. |

## Build & test

```bash
mvn test            # unit + approval tests (120 tests: 106 core, 14 serializers)
mvn verify          # full build (spotless check + tests)
mvn spotless:apply  # apply ktfmt formatting (run before committing)
```

When updating dependencies use the Maven versions plugin (not Maven Central web pages —
the local repo also includes the private GitHub Packages repo for `no.liflig:*`
artifacts):

```bash
mvn versions:display-dependency-updates versions:display-parent-updates versions:display-property-updates
```

Filter out `-Beta`, `-RC`, `-M`, `-SNAPSHOT` before bumping. Run `mvn test` after every
property bump.

## Project conventions

- **Package-by-feature.** Two packages, both feature-scoped: `jsonschema` (schema
  generation) and `openapi` (http4k renderer adapter). Don't introduce `common/` for a
  five-file library.
- **No Jackson.** This library exists to remove Jackson from the OpenAPI rendering path.
  Don't import `com.fasterxml.jackson.*` anywhere in `core/src/main/`.
- **Test DTOs live in `TestDtos.kt`.** Add new variants there rather than defining
  one-off `@Serializable` classes inside test methods — the approval tests rely on a
  shared, stable set.
- **Class-level KDoc when adding a class** — one short sentence on responsibility.

## Testing

Unit tests only (120 tests, JUnit 5 + Kotest assertions, `swagger-parser` for OpenAPI
validation). Write tests as part of development; test code is source code; ROI matters
— not everything is worth testing.

## Release & CI

`.github/workflows/ci.yml` runs on every push to every branch:

- **master push** → builds, then `mvn deploy scm:tag` publishes to GitHub Packages with
  an auto-generated tag like `1.YYYYMMDD.HHMMSS` (major version from `pom.xml`,
  punctuated timestamp from `capralifecycle/actions-lib/generate-tag`).
- **branch push** → `mvn -B -U verify` only (no release).

Implication: every commit to master becomes a published release. Prefer PRs / feature
branches for non-trivial changes so downstream consumers aren't bumping versions for
typo fixes.

### BOM and release versioning

`core`, `annotations`, `serializers` and `bom` all inherit `<version>${revision}</version>` from this
parent pom, and the release step passes a single `-Drevision="$TAG"` to one `mvn deploy`
covering the whole reactor. That's the entire sync mechanism: there's no version to keep
up to date by hand, because all four modules are stamped with the same property in the
same build. `flatten-maven-plugin` (`resolveCiFriendliesOnly`) then bakes that resolved
version into every published pom before upload — including the `${revision}` references
inside `bom/pom.xml`'s `dependencyManagement` and inside `core/pom.xml`'s dependencies on
`annotations` and `serializers` — so what consumers resolve is always concrete, matching
version numbers, never a placeholder.

This is also why a downstream consumer importing the BOM can never see `core`,
`annotations` and `serializers` diverge *within one BOM version* — they were only ever built and deployed
together. Drift is only possible *across* BOM versions, if a consumer imports one version
of the BOM in one place and a newer one arrives transitively through another dependency;
`mvn enforcer:enforce -Drules=dependencyConvergence` (if the consumer has that rule, as
`cn-cx-customs-se` does) will still catch that case, same as it would for any other
version conflict.

**When adding another published module**, add its `dependencyManagement` entry to
`bom/pom.xml` in the same PR — the BOM is a manually maintained list, not derived from
`<modules>`, so a new artifact only gets covered once someone adds it there.



- Follow existing conventions; ask before introducing new ones.
- Check the latest stable version with the Maven versions plugin (see above) before
  bumping a dependency or quoting a version. Don't trust prior knowledge or memory.
- Update this file (and `docs/architecture-overview.md` for substantive changes) when
  new patterns, conventions, or gotchas are discovered.
