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

| File                                                                                  | What                                                              |
| ------------------------------------------------------------------------------------- | ----------------------------------------------------------------- |
| `src/main/kotlin/no/liflig/http4k/kotlinx/jsonschema/KotlinxSerializationJsonSchemaCreator.kt` | Core schema walker. Implements `JsonSchemaCreator<Any, NODE>`.    |
| `src/main/kotlin/no/liflig/http4k/kotlinx/jsonschema/SealedClassExampleProvider.kt`   | `companion.example` discovery for sealed subclasses.              |
| `src/main/kotlin/no/liflig/http4k/kotlinx/jsonschema/NullableStrategy.kt`             | `TYPE_ARRAY` (default) vs `ANYOF`.                                |
| `src/main/kotlin/no/liflig/http4k/kotlinx/openapi/KotlinxOpenApi3Renderer.kt`         | `ApiRenderer` with NODE → kotlinx → Java-Enum fallback chain.     |
| `src/main/kotlin/no/liflig/http4k/kotlinx/openapi/OpenApi3WithKotlinx.kt`             | `openApi3WithKotlinx(...)` factory, wraps the renderer in `cached()`. |
| `src/test/kotlin/no/liflig/http4k/kotlinx/jsonschema/TestDtos.kt`                     | Test DTOs covering primitives, nullables, sealed classes, maps.   |
| `src/test/kotlin/no/liflig/http4k/kotlinx/jsonschema/KotlinxSerializationJsonSchemaCreatorTest.kt` | Approval tests; uses `swagger-parser` to validate rendered output. |
| `src/test/kotlin/no/liflig/http4k/kotlinx/openapi/KotlinxOpenApi3RendererTest.kt`     | End-to-end renderer test (`openApi3WithKotlinx`).                 |

## Build & test

```bash
mvn test            # unit + approval tests (47 tests)
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
  Don't import `com.fasterxml.jackson.*` anywhere in `src/main/`.
- **Test DTOs live in `TestDtos.kt`.** Add new variants there rather than defining
  one-off `@Serializable` classes inside test methods — the approval tests rely on a
  shared, stable set.
- **Class-level KDoc when adding a class** — one short sentence on responsibility.

## Testing

Unit tests only (47 tests, JUnit 5 + Kotest assertions, `swagger-parser` for OpenAPI
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

## General principles

- Follow existing conventions; ask before introducing new ones.
- Check the latest stable version with the Maven versions plugin (see above) before
  bumping a dependency or quoting a version. Don't trust prior knowledge or memory.
- Update this file (and `docs/architecture-overview.md` for substantive changes) when
  new patterns, conventions, or gotchas are discovered.
