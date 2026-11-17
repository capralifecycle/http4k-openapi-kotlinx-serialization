package no.liflig.http4k.kotlinx.jsonschema

/**
 * The values of an open set that this version of the API knows, referenced from [KnownValues].
 *
 * Implement it as an `object`, usually the companion of the enum that the producer uses for the
 * set, so the published list is read from the same declaration as the code that writes the values:
 * ```
 * enum class RejectionReason {
 *   OUT_OF_STOCK,
 *   KITCHEN_CLOSED;
 *
 *   companion object : KnownValuesProvider<String> {
 *     override val values = entries.map { it.name }.toSet()
 *   }
 * }
 * ```
 *
 * [T] is the property's Kotlin type, not nullable even for a nullable property: a primitive, a
 * `String`, or a value class or contextual type that serializes as one.
 *
 * It has no runtime check, unlike [AllowedValuesProvider]: a value outside [values] is valid.
 */
interface KnownValuesProvider<T : Any> {
  /** The known values, rendered in iteration order. */
  val values: Set<T>
}
