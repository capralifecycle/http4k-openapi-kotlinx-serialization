package no.liflig.http4k.kotlinx.jsonschema

/**
 * Documents a property or a type in the generated schema, rendering as `"description"`.
 *
 * On a property it describes that field. On a type it describes every use of that type, which is
 * how a shared vocabulary type — a value class, or one with a custom serializer — gets described
 * once rather than at each field that holds it. A property's own description wins over its type's.
 *
 * An annotation is needed because KDoc does not survive compilation, and because KDoc is written
 * for maintainers: what a type means to a caller of the API is a separate decision from what the
 * next developer needs to know about it.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.PROPERTY,
    // Permits the `@get:Description` use-site form, which `@Deprecated` also allows and which the
    // walker reads alongside the property's own annotations.
    AnnotationTarget.PROPERTY_GETTER,
)
@Retention(AnnotationRetention.RUNTIME)
annotation class Description(val value: String)
