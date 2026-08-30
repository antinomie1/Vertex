package dev.vertex.frontend

import net.minecraft.world.level.block.state.BlockState
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Resolves a shader-pack block.properties material id for a vanilla block state. */
class BlockMaterialMap private constructor(private val rules: List<Rule>) {
    private val cache = ConcurrentHashMap<BlockState, Int>()
    val size: Int get() = rules.size

    fun id(state: BlockState): Int = cache.computeIfAbsent(state) { value ->
        rules.firstOrNull { it.matches(value) }?.id ?: 0
    }

    data class Rule(val id: Int, val block: String, val properties: Map<String, String>) {
        fun matches(state: BlockState): Boolean {
            val name = state.block.builtInRegistryHolder().key().identifier().toString()
            if (name != block) return false
            return properties.all { (property, expected) ->
                expected == "*" || state.getValues().anyMatch { value ->
                    value.property().getName() == property && value.valueName() == expected
                }
            }
        }
    }

    companion object {
        fun empty() = BlockMaterialMap(emptyList())

        fun load(path: Path): BlockMaterialMap {
            if (!Files.isRegularFile(path)) return empty()
            val source = Files.readString(path).replace("\r", "")
            val modern = source.substringAfter("#if MC_VERSION", source)
                .substringBefore("#elif", source)
                .substringBefore("#else", source)
                .substringBefore("#endif", source)
            val properties = Properties().apply { modern.reader().use(::load) }
            val rules = properties.stringPropertyNames().sorted().flatMap { key ->
                val id = key.removePrefix("block.").toIntOrNull() ?: return@flatMap emptyList()
                properties.getProperty(key).trim().split(Regex("\\s+")).mapNotNull { token ->
                    val parts = token.split(':')
                    if (parts.size < 2) return@mapNotNull null
                    val constraints = parts.drop(2).mapNotNull { part ->
                        part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                    }.toMap()
                    Rule(id, "${parts[0]}:${parts[1]}", constraints)
                }
            }
            return BlockMaterialMap(rules)
        }
    }
}
