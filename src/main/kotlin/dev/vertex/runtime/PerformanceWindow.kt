package dev.vertex.runtime

import kotlin.math.ceil

data class Percentiles(val samples: Int, val p50Micros: Long, val p99Micros: Long)

/** Fixed-capacity hot-path recorder; sorting happens only when a report is requested. */
class PerformanceWindow(private val capacity: Int = 600) {
    init { require(capacity > 0) }
    private val values = LongArray(capacity)
    private var cursor = 0
    var size = 0
        private set

    fun record(micros: Long) {
        require(micros >= 0)
        values[cursor] = micros
        cursor = (cursor + 1) % capacity
        if (size < capacity) size++
    }

    fun snapshot(): Percentiles {
        require(size > 0)
        val sorted = values.copyOf(size).apply(LongArray::sort)
        fun percentile(value: Double) = sorted[ceil(size * value).toInt().coerceAtLeast(1) - 1]
        return Percentiles(size, percentile(0.50), percentile(0.99))
    }
}
