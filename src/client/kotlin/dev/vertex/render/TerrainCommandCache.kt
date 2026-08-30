package dev.vertex.render

import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.buffers.GpuBufferSlice
import com.mojang.renderpearl.api.commands.GpuQueryPool
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.IndexType
import com.mojang.renderpearl.api.textures.GpuSampler
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender
import dev.vertex.mixin.ChunkSectionsToRenderAccessor
import dev.vertex.mixin.DrawIndirectAccessor
import org.lwjgl.PointerBuffer
import java.nio.ByteBuffer
import java.nio.IntBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier

/** Replays the stable RenderPearl command bundle while its visible-set owner is unchanged. */
object TerrainCommandCache {
    private val bundles = Array(3) { Bundle() }
    private var replace = 0
    private var hits = 0L
    private var records = 0L
    private val epoch = AtomicLong()

    @JvmStatic
    @Suppress("CAST_NEVER_SUCCEEDS") // Mixin adds both accessor interfaces at runtime.
    fun render(sections: ChunkSectionsToRender, group: ChunkSectionLayerGroup, pass: RenderPass,
               sampler: GpuSampler, atlas: GpuTextureView, wireframe: Boolean) {
        PackChain.bindTerrainSamplers(pass, sampler, atlas)
        if (group != ChunkSectionLayerGroup.OPAQUE || !TerrainMesh.isPrepared() || sections !is ChunkSectionsToRender.DrawIndirect) {
            sections.renderGroup(group, pass, sampler, atlas, wireframe)
            return
        }
        val terrain = (sections as ChunkSectionsToRenderAccessor).`vertex$terrainTransformUBO`()
        val indirect = sections as DrawIndirectAccessor
        val groups = indirect.`vertex$drawGroups`()
        val infos = indirect.`vertex$chunkSectionInfos`()
        val currentEpoch = epoch.get()
        val cached = bundles.firstOrNull { it.matches(currentEpoch, terrain, groups, infos, sampler, atlas, wireframe) }
        if (cached != null) {
            // TerrainUniform contains the camera-relative translation and is
            // mutable even when the visible geometry bundle is unchanged.
            pass.setUniform("TerrainUniform", terrain)
            cached.commands.forEach { it(pass) }
            hits++
        } else {
            val bundle = bundles[replace++ % bundles.size]
            bundle.commands.clear()
            sections.renderGroup(group, RecordingPass(pass, bundle.commands), sampler, atlas, wireframe)
            bundle.set(currentEpoch, terrain, groups, infos, sampler, atlas, wireframe)
            records++
        }
        val interval = System.getProperty("vertex.sscaLogFrames")?.toLongOrNull()?.coerceAtLeast(1) ?: 600
        if ((hits + records) % interval == 0L) dev.vertex.Vertex.log.info(
            "[Vertex] terrain SSCA bundle: hits={} records={} ratio={}%", hits, records,
            hits * 100 / (hits + records),
        )
    }

    @JvmStatic
    fun invalidate() { epoch.incrementAndGet() }

    private class Bundle {
        var epoch = Long.MIN_VALUE
        var terrain: GpuBufferSlice? = null
        var groups: Any? = null
        var infos: GpuBufferSlice? = null
        var sampler: GpuSampler? = null
        var atlas: GpuTextureView? = null
        var wireframe = false
        val commands = ArrayList<(RenderPass) -> Unit>(24)
        fun matches(epoch: Long, terrain: GpuBufferSlice, groups: Any, infos: GpuBufferSlice, sampler: GpuSampler,
                    atlas: GpuTextureView, wireframe: Boolean) = commands.isNotEmpty() && this.terrain == terrain &&
            this.epoch == epoch &&
            this.groups == groups && this.infos == infos && this.sampler === sampler && this.atlas === atlas &&
            this.wireframe == wireframe
        fun set(epoch: Long, terrain: GpuBufferSlice, groups: Any, infos: GpuBufferSlice, sampler: GpuSampler,
                atlas: GpuTextureView, wireframe: Boolean) {
            this.epoch = epoch
            this.terrain = terrain; this.groups = groups; this.infos = infos
            this.sampler = sampler; this.atlas = atlas; this.wireframe = wireframe
        }
    }

    private class RecordingPass(private val out: RenderPass, private val log: MutableList<(RenderPass) -> Unit>) : RenderPass {
        private fun emit(command: (RenderPass) -> Unit) { command(out); log += command }
        override fun pushDebugGroup(label: Supplier<String>) = emit { it.pushDebugGroup(label) }
        override fun popDebugGroup() = emit(RenderPass::popDebugGroup)
        override fun writeTimestamp(pool: GpuQueryPool, query: Int) = emit { it.writeTimestamp(pool, query) }
        override fun setPipeline(pipeline: CompiledRenderPipeline) = emit { it.setPipeline(pipeline) }
        override fun setUniform(name: String, view: GpuTextureView?, sampler: GpuSampler?) = emit {
            if (name == "Sampler0" && view != null) PackChain.bindAtlas(it, view)
            else it.setUniform(name, view, sampler)
        }
        override fun setUniform(name: String, buffer: GpuBuffer) = emit { it.setUniform(name, buffer) }
        override fun setUniform(name: String, buffer: GpuBufferSlice) {
            // TerrainUniform contains the camera-relative translation. It must be
            // applied while recording, but never captured in the replay bundle:
            // the cache hit path binds the current slice immediately before replay.
            if (name == "TerrainUniform") {
                out.setUniform(name, buffer)
                return
            }
            emit {
                // The cached command bundle can outlive a frame. Rebind the
                // rotating pack UBO at replay time so movement never samples a
                // stale slot.
                if (name == "VertexPackUniforms") PackChain.bindUniforms(it)
                else it.setUniform(name, buffer)
            }
        }
        override fun pushConstants(data: ByteBuffer) { val copy = data.copy(); emit { copy.rewind(); it.pushConstants(copy) } }
        override fun enableScissor(x: Int, y: Int, width: Int, height: Int) = emit { it.enableScissor(x, y, width, height) }
        override fun disableScissor() = emit(RenderPass::disableScissor)
        override fun setVertexBuffer(slot: Int, buffer: GpuBufferSlice?) = emit { it.setVertexBuffer(slot, buffer) }
        override fun setIndexBuffer(buffer: GpuBuffer, type: IndexType) = emit { it.setIndexBuffer(buffer, type) }
        override fun drawIndexed(count: Int, instances: Int, first: Int, vertexOffset: Int, firstInstance: Int) =
            emit { it.drawIndexed(count, instances, first, vertexOffset, firstInstance) }
        override fun multiDrawIndexed(counts: IntBuffer, first: Int, vertexOffset: Int, firstInstance: Int) {
            val copy = counts.copy(); emit { copy.rewind(); it.multiDrawIndexed(copy, first, vertexOffset, firstInstance) }
        }
        override fun multiDrawIndexed(buffers: PointerBuffer, counts: IntBuffer, offsets: IntBuffer, instances: Int) {
            val b = buffers.duplicate(); val c = counts.copy(); val o = offsets.copy()
            emit { b.rewind(); c.rewind(); o.rewind(); it.multiDrawIndexed(b, c, o, instances) }
        }
        override fun drawIndexedIndirect(buffer: GpuBufferSlice, count: Int) = emit { it.drawIndexedIndirect(buffer, count) }
        override fun <T : Any> drawMultipleIndexed(draws: MutableCollection<RenderPass.Draw<T>>, buffer: GpuBuffer?, type: IndexType?,
                                                   uniforms: MutableCollection<String>, data: T) =
            emit { it.drawMultipleIndexed(draws, buffer, type, uniforms, data) }
        override fun draw(count: Int, instances: Int, first: Int, firstInstance: Int) = emit { it.draw(count, instances, first, firstInstance) }
        override fun multiDraw(counts: IntBuffer, instances: Int, firstInstance: Int, drawCount: Int) {
            val copy = counts.copy(); emit { copy.rewind(); it.multiDraw(copy, instances, firstInstance, drawCount) }
        }
        override fun multiDraw(counts: IntBuffer, firstInstances: IntBuffer, first: Int) {
            val c = counts.copy(); val i = firstInstances.copy(); emit { c.rewind(); i.rewind(); it.multiDraw(c, i, first) }
        }
        override fun drawIndirect(buffer: GpuBufferSlice, count: Int) = emit { it.drawIndirect(buffer, count) }
        override fun close() = Unit

        private fun ByteBuffer.copy() = ByteBuffer.allocateDirect(remaining()).put(duplicate()).flip()
        private fun IntBuffer.copy() = ByteBuffer.allocateDirect(remaining() * Int.SIZE_BYTES).asIntBuffer().put(duplicate()).flip()
    }
}
