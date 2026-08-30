package dev.vertex.ui

import dev.vertex.Vertex
import dev.vertex.frontend.PackRuntime
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSelectionList
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.awt.Desktop
import kotlin.math.roundToInt
import java.util.zip.ZipFile

/** An Iris-style pack manager: list on the left, selected pack and options on the right. */
class VertexShadersScreen(private val parent: Screen?) : Screen(Component.literal("Vertex Shader Manager")) {
    private data class PackEntry(val path: Path?, val name: String, val disable: Boolean = false)

    private val gameDir = Minecraft.getInstance().gameDirectory.toPath()
    private var entries = emptyList<PackEntry>()
    private var selected: Path? = null
    private var enabled = true
    private var scale = 1f
    private var shadow = 2048
    private var status = ""
    private var loaded = false
    private var packList: PackList? = null
    private var scaleButton: Button? = null
    private var shadowButton: Button? = null
    private var optionsButton: Button? = null

    private data class Layout(
        val margin: Int,
        val gap: Int,
        val titleY: Int,
        val contentTop: Int,
        val contentBottom: Int,
        val footerY: Int,
        val footerHeight: Int,
        val listX: Int,
        val listW: Int,
        val listY: Int,
        val listH: Int,
        val detailX: Int,
        val detailW: Int,
        val detailH: Int,
        val padding: Int,
        val optionTop: Int,
        val optionW: Int,
        val controlGap: Int,
        val controlHeight: Int,
        val statusY: Int,
        val applyW: Int,
        val refreshW: Int,
        val folderW: Int,
        val returnW: Int,
    )

    private val layout get() = calculateLayout()

    private fun calculateLayout(): Layout {
        val lineHeight = font.lineHeight.coerceAtLeast(1)
        val smallest = minOf(width, height).coerceAtLeast(1)
        val margin = (smallest * .06f).roundToInt()
            .coerceIn(8, 32)
            .coerceAtMost((width / 4).coerceAtLeast(1))
        val gap = (width * .025f).roundToInt().coerceIn(6, 20)
            .coerceAtMost((width - margin * 2 - 1).coerceAtLeast(1))
        val padding = (smallest * .025f).roundToInt().coerceIn(4, 16)
        val titleY = (height * .06f).roundToInt().coerceAtLeast(2)
        val headerHeight = lineHeight + padding
        val buttonHeight = (lineHeight + padding * 2).coerceAtMost((height - margin).coerceAtLeast(1))
        val footerHeight = buttonHeight
        val footerY = (height - margin - footerHeight).coerceAtLeast(0)
        val contentTop = (titleY + lineHeight + padding).coerceAtMost(footerY)
        val contentBottom = (footerY - padding / 2).coerceAtLeast(contentTop)
        val contentHeight = (contentBottom - contentTop).coerceAtLeast(1)
        val available = (width - margin * 2 - gap).coerceAtLeast(1)
        val listW = (available * .42f).roundToInt().coerceIn(1, available)
        val listX = margin
        val listY = (contentTop + headerHeight).coerceAtMost(contentBottom)
        val listH = (contentBottom - listY).coerceAtLeast(1)
        val detailX = listX + listW + gap
        val detailW = (width - margin - detailX).coerceAtLeast(1)
        val detailHeader = lineHeight * 2 + padding
        val controlGap = (smallest * .015f).roundToInt().coerceIn(2, 10)
        val controlsAvailable = (contentHeight - detailHeader - padding).coerceAtLeast(1)
        val controlHeight = ((controlsAvailable - controlGap * 2) / 3)
            .coerceAtLeast(1)
            .coerceAtMost(buttonHeight)
        val optionTop = (contentTop + detailHeader + controlGap)
            .coerceAtMost((contentBottom - controlHeight * 3 - controlGap * 2).coerceAtLeast(contentTop))
        val statusY = optionTop + controlHeight * 3 + controlGap * 2 + controlGap
        val optionW = (detailW - padding * 2).coerceAtLeast(1)
        val footerGap = gap.coerceAtMost(12)
        val footerAvailable = (width - margin * 2 - footerGap * 3).coerceAtLeast(4)
        val buttonWidths = footerButtonWidths(
            footerAvailable,
            listOf("应用并重载", "刷新", "打开文件夹", "返回"),
            padding,
        )
        return Layout(
            margin, gap, titleY, contentTop, contentBottom, footerY, footerHeight,
            listX, listW, listY, listH, detailX, detailW, contentHeight, padding,
            optionTop, optionW, controlGap, controlHeight, statusY,
            buttonWidths[0], buttonWidths[1], buttonWidths[2], buttonWidths[3],
        )
    }

    private fun footerButtonWidths(available: Int, labels: List<String>, padding: Int): List<Int> {
        val minimum = labels.map { (font.width(it) + padding * 2 + 4).coerceAtLeast(1) }
        val total = minimum.sum()
        if (total >= available) {
            var remaining = available
            return minimum.mapIndexed { index, value ->
                val width = if (index == minimum.lastIndex) remaining
                else (value.toDouble() / total * available).roundToInt().coerceAtLeast(1).coerceAtMost(remaining)
                remaining -= width
                width
            }
        }
        val share = (available - total) / labels.size
        val widths = minimum.map { it + share }.toMutableList()
        widths[widths.lastIndex] += available - widths.sum()
        return widths
    }

    override fun init() {
        val ui = layout
        if (!loaded) {
            val current = PackRuntime.settings(gameDir)
            selected = current.pack?.let(::resolvePackPath)
                ?: gameDir.resolve("shaderpacks/vertex-test").takeIf { Files.isDirectory(it) }
            enabled = current.enabled
            scale = current.renderScale
            shadow = current.shadowResolution
            loaded = true
        }
        entries = scan()
        val previousScroll = packList?.scrollAmount() ?: 0.0
        packList = PackList(
            this,
            ui.listX + ui.padding,
            ui.listY,
            (ui.listW - ui.padding * 2).coerceAtLeast(1),
            ui.listH,
            (font.lineHeight + ui.padding / 2).coerceAtLeast(1),
            ui.padding,
        ).also {
            addRenderableWidget(it)
            it.setPacks(entries, selected, enabled)
            it.setScrollAmount(previousScroll)
        }

        val optionX = ui.detailX + ui.padding
        scaleButton = addRenderableWidget(Button.builder(Component.literal(scaleLabel())) {
            scale = when (scale) { 1f -> .75f; .75f -> .5f; else -> 1f }
            scaleButton?.message = Component.literal(scaleLabel())
        }.bounds(optionX, ui.optionTop, ui.optionW, ui.controlHeight).build())
        shadowButton = addRenderableWidget(Button.builder(Component.literal(shadowLabel())) {
            shadow = when (shadow) { 1024 -> 2048; 2048 -> 4096; else -> 1024 }
            shadowButton?.message = Component.literal(shadowLabel())
        }.bounds(optionX, ui.optionTop + ui.controlHeight + ui.controlGap, ui.optionW, ui.controlHeight).build())
        optionsButton = addRenderableWidget(Button.builder(Component.literal("光影选项…")) { openOptions() }
            .bounds(optionX, ui.optionTop + (ui.controlHeight + ui.controlGap) * 2, ui.optionW, ui.controlHeight).build()).also {
            it.active = enabled && selected != null
        }

        addRenderableWidget(Button.builder(Component.literal("应用并重载")) { apply() }
            .bounds(ui.margin, ui.footerY, ui.applyW, ui.footerHeight).build())
        addRenderableWidget(Button.builder(Component.literal("刷新")) { refreshPacks() }
            .bounds(ui.margin + ui.applyW + ui.gap.coerceAtMost(12), ui.footerY, ui.refreshW, ui.footerHeight).build())
        addRenderableWidget(Button.builder(Component.literal("打开文件夹")) { openShaderFolder() }
            .bounds(
                ui.margin + ui.applyW + ui.refreshW + ui.gap.coerceAtMost(12) * 2,
                ui.footerY,
                ui.folderW,
                ui.footerHeight,
            ).build())
        addRenderableWidget(Button.builder(Component.literal("返回")) { onClose() }
            .bounds(width - ui.margin - ui.returnW, ui.footerY, ui.returnW, ui.footerHeight).build())
    }

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val ui = layout
        extractor.fill(ui.listX, ui.contentTop, ui.listX + ui.listW, ui.contentBottom, 0xD9151A24.toInt())
        extractor.fill(ui.detailX, ui.contentTop, ui.detailX + ui.detailW, ui.contentBottom, 0xD9151A24.toInt())
        extractor.centeredText(font, "Vertex 着色器管理器", width / 2, ui.titleY, 0xFFFFFFFF.toInt())
        extractor.text(font, "着色器包", ui.listX + ui.padding, ui.contentTop + ui.padding / 2, 0xFFE6EDF3.toInt())
        val count = "${entries.count { !it.disable }} 个包"
        extractor.text(font, font.plainSubstrByWidth(count, (ui.listW - ui.padding * 2).coerceAtLeast(1)),
            ui.listX + ui.listW - ui.padding - font.width(count), ui.contentTop + ui.padding / 2, 0xFF8FA3B8.toInt())
        val detailTextWidth = (ui.detailW - ui.padding * 2).coerceAtLeast(1)
        extractor.text(font, "当前选择", ui.detailX + ui.padding, ui.contentTop + ui.padding / 2, 0xFF8FA3B8.toInt())
        val name = font.plainSubstrByWidth(selectedName(), detailTextWidth)
        extractor.text(font, name, ui.detailX + ui.padding, ui.contentTop + ui.padding / 2 + font.lineHeight, 0xFFFFFFFF.toInt())
        val statusText = "状态：${status.ifBlank { if (enabled) "已启用" else "已关闭，使用原版渲染" }}"
        if (ui.statusY + font.lineHeight <= ui.contentBottom) {
            extractor.text(font, font.plainSubstrByWidth(statusText, detailTextWidth), ui.detailX + ui.padding, ui.statusY, 0xFFB8C7D9.toInt())
            if (enabled) selected?.let {
                if (ui.statusY + font.lineHeight * 2 <= ui.contentBottom) {
                    val path = font.plainSubstrByWidth(it.fileName.toString(), detailTextWidth)
                    extractor.text(font, path, ui.detailX + ui.padding, ui.statusY + font.lineHeight, 0xFF74879A.toInt())
                }
            }
        }
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() == 1 && packList?.handleClick(event) == true) return true
        return super.mouseClicked(event, doubleClick)
    }

    override fun onClose() = VertexUiBridge.show(parent)
    override fun isPauseScreen() = minecraft.level != null

    override fun rebuildWidgets() {
        clearWidgets()
        init()
    }

    private fun refreshPacks() {
        entries = scan()
        if (selected != null && entries.none { samePath(it.path, selected) }) {
            selected = null
            enabled = false
            status = "已重新扫描，当前光影包不存在，已关闭光影"
        } else {
            status = "已重新扫描 shaderpacks"
        }
        packList?.setPacks(entries, selected, enabled)
        packList?.setScrollAmount(0.0)
        optionsButton?.active = enabled && selected != null
    }

    private fun apply() {
        runCatching { Vertex.reloadShaders(PackRuntime.Settings(enabled, selected?.toString(), scale, shadow)) }
            .onSuccess {
                status = if (enabled) "已应用，管线和区块网格已安全重建" else "已关闭，Vertex 已完全停用并回退原版"
                packList?.refreshSelection()
                optionsButton?.active = enabled && selected != null
            }
            .onFailure { status = "应用失败：${it.message?.take(64) ?: it.javaClass.simpleName}" }
    }

    private fun selectedName() = when {
        !enabled -> "关闭（原版渲染）"
        selected == null -> "未选择光影包"
        else -> selected!!.fileName.toString()
    }

    private fun scaleLabel() = "渲染比例：${(scale * 100).toInt()}%"
    private fun shadowLabel() = "阴影分辨率：${shadow}²"
    private fun samePath(a: Path?, b: Path?) = a != null && b != null &&
        a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()

    private fun resolvePackPath(raw: String): Path {
        val path = Path.of(raw)
        return (if (path.isAbsolute) path else gameDir.resolve("shaderpacks").resolve(path))
            .toAbsolutePath().normalize()
    }

    private fun scan(): List<PackEntry> {
        val directory = gameDir.resolve("shaderpacks")
        val local = runCatching {
            Files.createDirectories(directory)
            Files.list(directory).use { stream ->
                stream.filter(::isShaderPack)
                    .map { path -> PackEntry(path, path.fileName.toString()) }.toList()
            }.sortedBy { it.name.lowercase() }
        }.getOrElse {
            status = "无法扫描 shaderpacks：${it.message?.take(48) ?: "IO error"}"
            emptyList()
        }
        return listOf(PackEntry(null, "关闭（原版）", true)) + local
    }

    private fun isShaderPack(path: Path): Boolean = runCatching {
        if (Files.isDirectory(path)) {
            Files.isDirectory(path.resolve("shaders")) || Files.list(path).use { children ->
                children.anyMatch { Files.isDirectory(it.resolve("shaders")) }
            }
        } else if (Files.isRegularFile(path) && path.fileName.toString().lowercase().endsWith(".zip")) {
            ZipFile(path.toFile()).use { zip ->
                zip.entries().asSequence().any { entry ->
                    val name = entry.name.trim('/').split('/')
                    !entry.isDirectory && (name.firstOrNull() == "shaders" || name.getOrNull(1) == "shaders")
                }
            }
        } else false
    }.getOrDefault(false)

    private fun choose(entry: PackEntry) {
        if (entry.disable) enabled = false else {
            enabled = true
            selected = entry.path
        }
        packList?.refreshSelection()
        optionsButton?.active = enabled && selected != null
    }

    private fun openOptions() {
        val path = selected ?: run {
            status = "当前没有可配置的光影包"
            return
        }
        VertexUiBridge.show(VertexShaderOptionsScreen(this, path))
    }

    private fun openShaderFolder() {
        runCatching {
            val directory = gameDir.resolve("shaderpacks")
            Files.createDirectories(directory)
            check(Desktop.isDesktopSupported()) { "当前环境不支持打开文件夹" }
            Desktop.getDesktop().open(directory.toFile())
        }.onFailure {
            status = "无法打开 shaderpacks：${it.message?.take(64) ?: it.javaClass.simpleName}"
        }
    }

    private fun isSelected(entry: PackEntry) = if (entry.disable) !enabled else enabled && samePath(entry.path, selected)

    /** A real scrollable list, matching the interaction model of Iris' pack screen. */
    private class PackList(
        private val screen: VertexShadersScreen,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rowHeight: Int,
        private val padding: Int,
    ) : AbstractSelectionList<PackList.Entry>(Minecraft.getInstance(), width, height, y, rowHeight) {
        init { setX(x) }

        override fun getRowWidth() = (width - 4).coerceAtLeast(1)

        fun setPacks(packs: List<PackEntry>, selected: Path?, enabled: Boolean) {
            replaceEntries(packs.map { Entry(this, it) })
            children().firstOrNull { entry ->
                if (entry.pack.disable) !enabled else enabled && screen.samePath(entry.pack.path, selected)
            }?.let(::setSelected)
        }

        fun refreshSelection() {
            children().firstOrNull { screen.isSelected(it.pack) }?.let(::setSelected)
        }

        override fun extractListBackground(extractor: GuiGraphicsExtractor) = Unit
        override fun extractListSeparators(extractor: GuiGraphicsExtractor) = Unit
        override fun extractSelection(extractor: GuiGraphicsExtractor, entry: Entry, top: Int) = Unit
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

        override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
            return handleClick(event) || super.mouseClicked(event, doubleClick)
        }

        fun handleClick(event: MouseButtonEvent): Boolean {
            if (event.button() != 1 || !isMouseOver(event.x(), event.y())) return false
            val entry = getEntryAtPosition(event.x(), event.y()) ?: return false
            setSelected(entry)
            screen.choose(entry.pack)
            return true
        }

        class Entry(private val list: PackList, val pack: PackEntry) : AbstractSelectionList.Entry<Entry>() {
            override fun extractContent(
                extractor: GuiGraphicsExtractor,
                mouseX: Int,
                mouseY: Int,
                hovered: Boolean,
                partialTick: Float,
            ) {
                val x = getContentX()
                val y = getContentY()
                val right = getContentRight()
                val bottom = getContentBottom()
                val selected = list.screen.isSelected(pack)
                val color = when {
                    selected -> 0xFF385A78.toInt()
                    hovered -> 0xFF273848.toInt()
                    else -> 0xFF1B2632.toInt()
                }
                extractor.fill(x, y, right, bottom, color)
                val marker = if (selected) "✓" else ""
                val textY = y + ((bottom - y - list.screen.font.lineHeight) / 2).coerceAtLeast(0)
                val markerX = x + list.padding
                val labelX = markerX + list.screen.font.width("✓") + list.padding
                val labelWidth = (right - labelX - list.padding).coerceAtLeast(1)
                val label = list.screen.font.plainSubstrByWidth(pack.name, labelWidth)
                extractor.text(list.screen.font, marker, markerX, textY, 0xFFB9E3FF.toInt())
                extractor.text(list.screen.font, label, labelX, textY, 0xFFE6EDF3.toInt())
            }

            override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
                if (event.button() == 1 && isMouseOver(event.x(), event.y())) {
                    list.setSelected(this)
                    list.screen.choose(pack)
                    return true
                }
                return false
            }
        }
    }
}
