package no.liflig.http4k.kotlinx.jsonschema

import kotlin.reflect.KClass
import kotlin.reflect.full.*

/**
 * Supplies example instances for the leaves of a sealed hierarchy, from which the schema walker
 * fills in `example` values on each subclass definition.
 *
 * Each returned example is matched to its subclass by the serial name of the example's own runtime
 * class, not by position in the list or by the [getExamples] argument. Examples may come in any
 * order and cover any subset of leaves; a leaf without one renders without example values.
 */
interface SealedClassExampleProvider {
  /**
   * Example instances for the leaves reachable from [sealedClass], through nested sealed levels.
   */
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
