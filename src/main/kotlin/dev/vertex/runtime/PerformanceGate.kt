package dev.vertex.runtime

data class PerformanceBaseline(val p50Micros: Long, val p99Micros: Long) {
    init { require(p50Micros > 0 && p99Micros > 0) }
    fun encode() = "$p50Micros,$p99Micros"

    companion object {
        fun decode(value: String): PerformanceBaseline? = value.trim().split(',').let { fields ->
            if (fields.size != 2) null else fields[0].toLongOrNull()?.let { p50 ->
                fields[1].toLongOrNull()?.let { p99 -> runCatching { PerformanceBaseline(p50, p99) }.getOrNull() }
            }
        }
    }
}

data class PerformanceRegression(val p50Percent: Double, val p99Percent: Double) {
    val worstPercent get() = maxOf(p50Percent, p99Percent)
}

object PerformanceGate {
    fun compare(baseline: PerformanceBaseline, current: Percentiles, thresholdPercent: Double = 3.0): PerformanceRegression? {
        require(thresholdPercent >= 0 && current.samples > 0)
        fun delta(value: Long, base: Long) = (value.toDouble() / base - 1.0) * 100.0
        val result = PerformanceRegression(delta(current.p50Micros, baseline.p50Micros), delta(current.p99Micros, baseline.p99Micros))
        val limit = 1.0 + thresholdPercent / 100.0
        return result.takeIf {
            current.p50Micros > baseline.p50Micros * limit || current.p99Micros > baseline.p99Micros * limit
        }
    }
}
