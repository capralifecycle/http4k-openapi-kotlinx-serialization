package no.liflig.http4k.kotlinx.jsonschema

/**
 * A named set of values a property may hold, referenced from [AllowedValues].
 *
 * Implement it as an `object`. The schema creator reads [values] from that instance to render the
 * property's `"enum"`, and the same object enforces the constraint at runtime through
 * [requireAllowed] or `in`, so the documented set and the checked set cannot drift apart. See
 * [AllowedValues] for a full example.
 *
 * [T] is the property's Kotlin type, not nullable even for a nullable property: a primitive, a
 * `String`, or a value class or contextual type that serializes as one.
 */
interface AllowedValuesProvider<T : Any> {
  /** The allowed values, rendered in iteration order. */
  val values: Set<T>

  operator fun contains(value: T): Boolean = value in values

  /**
   * Returns [value], or throws [IllegalArgumentException] naming [propertyName] when it is not
   * allowed. Called from an `init` block, it turns a disallowed value into a deserialization
   * failure.
   */
  fun requireAllowed(value: T, propertyName: String): T {
    require(value in values) { "'$propertyName' must be one of $values, but was $value" }
    return value
  }
}
