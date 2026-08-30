package dev.vertex.ui

import dev.vertex.Vertex
import dev.vertex.frontend.PackRuntime
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path

class VertexShadersScreen(private val parent: Screen?) : Screen(Component.literal("Vertex Shaders")) {
    private data class PackEntry(val path: Path?, val name: String, val disable: Boolean = false)

    private val gameDir = Minecraft.getInstance().gameDirectory.toPath()
    private var entries = emptyList<PackEntry>()
    private var selected: Path? = null
    private var enabled = true
    private var scale = 1f
    private var shadow = 2048
    private var status = ""
    private val packButtons = ArrayList<Pair<PackEntry, Button>>()
    private var scaleButton: Button? = null
    private var shadowButton: Button? = null

    override fun init() {
        val current = PackRuntime.settings(gameDir)
        selected = current.pack?.let(Path::of)
        enabled = current.enabled
        scale = current.renderScale
        shadow = current.shadowResolution
        entries = scan()
        packButtons.clear()

        val left = (width - 640) / 2
        val top = 62
        val rows = ((height - top - 72) / 24).coerceAtLeast(1)
        entries.take(rows).forEachIndexed { index, entry ->
            val button = addRenderableWidget(Button.builder(Component.literal(packLabel(entry))) {
                if (entry.disable) enabled = false else {
                    enabled = true
                    selected = entry.path
                }
                updatePackLabels()
            }.bounds(left, top + index * 24, 220, 20).build())
            packButtons += entry to button
        }

        scaleButton = addRenderableWidget(Button.builder(Component.literal(scaleLabel())) {
            scale = when (scale) { 1f -> .75f; .75f -> .5f; else -> 1f }
            scaleButton?.message = Component.literal(scaleLabel())
        }.bounds(left + 250, top + 30, 180, 20).build())
        shadowButton = addRenderableWidget(Button.builder(Component.literal(shadowLabel())) {
            shadow = when (shadow) { 1024 -> 2048; 2048 -> 4096; else -> 1024 }
            shadowButton?.message = Component.literal(shadowLabel())
        }.bounds(left + 250, top + 72, 180, 20).build())
        addRenderableWidget(Button.builder(Component.literal("应用并重载")) { apply() }
            .bounds(left, height - 38, 140, 20).build())
        addRenderableWidget(Button.builder(Component.literal("刷新列表")) { rebuildWidgets() }
            .bounds(left + 148, height - 38, 110, 20).build())
        addRenderableWidget(Button.builder(Component.literal("返回")) { onClose() }
            .bounds(left + 266, height - 38, 100, 20).build())
    }

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val left = ((width - 640) / 2).coerceAtLeast(8)
        val top = 62
        extractor.fill(left - 16, top - 28, left + 456, height - 52, 0xD9151A24.toInt())
        extractor.centeredText(font, "Vertex Shaders", width / 2, 22, 0xFFFFFFFF.toInt())
        extractor.text(font, "着色器包", left, top - 16, 0xFFE6EDF3.toInt())
        extractor.text(font, "渲染选项", left + 250, top + 4, 0xFFE6EDF3.toInt())
        extractor.text(font, "状态：${status.ifBlank { if (enabled) "已启用" else "已关闭" }}", left + 250, top + 122, 0xFFB8C7D9.toInt())
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
    }

    override fun onClose() {
        VertexUiBridge.show(parent)
    }

    override fun isPauseScreen() = minecraft.level != null

    private fun apply() {
        runCatching {
            Vertex.reloadShaders(PackRuntime.Settings(enabled, selected?.toString(), scale, shadow))
        }.onSuccess {
            status = "已应用，管线已重载"
            updatePackLabels()
        }.onFailure { status = "应用失败：${it.message?.take(64) ?: it.javaClass.simpleName}" }
    }

    override fun rebuildWidgets() {
        clearWidgets()
        init()
    }

    private fun updatePackLabels() {
        packButtons.forEach { (entry, button) -> button.message = Component.literal(packLabel(entry)) }
    }

    private fun packLabel(entry: PackEntry): String {
        val active = if (entry.disable) !enabled else enabled && samePath(entry.path, selected)
        return (if (active) "✓ " else "") + entry.name
    }

    private fun scaleLabel() = "渲染比例：${(scale * 100).toInt()}%"
    private fun shadowLabel() = "阴影分辨率：${shadow}²"

    private fun samePath(a: Path?, b: Path?) = a != null && b != null &&
        a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()

    private fun scan(): List<PackEntry> {
        val directory = gameDir.resolve("shaderpacks")
        Files.createDirectories(directory)
        val local = Files.list(directory).use { stream ->
            stream.filter { path ->
                Files.isDirectory(path) && Files.isDirectory(path.resolve("shaders")) ||
                    Files.isRegularFile(path) && path.fileName.toString().lowercase().endsWith(".zip")
            }.map { path -> PackEntry(path, path.fileName.toString()) }.toList()
        }.sortedBy { it.name.lowercase() }
        val active = selected?.let { path ->
            if (Files.exists(path) && local.none { samePath(it.path, path) })
                listOf(PackEntry(path, path.fileName.toString())) else emptyList()
        }.orEmpty()
        return listOf(PackEntry(null, "Vertex 默认"), PackEntry(null, "关闭（原版）", true)) + active + local
    }
}
