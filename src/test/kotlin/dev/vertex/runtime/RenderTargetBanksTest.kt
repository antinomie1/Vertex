package dev.vertex.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class RenderTargetBanksTest {
    @Test
    fun `default output exposes new bank exactly once`() {
        val banks = RenderTargetBanks()
        banks.commit(listOf(0), emptyMap())
        assertEquals(1, banks[0])
        banks.commit(listOf(0), mapOf(0 to true))
        assertEquals(0, banks[0])
    }

    @Test
    fun `explicit false keeps previous bank`() {
        val banks = RenderTargetBanks()
        banks.commit(listOf(2), mapOf(2 to false))
        assertEquals(0, banks[2])
    }

    @Test
    fun `duplicate output declarations cannot double flip`() {
        val banks = RenderTargetBanks()
        banks.commit(listOf(3, 3), emptyMap())
        assertEquals(1, banks[3])
    }

    @Test
    fun `duplicate tracking supports render target tables wider than one word`() {
        val banks = RenderTargetBanks(40)
        banks.commit(listOf(31, 32, 39, 32), emptyMap())
        assertEquals(1, banks[31])
        assertEquals(1, banks[32])
        assertEquals(1, banks[39])
    }
}
