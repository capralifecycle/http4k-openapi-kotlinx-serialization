package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KClass

/**
 * Restricts a property to the values of [provider], rendering as `"enum"` on that property.
 *
 * Useful when the allowed values depend on where the property sits rather than on its type: the
 * same `pieces: Int` can allow `6, 8, 12` on one sealed subclass and `5, 10, 15` on another, which
 * a Kotlin type cannot express.
 *
 * Declare the values in an [AllowedValuesProvider] `object`, reference it here, and call the
 * provider from `init` so a disallowed value fails deserialization — the annotation only documents
 * the constraint; it does not enforce it:
 * ```
 * object SushiPieces : AllowedValuesProvider<Int> {
 *   override val values = setOf(6, 8, 12)
 * }
 *
 * @Serializable
 * data class SushiOrder(@AllowedValues(SushiPieces::class) val pieces: Int) {
 *   init {
 *     SushiPieces.requireAllowed(pieces, "pieces")
 *   }
 * }
 * ```
 *
 * That renders `"pieces": {"type": "integer", "format": "int32", "enum": [6, 8, 12]}`.
 * - **Property types:** any that serializes as a JSON primitive — `Int`, `Long`, `Short`, `Byte`,
 *   `Double`, `Float`, `Boolean`, `String`, `Char`, unsigned types, and value classes or contextual
 *   types over them. Each value is encoded with the property's serializer, so it renders as the
 *   property is written on the wire. Enum classes, collections and objects are not supported.
 * - **Nullable properties:** `null` is added to the `enum`; the provider's values stay non-null.
 * - **Placement:** on the concrete class's property, or its getter with `@get:AllowedValues`. An
 *   annotation on an interface's abstract `val` is not inherited by overrides.
 * - **Checked at schema generation:** the compiler cannot match the provider's type to the
 *   property's, so the schema creator does. It throws [IllegalArgumentException] naming the
 *   property when the provider is not an `object`, is empty, or holds values of another type, when
 *   the property's type is unsupported, or when the example uses a value the constraint rejects.
 *
 * The values live in a provider rather than in the annotation itself because annotation parameters
 * cannot be generic: a class reference is the only way one annotation can carry typed values of
 * every type, and the same object then serves as the runtime check, so the two cannot drift apart.
 *
 * A plain runtime annotation rather than a kotlinx `@SerialInfo` one, so this module stays free of
 * dependencies beyond `kotlin-stdlib`.
 */
@Target(
    AnnotationTarget.PROPERTY,
    // Permits the `@get:AllowedValues` use-site form, read alongside the property's own
    // annotations, as with `@Description`.
    AnnotationTarget.PROPERTY_GETTER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class AllowedValues(val provider: KClass<out AllowedValuesProvider<*>>)
