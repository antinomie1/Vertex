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

/** An Iris-style pack manager: list on the left, selected pack and options on the right. */
class VertexShadersScreen(private val parent: Screen?) : Screen(Component.literal("Vertex Shader Manager")) {
    private data class PackEntry(val path: Path?, val name: String, val disable: Boolean = false)

    private val gameDir = Minecraft.getInstance().gameDirectory.toPath()
    private var entries = emptyList<PackEntry>()
    private var selected: Path? = null
    private var enabled = true
    private var scale = 1f
    private var shadow = 2048
    private var scroll = 0
    private var status = ""
    private var loaded = false
    private val packButtons = ArrayList<Pair<PackEntry, Button>>()
    private var scaleButton: Button? = null
    private var shadowButton: Button? = null

    private val margin get() = (width / 18).coerceIn(12, 32)
    private val compact get() = width < 620
    private val gap = 10
    private val contentTop = 60
    private val footerY get() = (height - 30).coerceAtLeast(contentTop + 120)
    private val contentHeight get() = (footerY - contentTop - 10).coerceAtLeast(120)
    private val listX get() = margin
    private val listW get() = if (compact) width - margin * 2 else
        (((width - margin * 2 - gap) * .42f).toInt()).coerceAtLeast(190)
    private val listH get() = if (compact) (contentHeight * .52f).toInt().coerceAtLeast(90) else contentHeight
    private val detailX get() = if (compact) margin else listX + listW + gap
    private val detailY get() = if (compact) contentTop + listH + gap else contentTop
    private val detailW get() = if (compact) width - margin * 2 else width - margin - detailX
    private val detailH get() = (footerY - detailY - 10).coerceAtLeast(90)

    override fun init() {
        if (!loaded) {
            val current = PackRuntime.settings(gameDir)
            selected = current.pack?.let(Path::of)
            enabled = current.enabled
            scale = current.renderScale
            shadow = current.shadowResolution
            loaded = true
        }
        entries = scan()
        val rows = ((listH - 34) / 24).coerceAtLeast(1)
        scroll = scroll.coerceIn(0, (entries.size - rows).coerceAtLeast(0))
        packButtons.clear()

        entries.drop(scroll).take(rows).forEachIndexed { index, entry ->
            val button = addRenderableWidget(Button.builder(Component.literal(packLabel(entry))) {
                if (entry.disable) enabled = false else {
                    enabled = true
                    selected = entry.path
                }
                updatePackLabels()
            }.bounds(listX + 8, contentTop + 28 + index * 24, listW - 16, 20).build())
            packButtons += entry to button
        }

        val optionX = detailX + 12
        val optionW = (detailW - 24).coerceAtLeast(80)
        val optionTop = detailY + 62
        scaleButton = addRenderableWidget(Button.builder(Component.literal(scaleLabel())) {
            scale = when (scale) { 1f -> .75f; .75f -> .5f; else -> 1f }
            scaleButton?.message = Component.literal(scaleLabel())
        }.bounds(optionX, optionTop, optionW, 20).build())
        shadowButton = addRenderableWidget(Button.builder(Component.literal(shadowLabel())) {
            shadow = when (shadow) { 1024 -> 2048; 2048 -> 4096; else -> 1024 }
            shadowButton?.message = Component.literal(shadowLabel())
        }.bounds(optionX, optionTop + 28, optionW, 20).build())

        addRenderableWidget(Button.builder(Component.literal("应用并重载")) { apply() }
            .bounds(margin, footerY, 130, 20).build())
        addRenderableWidget(Button.builder(Component.literal("刷新")) { scroll = 0; rebuildWidgets() }
            .bounds(margin + 138, footerY, 76, 20).build())
        addRenderableWidget(Button.builder(Component.literal("返回")) { onClose() }
            .bounds(width - margin - 84, footerY, 84, 20).build())
    }

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractor.fill(listX, contentTop, listX + listW, contentTop + listH, 0xD9151A24.toInt())
        extractor.fill(detailX, detailY, detailX + detailW, detailY + detailH, 0xD9151A24.toInt())
        extractor.centeredText(font, "Vertex 着色器管理器", width / 2, 20, 0xFFFFFFFF.toInt())
        extractor.text(font, "着色器包", listX + 12, contentTop + 10, 0xFFE6EDF3.toInt())
        extractor.text(font, "${entries.size} 个包", listX + listW - 58, contentTop + 10, 0xFF8FA3B8.toInt())
        extractor.text(font, "当前选择", detailX + 12, detailY + 12, 0xFF8FA3B8.toInt())
        extractor.text(font, selectedName(), detailX + 12, detailY + 28, 0xFFFFFFFF.toInt())
        extractor.text(font, "渲染设置", detailX + 12, detailY + 48, 0xFF8FA3B8.toInt())
        extractor.text(font, "状态：${status.ifBlank { if (enabled) "已启用" else "已关闭，使用原版渲染" }}", detailX + 12, detailY + 104, 0xFFB8C7D9.toInt())
        selected?.let { extractor.text(font, it.toAbsolutePath().toString().take(54), detailX + 12, detailY + 122, 0xFF74879A.toInt()) }
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (mouseX in listX.toDouble()..(listX + listW).toDouble() && mouseY in contentTop.toDouble()..(contentTop + listH).toDouble()) {
            val rows = ((listH - 34) / 24).coerceAtLeast(1)
            val max = (entries.size - rows).coerceAtLeast(0)
            val next = (scroll - verticalAmount.toInt().coerceIn(-1, 1)).coerceIn(0, max)
            if (next != scroll) {
                scroll = next
                rebuildWidgets()
            }
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)
    }

    override fun onClose() = VertexUiBridge.show(parent)
    override fun isPauseScreen() = minecraft.level != null

    override fun rebuildWidgets() {
        clearWidgets()
        init()
    }

    private fun apply() {
        runCatching { Vertex.reloadShaders(PackRuntime.Settings(enabled, selected?.toString(), scale, shadow)) }
            .onSuccess {
                status = "已应用，管线和区块网格已安全重建"
                updatePackLabels()
            }
            .onFailure { status = "应用失败：${it.message?.take(64) ?: it.javaClass.simpleName}" }
    }

    private fun updatePackLabels() = packButtons.forEach { (entry, button) ->
        button.message = Component.literal(packLabel(entry))
    }

    private fun packLabel(entry: PackEntry): String {
        val active = if (entry.disable) !enabled else enabled && samePath(entry.path, selected)
        return (if (active) "✓ " else "") + entry.name
    }

    private fun selectedName() = when {
        !enabled -> "关闭（原版渲染）"
        selected == null -> "Vertex 内置测试包"
        else -> selected!!.fileName.toString()
    }

    private fun scaleLabel() = "渲染比例：${(scale * 100).toInt()}%"
    private fun shadowLabel() = "阴影分辨率：${shadow}²"
    private fun samePath(a: Path?, b: Path?) = a != null && b != null &&
        a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()

    private fun scan(): List<PackEntry> {
        val directory = gameDir.resolve("shaderpacks")
        val local = runCatching {
            Files.createDirectories(directory)
            Files.list(directory).use { stream ->
                stream.filter { path ->
                    (Files.isDirectory(path) && Files.isDirectory(path.resolve("shaders"))) ||
                        (Files.isRegularFile(path) && path.fileName.toString().lowercase().endsWith(".zip"))
                }.map { path -> PackEntry(path, path.fileName.toString()) }.toList()
            }.sortedBy { it.name.lowercase() }
        }.getOrElse {
            status = "无法扫描 shaderpacks：${it.message?.take(48) ?: "IO error"}"
            emptyList()
        }
        val external = selected?.let { path ->
            if (Files.exists(path) && local.none { samePath(it.path, path) })
                listOf(PackEntry(path, path.fileName.toString())) else emptyList()
        }.orEmpty()
        return listOf(PackEntry(null, "Vertex 内置测试包"), PackEntry(null, "关闭（原版）", true)) + external + local
    }
}
