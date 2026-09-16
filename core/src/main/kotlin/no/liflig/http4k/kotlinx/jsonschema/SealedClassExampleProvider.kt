package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KClass
import kotlin.reflect.full.*

interface SealedClassExampleProvider {
  fun getExamples(sealedClass: KClass<*>): List<Any>
}

/**
 * Collects examples from every leaf of a sealed hierarchy: the instance itself for objects, else
 * the companion's `example` property. Intermediate sealed levels are walked through, not read.
 */
class DefaultSealedClassExampleProvider : SealedClassExampleProvider {
  override fun getExamples(sealedClass: KClass<*>): List<Any> =
      sealedClass.sealedSubclasses.flatMap { subclass ->
        if (subclass.isSealed) getExamples(subclass) else listOfNotNull(leafExample(subclass))
      }

  private fun leafExample(subclass: KClass<*>): Any? {
    subclass.objectInstance?.let {
      return it
    }
    val companion = subclass.companionObjectInstance ?: return null
    return try {
      companion::class.memberProperties.find { it.name == "example" }?.getter?.call(companion)
    } catch (e: Exception) {
      null
    }
  }
}
