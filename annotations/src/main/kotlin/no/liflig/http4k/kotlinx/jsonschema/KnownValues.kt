package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KClass

/**
 * Lists the values of an open set that this version of the API knows, rendering as `"examples"` on
 * that property. The property keeps its own type in the schema, so every other value stays valid:
 * ```
 * @Serializable
 * data class Rejection(@KnownValues(RejectionReason.Companion::class) val reason: String)
 * ```
 *
 * That renders `"reason": {"type": "string", "examples": ["OUT_OF_STOCK", "KITCHEN_CLOSED"]}`.
 *
 * **Why an open set.** A JSON Schema `enum` is a closed set: it tells a client that no other value
 * can occur. A client generated from it, or one that decodes with the producer's enum class, then
 * fails on a value that the producer adds later, so each new value is a breaking change. For a set
 * that grows, such as reason codes and error codes, the producer can make that promise only by
 * never adding a value. An open set makes the opposite promise: values are added, and a known value
 * is not removed, renamed or given another meaning. The client must have a fallback for a value it
 * does not know, and the property's [Description] is the place to say what that fallback is.
 *
 * **Why `examples`.** OpenAPI has no construct for an open enum. `examples` is a standard JSON
 * Schema annotation keyword in OpenAPI 3.1: validators ignore it, and a new value shows as a change
 * in the rendered document. The other encodings in use have costs: vendor extensions such as
 * `x-extensible-enum` are read only by the tools of that vendor, and `openapi-generator`'s
 * `typescript-fetch` renders `anyOf` of an `enum` and a `string` as an empty interface.
 *
 * **OpenAPI 3.1 only.** The Schema Object of OpenAPI 3.0 has `example` but not `examples`, so a
 * document rendered as 3.0 holds a keyword that 3.0 does not define.
 *
 * **When to use which.** Use an enum class or [AllowedValues] for a set that is fixed, for input
 * that the service validates, and for a value that a client must handle case by case. Use
 * [KnownValues] for a response value that a client can show or pass on without knowing it.
 * - **Property types:** as for [AllowedValues], any that serializes as a JSON primitive. Declare
 *   the property as `String` (or a value class over it), not as the enum class: a consumer that
 *   decodes with the producer's classes must be able to read a value the enum does not have.
 * - **Nullable properties:** `null` is not added to `examples`; the property's type admits it.
 * - **Placement:** on the concrete class's property, or its getter with `@get:KnownValues`.
 * - **Checked at schema generation:** as for [AllowedValues], the schema creator throws
 *   [IllegalArgumentException] naming the property when the provider is not an `object`, is empty,
 *   or holds values of another type, and when the property's type is unsupported. It also throws
 *   when a property has [KnownValues] and [AllowedValues], which contradict each other. An example
 *   value outside the known values is accepted, because the set is open.
 * - **Property-level serializers:** each value is encoded with the serializer of the property's
 *   type. A `@Serializable(with = ...)` on the property that keeps the JSON type but writes another
 *   value is not detected, and the values then render as the type's serializer writes them.
 *
 * A plain runtime annotation, like [AllowedValues], so this module stays free of dependencies
 * beyond `kotlin-stdlib`.
 */
@Target(
    AnnotationTarget.PROPERTY,
    // Permits the `@get:KnownValues` use-site form, as with `@AllowedValues`.
    AnnotationTarget.PROPERTY_GETTER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class KnownValues(val provider: KClass<out KnownValuesProvider<*>>)
