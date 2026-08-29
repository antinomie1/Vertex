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
        var seen = 0
        for (id in outputs) {
            val index = checked(id)
            val bit = 1 shl index
            if (seen and bit != 0) continue
            seen = seen or bit
            if (flips[id] != false) values[index] = values[index] xor 1
        }
    }

    private fun checked(id: Int): Int {
        require(id in values.indices) { "render target $id outside 0..${values.lastIndex}" }
        return id
    }
}
