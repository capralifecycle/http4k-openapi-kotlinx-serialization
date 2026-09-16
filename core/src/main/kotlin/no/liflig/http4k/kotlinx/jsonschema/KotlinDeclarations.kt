package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties

/*
 * Lookups against Kotlin declarations for what a [kotlinx.serialization.descriptors.SerialDescriptor]
 * cannot provide: generic type arguments, annotations that are not `@SerialInfo`, and sealed
 * subclass sets.
 */

/**
 * Resolves the Kotlin property backing a serialized element name. Matches both the Kotlin property
 * name and any `@SerialName` annotation on the property, since the serialized name may differ from
 * the Kotlin name.
 *
 * The property carries two things the descriptor cannot: its [KType], used to thread generic type
 * arguments through the walk, and its annotations, used to detect [Deprecated] and [Description].
 */
internal fun resolveProperty(ownerKClass: KClass<*>?, elementName: String): KProperty1<*, *>? =
    ownerKClass?.memberProperties?.find { prop ->
      prop.name == elementName ||
          prop.annotations.filterIsInstance<kotlinx.serialization.SerialName>().any {
            it.value == elementName
          }
    }

/**
 * Loads the [KClass] named by a descriptor's `serialName`.
 *
 * Last resort when no [KType] reached this point in the walk. The root example's type covers the
 * collection-passed-straight-to-`toSchema` case and properties carry their own [KType], so what
 * remains is a property whose declared type is a generic type parameter: the classifier is a
 * `KTypeParameter` rather than a [KClass], and the concrete argument was erased upstream.
 *
 * Returns `null` when the name is not a loadable class, in which case the caller degrades to
 * descriptor-only information — as it did everywhere before this fallback existed. Any class-level
 * `@SerialName` falls here.
 *
 * **Known limitation.** A `@SerialName` that *is* a loadable class name resolves — to that class,
 * not to the one being rendered — so its property annotations are read instead. Nothing available
 * here distinguishes the two: the alias is, by construction, the other class's serial name. This
 * requires one `@Serializable` type to alias another's fully-qualified name, and such a pair
 * already collides in [DefinitionRegistry], which keys definitions by serial name too. See
 * `KotlinxSerializationJsonSchemaCreatorTest`.
 */
internal fun loadKClass(serialName: String): KClass<*>? =
    try {
      // initialize = false: this is a best-effort metadata lookup, and running a consumer's
      // static initialisers as a side effect of rendering documentation is not worth the risk.
      Class.forName(
              serialName.removeSuffix("?"),
              false,
              KotlinxSerializationJsonSchemaCreator::class.java.classLoader,
          )
          .kotlin
    } catch (_: ClassNotFoundException) {
      null
    } catch (_: LinkageError) {
      // A half-resolvable class is still just a failed lookup here — degrade to
      // descriptor-only information rather than failing the whole document.
      null
    }

/** Resolves the inner type of an inline value class. */
internal fun resolveInlineInnerType(kType: KType?): KType? {
  val kClass = kType?.classifier as? KClass<*> ?: return null
  return kClass.memberProperties.firstOrNull()?.returnType
}

/** Recursively collects all concrete (non-sealed) subclasses from a sealed hierarchy. */
internal fun collectLeafSubclasses(klass: KClass<*>): List<KClass<*>> =
    klass.sealedSubclasses.flatMap { sub ->
      if (sub.isSealed) collectLeafSubclasses(sub) else listOf(sub)
    }

/**
 * True when [property] carries `@Deprecated`. Checks the getter's annotations as well as the
 * property's own, since the `@get:Deprecated` use-site form lands on the getter — `Deprecated`
 * permits `PROPERTY_GETTER` as a target.
 */
internal fun isDeprecated(property: KProperty1<*, *>?): Boolean =
    property != null &&
        (property.annotations + property.getter.annotations).any { it is Deprecated }

/**
 * The [Description] declared on [property] itself or on its getter, so the `@get:Description`
 * use-site form works, as it does for [Deprecated].
 */
internal fun descriptionOn(property: KProperty1<*, *>): String? =
    (property.annotations + property.getter.annotations)
        .filterIsInstance<Description>()
        .firstOrNull()
        ?.value

/** The [Description] declared on [kClass], if any. */
internal fun descriptionOn(kClass: KClass<*>?): String? =
    kClass?.annotations?.filterIsInstance<Description>()?.firstOrNull()?.value
