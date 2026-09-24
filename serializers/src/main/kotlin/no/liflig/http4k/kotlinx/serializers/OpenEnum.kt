package no.liflig.http4k.kotlinx.serializers

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind

/**
 * Marks the descriptor built by [OpenEnumSerializer] as an open enum, carrying the serial name of
 * its fallback entry.
 *
 * Only [OpenEnumSerializer] creates it, and it is never written on source: the empty [Target]
 * rejects it everywhere, so it cannot be put on a non-enum, or on an enum whose serializer does not
 * actually fall back. Readers ask through [openEnumFallback] instead of seeing this type.
 *
 * It lives in the descriptor's `annotations` rather than being a descriptor subclass because that
 * member is stable public API that nullable wrappers delegate, whereas implementing
 * [SerialDescriptor] is reserved by kotlinx.serialization (`@SubclassOptInRequired`).
 */
@Target
@Retention(AnnotationRetention.RUNTIME)
internal annotation class OpenEnum(val fallback: String)

/**
 * The serial name of the fallback entry when this descriptor belongs to an [OpenEnumSerializer],
 * otherwise `null`. Sees through nullable wrappers, so `IdentityType?` answers like `IdentityType`.
 */
@OptIn(ExperimentalSerializationApi::class)
fun SerialDescriptor.openEnumFallback(): String? =
    if (kind == SerialKind.ENUM) annotations.firstNotNullOfOrNull { (it as? OpenEnum)?.fallback }
    else null
