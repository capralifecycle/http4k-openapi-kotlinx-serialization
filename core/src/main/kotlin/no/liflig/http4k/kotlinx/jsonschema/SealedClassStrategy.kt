package no.liflig.http4k.kotlinx.jsonschema

/**
 * Controls how a sealed hierarchy's parent definition is rendered.
 *
 * Subclass definitions are the same under both strategies: an object whose discriminator property
 * is required and pinned to the subclass's serial name with a single-value `enum`. That pin is what
 * tells the branches apart, so a validator or code generator can match a payload to exactly one
 * subclass without the OpenAPI `discriminator` object. A single-value `enum` rather than `const`
 * keeps the output valid under OpenAPI 3.0 as well as 3.1.
 */
enum class SealedClassStrategy {
  /**
   * `oneOf` of `$ref`s plus an OpenAPI `discriminator` object with `propertyName` and `mapping`.
   * The default. Lets generators dispatch on the discriminator value directly, e.g. a `switch` on
   * `dish` in `openapi-generator-cli`'s `typescript-fetch` output.
   */
  DISCRIMINATED_ONE_OF,

  /**
   * `oneOf` of `$ref`s only, with no `discriminator` object: plain JSON Schema. For consumers that
   * should not depend on the OpenAPI-specific `discriminator` keyword. `openapi-generator-cli`
   * (`typescript-fetch`) still generates a union of the subclass types from it, but deserializes by
   * trying each branch's shape in turn instead of switching on the discriminator value.
   */
  ONE_OF,
}
