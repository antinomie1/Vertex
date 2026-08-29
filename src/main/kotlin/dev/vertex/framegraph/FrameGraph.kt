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
    val fullscreen: Boolean = false,
) {
    init { require(id.isNotBlank()); require((reads intersect writes).isEmpty()) { "$id reads and writes the same resource" } }
}

data class Dependency(val producer: Int, val consumer: Int, val resource: String)

data class BarrierGroup(val producer: Int, val consumer: Int, val resources: List<String>)

data class ResourceLifetime(val start: Int, val end: Int) {
    init { require(start >= 0 && end >= start) }
}

data class CompiledFrameGraph(
    val nodes: List<FrameNode>,
    val dependencies: List<Dependency>,
    val aliasGroups: List<List<String>>,
    val lifetimes: Map<String, ResourceLifetime> = emptyMap(),
    val barrierGroups: List<BarrierGroup> = emptyList(),
    val fusionGroups: List<List<String>> = emptyList(),
)

/** Compiles a declared execution order into true dependencies and safe transient aliases. */
object FrameGraphCompiler {
    fun compile(resources: List<ResourceSpec>, nodes: List<FrameNode>): CompiledFrameGraph {
        require(resources.map { it.id }.distinct().size == resources.size) { "duplicate resource id" }
        require(nodes.map { it.id }.distinct().size == nodes.size) { "duplicate node id" }
        val specs = resources.associateBy { it.id }
        val writer = hashMapOf<String, Int>()
        val firstWriter = hashMapOf<String, Int>()
        val dependencies = mutableListOf<Dependency>()

        nodes.forEachIndexed { index, node ->
            (node.reads + node.writes).forEach { require(it in specs) { "${node.id}: unknown resource '$it'" } }
            node.reads.toList().sorted().forEach { resource ->
                val producer = writer[resource]
                require(producer != null || specs.getValue(resource).external) {
                    "${node.id}: '$resource' is read before it is written"
                }
                if (producer != null) dependencies += Dependency(producer, index, resource)
            }
            node.writes.toList().sorted().forEach { resource ->
                require(!specs.getValue(resource).external) { "${node.id}: cannot overwrite external '$resource'" }
                writer[resource]?.let { dependencies += Dependency(it, index, resource) }
                firstWriter.putIfAbsent(resource, index)
                writer[resource] = index
            }
        }

        val lifetimes = resources.filterNot(ResourceSpec::external).mapNotNull { resource ->
            val start = firstWriter[resource.id] ?: return@mapNotNull null
            val end = nodes.indices.lastOrNull { resource.id in nodes[it].reads || resource.id in nodes[it].writes } ?: start
            Lifetime(resource, start, end)
        }
        val barriers = dependencies.groupBy { it.producer to it.consumer }.map { (edge, values) ->
            BarrierGroup(edge.first, edge.second, values.map(Dependency::resource).distinct().sorted())
        }
        return CompiledFrameGraph(
            nodes,
            dependencies,
            alias(lifetimes),
            lifetimes.associate { it.resource.id to ResourceLifetime(it.start, it.end) },
            barriers,
            fusionGroups(resources.associateBy(ResourceSpec::id), nodes),
        )
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

    /** Conservative desktop-safe fusion: only adjacent marked fullscreen passes with a unique edge. */
    private fun fusionGroups(specs: Map<String, ResourceSpec>, nodes: List<FrameNode>): List<List<String>> {
        val consumers = nodes.flatMapIndexed { index, node ->
            node.reads.map { it to index }
        }.groupBy({ it.first }, { it.second })
        val groups = mutableListOf<MutableList<String>>()
        nodes.forEachIndexed { index, node ->
            val previous = nodes.getOrNull(index - 1)
            val edge = previous?.writes?.singleOrNull()
            val canFuse = previous != null && previous.fullscreen && node.fullscreen && edge != null &&
                consumers[edge] == listOf(index) && node.reads.contains(edge) &&
                outputsMatch(specs, previous, node)
            if (canFuse) groups.last().add(node.id) else groups += mutableListOf(node.id)
        }
        return groups
    }

    private fun outputsMatch(specs: Map<String, ResourceSpec>, first: FrameNode, second: FrameNode): Boolean {
        val a = first.writes.singleOrNull()?.let(specs::get) ?: return false
        val b = second.writes.firstOrNull()?.let(specs::get) ?: return true
        return a.width == b.width && a.height == b.height && a.format == b.format
    }

    private data class Lifetime(val resource: ResourceSpec, val start: Int, val end: Int)
}
