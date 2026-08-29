package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EpochStateTest {
    @Test
    fun `global invalidation advances epoch and stales every slot`() {
        val state = EpochState(2)
        state.markRecorded(0)
        state.markRecorded(1)
        assertFalse(state.needsRecording(0))

        assertEquals(1, state.invalidateAll())
        assertTrue(state.needsRecording(0))
        assertTrue(state.needsRecording(1))
        assertFailsWith<IllegalArgumentException> { state.markRecorded(0, recordedEpoch = 0) }
    }

    @Test
    fun `local dirty event affects one in-flight slot`() {
        val state = EpochState(2)
        state.markRecorded(0)
        state.markRecorded(1)
        state.invalidateSlot(1)
        assertFalse(state.needsRecording(0))
        assertTrue(state.needsRecording(1))
    }
}
