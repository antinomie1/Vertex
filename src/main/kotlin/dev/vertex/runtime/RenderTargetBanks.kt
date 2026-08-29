package dev.vertex.runtime

/** Deterministic two-bank state for shader-pack render targets. */
class RenderTargetBanks(private val size: Int = 16) {
    init { require(size > 0) }

    private val values = IntArray(size)

    operator fun get(id: Int): Int = values[checked(id)]
    operator fun set(id: Int, bank: Int) {
        require(bank in 0..1) { "bank must be 0 or 1" }
        values[checked(id)] = bank
    }

    fun reset() = values.fill(0)

    /** Outputs write the opposite bank; flip=false exposes the existing bank, otherwise the new bank. */
    fun commit(outputs: Iterable<Int>, flips: Map<Int, Boolean>) {
        outputs.toSet().forEach { id -> if (flips[id] != false) values[checked(id)] = values[id] xor 1 }
    }

    private fun checked(id: Int): Int {
        require(id in values.indices) { "render target $id outside 0..${values.lastIndex}" }
        return id
    }
}
