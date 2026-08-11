package no.liflig.http4k.kotlinx.jsonschema

/**
 * Controls how nullable fields are represented in generated JSON Schema.
 *
 * Different OpenAPI code generators have varying levels of support for nullable representations.
 * This enum allows choosing the strategy that best fits the target consumer.
 */
enum class NullableStrategy {
  /**
   * Uses `type` arrays for nullable primitives and unwraps `$ref` for nullable reference types.
   * This is the recommended default for maximum code generator compatibility.
   *
   * Nullable primitives become `{"type": ["string", "null"]}` instead of `{"anyOf":
   * [{"type": "string"}, {"type": "null"}]}`.
   *
   * Nullable `$ref` types (classes, enums, sealed hierarchies) are emitted as plain `{"$ref":
   * "..."}` without an `anyOf` wrapper, since such a schema has no `type` field to merge `"null"`
   * into.
   *
   * Trade-off: for `$ref` types the schema then says nothing about the field accepting `null`. If
   * the field also has a Kotlin default it is absent from `required`, and generators treat it as
   * optional — close enough for TypeScript generators, which conflate optional and nullable. If it
   * has no default it *is* in `required` (`required` follows `descriptor.isElementOptional`, not
   * nullability), and the `null` case is not expressed at all. Use [ANYOF] when that distinction
   * matters.
   */
  TYPE_ARRAY,

  /**
   * Wraps all nullable types with `anyOf: [schema, {"type": "null"}]`. This is the most
   * semantically precise representation in OpenAPI 3.1 / JSON Schema 2020-12, explicitly encoding
   * that a field can be either its declared type or null.
   *
   * Use this when the schema is consumed by strict validators that distinguish "field absent" from
   * "field present with null value", or when spec correctness is more important than code generator
   * compatibility.
   *
   * Known issue: `openapi-generator-cli` (typescript-fetch) generates empty wrapper interfaces for
   * `anyOf` nullable fields instead of proper nullable types.
   */
  ANYOF,
}
