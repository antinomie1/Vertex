package dev.vertex.runtime

/** Deterministic two-bank state for shader-pack render targets. */
class RenderTargetBanks(private val size: Int = 16) {
    init { require(size > 0) }

    private val values = IntArray(size)
    private val seenGeneration = IntArray(size)
    private var generation = 0

    operator fun get(id: Int): Int = values[checked(id)]
    operator fun set(id: Int, bank: Int) {
        require(bank in 0..1) { "bank must be 0 or 1" }
        values[checked(id)] = bank
    }

    fun reset() = values.fill(0)

    /** Outputs write the opposite bank; flip=false exposes the existing bank, otherwise the new bank. */
    fun commit(outputs: Iterable<Int>, flips: Map<Int, Boolean>) {
        val mark = nextGeneration()
        for (id in outputs) {
            val index = checked(id)
            if (seenGeneration[index] == mark) continue
            seenGeneration[index] = mark
            if (flips[id] != false) values[index] = values[index] xor 1
        }
    }

    private fun nextGeneration(): Int {
        if (++generation == 0) {
            seenGeneration.fill(0)
            generation = 1
        }
        return generation
    }

    private fun checked(id: Int): Int {
        require(id in values.indices) { "render target $id outside 0..${values.lastIndex}" }
        return id
    }
}
