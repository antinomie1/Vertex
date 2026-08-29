package dev.vertex.framegraph

enum class NodeCadence { AMORTIZED, PER_FRAME }

data class ResourceSpec(
    val id: String,
    val width: Int,
    val height: Int,
    val format: String,
    val epoch: Int = 0,
    val external: Boolean = false,
) {
    init { require(width > 0 && height > 0); require(id.isNotBlank() && format.isNotBlank()) }
}

data class FrameNode(
    val id: String,
    val cadence: NodeCadence,
    val reads: Set<String> = emptySet(),
    val writes: Set<String> = emptySet(),
) {
    init { require(id.isNotBlank()); require((reads intersect writes).isEmpty()) { "$id reads and writes the same resource" } }
}

data class Dependency(val producer: Int, val consumer: Int, val resource: String)

data class ResourceLifetime(val start: Int, val end: Int) {
    init { require(start >= 0 && end >= start) }
}

data class CompiledFrameGraph(
    val nodes: List<FrameNode>,
    val dependencies: List<Dependency>,
    val aliasGroups: List<List<String>>,
    val lifetimes: Map<String, ResourceLifetime> = emptyMap(),
)

/** Compiles a declared execution order into true dependencies and safe transient aliases. */
object FrameGraphCompiler {
    fun compile(resources: List<ResourceSpec>, nodes: List<FrameNode>): CompiledFrameGraph {
        require(resources.map { it.id }.distinct().size == resources.size) { "duplicate resource id" }
        require(nodes.map { it.id }.distinct().size == nodes.size) { "duplicate node id" }
        val specs = resources.associateBy { it.id }
        val writer = hashMapOf<String, Int>()
        val dependencies = mutableListOf<Dependency>()

        nodes.forEachIndexed { index, node ->
            (node.reads + node.writes).forEach { require(it in specs) { "${node.id}: unknown resource '$it'" } }
            node.reads.forEach { resource ->
                val producer = writer[resource]
                require(producer != null || specs.getValue(resource).external) {
                    "${node.id}: '$resource' is read before it is written"
                }
                if (producer != null) dependencies += Dependency(producer, index, resource)
            }
            node.writes.forEach { resource ->
                require(resource !in writer) { "${node.id}: '$resource' already has a writer" }
                require(!specs.getValue(resource).external) { "${node.id}: cannot overwrite external '$resource'" }
                writer[resource] = index
            }
        }

        val lifetimes = resources.filterNot(ResourceSpec::external).mapNotNull { resource ->
            val start = writer[resource.id] ?: return@mapNotNull null
            val end = nodes.indices.lastOrNull { resource.id in nodes[it].reads } ?: start
            Lifetime(resource, start, end)
        }
        return CompiledFrameGraph(nodes, dependencies, alias(lifetimes), lifetimes.associate {
            it.resource.id to ResourceLifetime(it.start, it.end)
        })
    }

    private fun alias(lifetimes: List<Lifetime>): List<List<String>> {
        val groups = mutableListOf<MutableList<Lifetime>>()
        lifetimes.sortedBy { it.start }.forEach { candidate ->
            val group = groups.firstOrNull { existing ->
                existing.first().resource.compatible(candidate.resource) &&
                    existing.none { it.start <= candidate.end && candidate.start <= it.end }
            } ?: mutableListOf<Lifetime>().also(groups::add)
            group += candidate
        }
        return groups.map { group -> group.map { it.resource.id } }
    }

    private fun ResourceSpec.compatible(other: ResourceSpec): Boolean =
        width == other.width && height == other.height && format == other.format && epoch == other.epoch

    private data class Lifetime(val resource: ResourceSpec, val start: Int, val end: Int)
}
