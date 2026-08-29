package dev.vertex.runtime

enum class CommandBufferState { STALE, VALID }

/** Slot-local SSCA state; a slot may only become valid after it has been re-recorded. */
class EpochState(slotCount: Int = 2) {
    init { require(slotCount > 0) }

    private val states = Array(slotCount) { CommandBufferState.STALE }
    var epoch: Long = 0
        private set

    val slotCount: Int get() = states.size

    fun state(slot: Int): CommandBufferState = states[checked(slot)]

    fun markRecorded(slot: Int, recordedEpoch: Long = epoch) {
        require(recordedEpoch == epoch) {
            "cannot validate slot for stale epoch $recordedEpoch; current epoch is $epoch"
        }
        states[checked(slot)] = CommandBufferState.VALID
    }

    fun invalidateSlot(slot: Int) {
        states[checked(slot)] = CommandBufferState.STALE
    }

    fun invalidateAll(): Long {
        epoch++
        states.fill(CommandBufferState.STALE)
        return epoch
    }

    fun needsRecording(slot: Int): Boolean = state(slot) == CommandBufferState.STALE

    private fun checked(slot: Int): Int {
        require(slot in states.indices) { "slot $slot outside 0..${states.lastIndex}" }
        return slot
    }
}
