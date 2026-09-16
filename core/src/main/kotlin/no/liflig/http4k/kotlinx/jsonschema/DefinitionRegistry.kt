package no.liflig.http4k.kotlinx.jsonschema

import org.http4k.contract.jsonschema.JsonSchema
import org.http4k.format.Json
import org.http4k.format.JsonType

/**
 * Collects the named definitions one [SchemaWalk] produces and decides the key each is published
 * under. Created per `toSchema` call.
 *
 * During the walk every definition is keyed, and every `$ref` written, by its *identity*: the
 * serial name, or for sealed hierarchies the qualified class name, which is unique by construction.
 * [finish] then assigns the published names in one pass — the short name where it is uncontested,
 * the dotted identity with `_` where two identities share a short name, the caller's override id
 * for the root — and rewrites every ref to match. Naming once, after the walk, means no ref is ever
 * written against a name that a later collision would have to take back.
 */
internal class DefinitionRegistry<NODE : Any>(
    private val json: Json<NODE>,
    private val refLocationPrefix: String,
    private val refModelNamePrefix: String?,
) {
  private class Entry<NODE>(val shortName: String, val schema: NODE)

  /** Definitions by identity, in the order their schemas finished building. */
  private val entries = LinkedHashMap<String, Entry<NODE>>()

  /** Identities whose schema is being built further up the stack. */
  private val inProgress = mutableSetOf<String>()

  /** The JSON pointer to the definition for [identity], as written during the walk. */
  fun refPath(identity: String): String = "#/$refLocationPrefix/$identity"

  /** A `$ref` node pointing at the definition for [identity]. */
  fun ref(identity: String): NODE = json.obj("\$ref" to json.string(refPath(identity)))

  /**
   * A `$ref` to the definition for [identity], building and registering its schema first unless
   * that is already done or under way. The second case is a recursive type referring back to
   * itself, and the ref is what breaks the cycle.
   */
  fun define(identity: String, shortName: String, build: () -> NODE): NODE {
    if (identity !in entries && inProgress.add(identity)) {
      entries[identity] = Entry(shortName, build())
      inProgress.remove(identity)
    }
    return ref(identity)
  }

  /** Names every definition, rewrites the refs in [rootNode] and the definitions to match. */
  fun finish(rootNode: NODE, overrideDefinitionId: String?): JsonSchema<NODE> {
    val names = publishedNames(rootNode, overrideDefinitionId)
    val pathRenames =
        names
            .mapKeys { (identity, _) -> refPath(identity) }
            .mapValues { (_, name) -> "#/$refLocationPrefix/$name" }
    val definitions =
        entries.entries.associateTo(LinkedHashMap()) { (identity, entry) ->
          names.getValue(identity) to rewriteRefPaths(entry.schema, pathRenames)
        }
    return JsonSchema(rewriteRefPaths(rootNode, pathRenames), definitions)
  }

  private fun publishedNames(rootNode: NODE, overrideDefinitionId: String?): Map<String, String> {
    val contested = entries.values.groupingBy { it.shortName }.eachCount().filterValues { it > 1 }
    val names =
        entries.mapValuesTo(LinkedHashMap()) { (identity, entry) ->
          val name =
              if (entry.shortName in contested) identity.replace('.', '_') else entry.shortName
          refModelNamePrefix.orEmpty() + name
        }
    if (overrideDefinitionId != null) {
      // Only a root that is itself a definition can be renamed; an inline root (primitive,
      // array, map) has nothing to rename.
      rootIdentity(rootNode)?.let {
        names[it] = refModelNamePrefix.orEmpty() + overrideDefinitionId
      }
    }
    return names
  }

  private fun rootIdentity(rootNode: NODE): String? =
      json
          .fields(rootNode)
          .firstOrNull { (name, _) -> name == "\$ref" }
          ?.let { (_, path) -> json.text(path).removePrefix("#/$refLocationPrefix/") }
          ?.takeIf { it in entries }

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
        JsonType.String -> pathRenames[json.text(node)]?.let { json.string(it) } ?: node
        else -> node
      }
}
