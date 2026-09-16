package no.liflig.http4k.kotlinx.jsonschema

import org.http4k.contract.jsonschema.JsonSchema
import org.http4k.format.Json
import org.http4k.format.JsonType

/**
 * Collects the named definitions one [SchemaWalk] produces and decides the key each is published
 * under, resolving short-name collisions and the caller's `overrideDefinitionId`. Created per
 * `toSchema` call.
 */
internal class DefinitionRegistry<NODE : Any>(
    private val json: Json<NODE>,
    private val refLocationPrefix: String,
    private val refModelNamePrefix: String?,
) {
  private val schemas: MutableMap<String, NODE> = mutableMapOf()
  private val serialNames: MutableMap<String, String> = mutableMapOf()

  /**
   * Tracks definitions that were renamed mid-walk due to short-name collisions. Maps the *original*
   * definition key (what already-emitted refs are pointing at) to the *new* key. Applied as a
   * post-walk sweep in [finish] so stale `$ref`s are rewritten consistently across [schemas] and
   * the root node.
   */
  private val collisionRenames: MutableMap<String, String> = mutableMapOf()

  private val visited = mutableSetOf<String>()

  /** The JSON pointer to the definition published as [defName]. */
  fun refPath(defName: String): String = "#/$refLocationPrefix/$defName"

  /** A `$ref` node pointing at the definition published as [defName]. */
  fun ref(defName: String): NODE = json.obj("\$ref" to json.string(refPath(defName)))

  /** True when a class with this serial name is already being, or has been, rendered. */
  fun alreadyVisited(serialName: String): Boolean = visited.contains(serialName)

  fun markVisited(serialName: String) {
    visited.add(serialName)
  }

  /**
   * Registers [schema] under a key derived from [shortName], or returns the existing key when the
   * same [serialName] was already registered. A different serial name already holding the short
   * name is renamed to its full form, and the new one takes its full form too.
   */
  fun add(serialName: String, shortName: String, schema: () -> NODE): String {
    val existingKey = findKey(serialName, shortName)

    if (existingKey != null) {
      val existingSerialName = serialNames[existingKey]
      if (existingSerialName != serialName) {
        val oldFullName = existingSerialName?.replace('.', '_') ?: existingKey
        val oldPrefixedName = refModelNamePrefix?.let { "$it$oldFullName" } ?: oldFullName
        val existing =
            schemas.remove(existingKey)
                ?: throw IllegalStateException(
                    "Expected definition '$existingKey' not found during collision resolution"
                )
        schemas[oldPrefixedName] = existing
        serialNames.remove(existingKey)
        serialNames[oldPrefixedName] = existingSerialName ?: existingKey
        // Record the rename so any `$ref` emitted earlier (pointing at existingKey)
        // can be rewritten to oldPrefixedName by the post-walk sweep in finish.
        if (existingKey != oldPrefixedName) {
          collisionRenames[existingKey] = oldPrefixedName
        }

        val newFullName = serialName.replace('.', '_')
        val newPrefixedName = refModelNamePrefix?.let { "$it$newFullName" } ?: newFullName
        schemas[newPrefixedName] = schema()
        serialNames[newPrefixedName] = serialName
        return newPrefixedName
      } else {
        return existingKey
      }
    } else {
      val defName = refModelNamePrefix?.let { "$it$shortName" } ?: shortName
      schemas[defName] = schema()
      serialNames[defName] = serialName
      return defName
    }
  }

  /** The key a class already visited is, or will be, published under. */
  fun existingName(serialName: String, shortName: String): String =
      findKey(serialName, shortName) ?: (refModelNamePrefix?.let { "$it$shortName" } ?: shortName)

  private fun findKey(serialName: String, shortName: String): String? =
      schemas.keys.find { key ->
        val strippedKey = refModelNamePrefix?.let { key.removePrefix(it) } ?: key
        strippedKey == shortName || strippedKey == serialName.replace('.', '_')
      }

  /**
   * Applies the pending collision renames and, when given, the override id to the root definition,
   * then packages the root [node] with every definition.
   */
  fun finish(node: NODE, overrideDefinitionId: String?): JsonSchema<NODE> {
    val renamed = applyCollisionRenames(node)
    return if (overrideDefinitionId != null) {
      applyOverrideDefinitionId(renamed, overrideDefinitionId)
    } else {
      JsonSchema(renamed, schemas)
    }
  }

  private fun applyCollisionRenames(node: NODE): NODE {
    if (collisionRenames.isEmpty()) return node
    val pathRenames =
        collisionRenames.entries.associate { (oldKey, newKey) ->
          "#/$refLocationPrefix/$oldKey" to "#/$refLocationPrefix/$newKey"
        }
    val rewrittenDefs = schemas.mapValues { (_, v) -> rewriteRefPaths(v, pathRenames) }
    schemas.clear()
    schemas.putAll(rewrittenDefs)
    collisionRenames.clear()
    return rewriteRefPaths(node, pathRenames)
  }

  private fun applyOverrideDefinitionId(
      node: NODE,
      overrideDefinitionId: String,
  ): JsonSchema<NODE> {
    val refField = json.fields(node).firstOrNull { (k, _) -> k == "\$ref" }
    if (refField == null) {
      // Inline schema (primitives, arrays, maps) — no definition to rename.
      return JsonSchema(node, schemas)
    }

    val originalRefPath = json.text(refField.second)
    val originalDefKey = originalRefPath.removePrefix("#/$refLocationPrefix/")
    val newDefKey = (refModelNamePrefix ?: "") + overrideDefinitionId

    if (originalDefKey == newDefKey) {
      return JsonSchema(node, schemas)
    }

    val newRefPath = "#/$refLocationPrefix/$newDefKey"
    val pathRenames = mapOf(originalRefPath to newRefPath)

    // Move the renamed definition under the new key, then rewrite every inner $ref
    // so recursive self-references and cross-definition refs stay consistent.
    val moved = schemas.remove(originalDefKey)
    serialNames.remove(originalDefKey)
    val rewrittenDefs = schemas.mapValues { (_, v) -> rewriteRefPaths(v, pathRenames) }
    schemas.clear()
    schemas.putAll(rewrittenDefs)
    if (moved != null) {
      schemas[newDefKey] = rewriteRefPaths(moved, pathRenames)
    }

    return JsonSchema(ref(newDefKey), schemas)
  }

  /**
   * Recursively walks [node] and rewrites every string whose content exactly matches one of the old
   * paths in [pathRenames] to the corresponding new path. Covers both `$ref` values and OpenAPI
   * `discriminator.mapping` values (which are paths under arbitrary keys, not under `$ref`).
   * Exact-match-only, so an unrelated description string can't be rewritten by accident.
   */
  private fun rewriteRefPaths(node: NODE, pathRenames: Map<String, String>): NODE =
      when (json.typeOf(node)) {
        JsonType.Object ->
            json.obj(json.fields(node).map { (k, v) -> k to rewriteRefPaths(v, pathRenames) })
        JsonType.Array -> json.array(json.elements(node).map { rewriteRefPaths(it, pathRenames) })
        JsonType.String -> {
          val text = json.text(node)
          if (pathRenames.containsKey(text)) json.string(pathRenames.getValue(text)) else node
        }
        else -> node
      }
}
