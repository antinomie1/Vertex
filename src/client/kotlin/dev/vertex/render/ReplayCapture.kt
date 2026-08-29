package dev.vertex.render

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.device.GpuDevice
import com.mojang.renderpearl.api.textures.GpuTexture
import dev.vertex.Vertex
import dev.vertex.runtime.FrameHash
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.TreeMap

/** Opt-in GPU readback sequence used to create or verify deterministic replay baselines. */
object ReplayCapture {
    private val requested = System.getProperty("vertex.replayHashes")
    private val frames = System.getProperty("vertex.replayFrames")?.toIntOrNull()?.coerceIn(1, 10_000) ?: 120
    private var initialized = false
    private var path: Path? = null
    private var expected: List<Long>? = null
    private val pending = TreeMap<Int, Long>()
    private val actual = ArrayList<Long>()
    private var submitted = 0

    fun capture(device: GpuDevice, texture: GpuTexture, width: Int, height: Int) {
        if (requested == null || submitted >= frames) return
        initialize()
        val index = submitted++
        val buffer = device.createBuffer({ "vertex-replay-$index" },
            GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST, width.toLong() * height * 4)
        device.createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, {
            try {
                buffer.map(true, false).use { mapped ->
                    val data = mapped.data()
                    complete(index, FrameHash.rgba(data, width, height, data.limit() / height))
                }
            } catch (t: Throwable) { Vertex.log.error("[Vertex] replay frame $index readback failed", t) }
            finally { buffer.close() }
        }, 0)
    }

    @Synchronized
    private fun complete(index: Int, hash: Long) {
        pending[index] = hash
        while (pending.containsKey(actual.size)) actual += pending.remove(actual.size)!!
        val baseline = expected
        if (baseline != null && index < baseline.size && baseline[index] != hash)
            Vertex.log.error("[Vertex] replay drift at frame {}: expected={} actual={}", index,
                baseline[index].toULong().toString(16), hash.toULong().toString(16))
        if (actual.size == frames) {
            if (baseline == null) {
                Files.createDirectories(path!!.parent)
                Files.write(path!!, actual.map { it.toULong().toString(16) })
                Vertex.log.info("[Vertex] replay baseline written: {} ({} frames)", path, frames)
            } else if (baseline.take(frames) == actual) {
                Vertex.log.info("[Vertex] replay verified: {} frames", frames)
            }
        }
    }

    private fun initialize() {
        if (initialized) return
        initialized = true
        val raw = Path.of(requested!!)
        path = (if (raw.isAbsolute) raw else Minecraft.getInstance().gameDirectory.toPath().resolve(raw)).normalize()
        if (Files.isRegularFile(path)) expected = Files.readAllLines(path).filter(String::isNotBlank)
            .map { it.trim().toULong(16).toLong() }
        expected?.let { require(it.size >= frames) { "replay baseline has ${it.size} frames; $frames required" } }
    }
}
