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
import java.util.Properties
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/** The pack-owned second-level options screen used by Iris-style shader packs. */
class VertexShaderOptionsScreen(
    private val parent: Screen,
    private val pack: Path,
    private val menu: String = "screen",
    private val menuTitle: String = "光影选项",
    private val values: MutableMap<String, String> = PackRuntime.options().toMutableMap(),
) : Screen(Component.literal(menuTitle)) {
    private val model by lazy { ShaderPackOptions.load(pack, values) }
    private var optionList: OptionList? = null
    private var status = ""
    private var dirty = false

    private data class Layout(
        val margin: Int,
        val gap: Int,
        val titleY: Int,
        val top: Int,
        val bottom: Int,
        val footerY: Int,
        val footerHeight: Int,
        val padding: Int,
        val listX: Int,
        val listY: Int,
        val listWidth: Int,
        val listHeight: Int,
        val applyWidth: Int,
        val returnWidth: Int,
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
        val footerHeight = (lineHeight + padding * 2).coerceAtMost((height - margin).coerceAtLeast(1))
        val footerY = (height - margin - footerHeight).coerceAtLeast(0)
        val top = (titleY + lineHeight + padding).coerceAtMost(footerY)
        val bottom = (footerY - padding / 2).coerceAtLeast(top)
        val listY = (top + lineHeight * 2 + padding).coerceAtMost(bottom)
        val listHeight = (bottom - listY).coerceAtLeast(1)
        val available = (width - margin * 2 - padding * 2).coerceAtLeast(1)
        val footerAvailable = (width - margin * 2 - gap).coerceAtLeast(2)
        val minimumApply = (font.width("应用并重载") + padding * 2 + 4).coerceAtLeast(1)
        val minimumReturn = (font.width("返回") + padding * 2 + 4).coerceAtLeast(1)
        val applyWidth: Int
        val returnWidth: Int
        if (minimumApply + minimumReturn <= footerAvailable) {
            val extra = footerAvailable - minimumApply - minimumReturn
            applyWidth = minimumApply + extra / 2
            returnWidth = minimumReturn + extra - extra / 2
        } else {
            applyWidth = (minimumApply.toDouble() / (minimumApply + minimumReturn) * footerAvailable)
                .roundToInt().coerceAtLeast(1)
            returnWidth = (footerAvailable - applyWidth).coerceAtLeast(1)
        }
        return Layout(
            margin, gap, titleY, top, bottom, footerY, footerHeight, padding,
            margin + padding, listY, available, listHeight, applyWidth, returnWidth,
        )
    }

    override fun init() {
        val ui = layout
        val previousScroll = optionList?.scrollAmount() ?: 0.0
        optionList = OptionList(
            this,
            ui.listX,
            ui.listY,
            ui.listWidth,
            ui.listHeight,
            (font.lineHeight + ui.padding).coerceAtLeast(1),
            ui.padding,
        ).also {
            addRenderableWidget(it)
            it.setItems(model.items(menu))
            it.setScrollAmount(previousScroll)
        }
        addRenderableWidget(Button.builder(Component.literal("应用并重载")) { apply() }
            .bounds(ui.margin, ui.footerY, ui.applyWidth, ui.footerHeight).build())
        addRenderableWidget(Button.builder(Component.literal("返回")) { onClose() }
            .bounds(width - ui.margin - ui.returnWidth, ui.footerY, ui.returnWidth, ui.footerHeight).build())
    }

    override fun extractRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val ui = layout
        extractor.fill(ui.margin, ui.top, width - ui.margin, ui.bottom, 0xD9151A24.toInt())
        extractor.centeredText(font, menuTitle, width / 2, ui.titleY, 0xFFFFFFFF.toInt())
        val textWidth = (width - ui.margin * 2 - ui.padding * 2).coerceAtLeast(1)
        extractor.text(font, font.plainSubstrByWidth("光影选项 / ${pack.fileName}", textWidth),
            ui.margin + ui.padding, ui.top + ui.padding / 2, 0xFF8FA3B8.toInt())
        val hint = if (dirty) "有未应用的更改" else status.ifBlank { "点击选项切换值，分类可进入下一级" }
        extractor.text(font, font.plainSubstrByWidth(hint, textWidth),
            ui.margin + ui.padding, ui.top + ui.padding / 2 + font.lineHeight, 0xFFB8C7D9.toInt())
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
    }

    override fun onClose() = VertexUiBridge.show(parent)

    private fun openCategory(id: String, title: String) {
        VertexUiBridge.show(VertexShaderOptionsScreen(this, pack, id, "光影选项 / $title", values))
    }

    private fun cycle(option: ShaderPackOptions.Option) {
        val next = (option.values.indexOf(values[option.key]) + 1).mod(option.values.size)
        setOption(option, next)
    }

    private fun setOption(option: ShaderPackOptions.Option, index: Int) {
        val selected = option.values[index.coerceIn(option.values.indices)]
        values[option.key] = selected
        if (option.key == "profile") model.profileValues(selected).forEach { (key, value) -> values[key] = value }
        dirty = true
        optionList?.refresh()
    }

    private fun apply() {
        runCatching {
            val gameDir = minecraft.gameDirectory.toPath()
            PackRuntime.applyOptions(gameDir, values)
            val current = PackRuntime.settings(gameDir)
            Vertex.reloadShaders(current.copy(enabled = true, pack = pack.toAbsolutePath().normalize().toString()))
        }.onSuccess {
            dirty = false
            status = "已应用并重载光影"
        }.onFailure {
            status = "应用失败：${it.message?.take(64) ?: it.javaClass.simpleName}"
        }
    }

    private class OptionList(
        private val screen: VertexShaderOptionsScreen,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        rowHeight: Int,
        private val padding: Int,
    ) : AbstractSelectionList<OptionList.Entry>(Minecraft.getInstance(), width, height, y, rowHeight) {
        init { setX(x) }

        override fun getRowWidth() = (width - 4).coerceAtLeast(1)

        fun setItems(items: List<ShaderPackOptions.Item>) {
            replaceEntries(items.map { Entry(this, it) })
        }

        fun refresh() {
            val scroll = scrollAmount()
            setItems(screen.model.items(screen.menu))
            setScrollAmount(scroll)
        }

        override fun extractListBackground(extractor: GuiGraphicsExtractor) = Unit
        override fun extractListSeparators(extractor: GuiGraphicsExtractor) = Unit
        override fun extractSelection(extractor: GuiGraphicsExtractor, entry: Entry, top: Int) = Unit
        override fun updateWidgetNarration(output: NarrationElementOutput) = Unit

        class Entry(private val list: OptionList, val item: ShaderPackOptions.Item) : AbstractSelectionList.Entry<Entry>() {
            override fun extractContent(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, hovered: Boolean, partialTick: Float) {
                val x = getContentX()
                val y = getContentY()
                val right = getContentRight()
                val bottom = getContentBottom()
                val selected = list.screen.optionList?.getSelected() === this
                val color = when { selected -> 0xFF385A78.toInt(); hovered -> 0xFF273848.toInt(); else -> 0xFF1B2632.toInt() }
                extractor.fill(x, y, right, bottom, color)
                val textY = y + ((bottom - y - list.screen.font.lineHeight) / 2).coerceAtLeast(0)
                when (item) {
                    is ShaderPackOptions.Item.Option -> {
                        val value = list.screen.values[item.key] ?: item.values.firstOrNull().orEmpty()
                        val available = (right - x - list.padding * 2).coerceAtLeast(1)
                        val keyNeed = list.screen.font.width(item.key) + list.padding
                        val valueNeed = list.screen.font.width(value) + list.padding
                        val keyColumn = if (keyNeed + valueNeed <= available) keyNeed
                        else (available * keyNeed.toDouble() / (keyNeed + valueNeed)).toInt()
                            .coerceIn(1, (available - 1).coerceAtLeast(1))
                        val key = list.screen.font.plainSubstrByWidth(item.key, (keyColumn - list.padding).coerceAtLeast(1))
                        val valueX = x + list.padding + keyColumn
                        val text = list.screen.font.plainSubstrByWidth(value, (right - valueX - list.padding).coerceAtLeast(1))
                        extractor.text(list.screen.font, key, x + list.padding, textY, 0xFFE6EDF3.toInt())
                        extractor.text(list.screen.font, text, valueX, textY, 0xFFB9E3FF.toInt())
                        if (item.option.slider && item.values.size > 1) {
                            val left = x + list.padding
                            val trackRight = right - list.padding
                            val trackY = bottom - 2
                            val fraction = item.values.indexOf(value).coerceAtLeast(0).toFloat() / (item.values.size - 1)
                            val knob = left + ((trackRight - left) * fraction).roundToInt()
                            extractor.fill(left, trackY, trackRight, trackY + 1, 0xFF526679.toInt())
                            extractor.fill(left, trackY, knob.coerceAtLeast(left + 1), trackY + 1, 0xFFB9E3FF.toInt())
                        }
                    }
                    is ShaderPackOptions.Item.Subscreen -> {
                        val action = "进入"
                        val actionX = (right - list.padding - list.screen.font.width(action)).coerceAtLeast(x + list.padding)
                        val titleWidth = (actionX - x - list.padding * 2).coerceAtLeast(1)
                        extractor.text(list.screen.font, list.screen.font.plainSubstrByWidth("› ${item.title}", titleWidth), x + list.padding, textY, 0xFFB9E3FF.toInt())
                        extractor.text(list.screen.font, action, actionX, textY, 0xFF8FA3B8.toInt())
                    }
                    is ShaderPackOptions.Item.Info -> extractor.text(list.screen.font, list.screen.font.plainSubstrByWidth(item.text, (right - x - list.padding * 2).coerceAtLeast(1)), x + list.padding, textY, 0xFF74879A.toInt())
                }
    }

            private var dragging = false

            override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
                if (event.button() != 0 || !isMouseOver(event.x(), event.y())) return false
                list.setSelected(this)
                when (item) {
                    is ShaderPackOptions.Item.Option -> if (item.option.slider) {
                        dragging = true
                        setSlider(event.x())
                    } else list.screen.cycle(item.option)
                    is ShaderPackOptions.Item.Subscreen -> list.screen.openCategory(item.id, item.title)
                    is ShaderPackOptions.Item.Info -> Unit
                }
                return true
            }

            override fun mouseDragged(event: MouseButtonEvent, deltaX: Double, deltaY: Double): Boolean {
                if (!dragging || event.button() != 0) return false
                setSlider(event.x())
                return true
            }

            override fun mouseReleased(event: MouseButtonEvent): Boolean {
                val wasDragging = dragging
                dragging = false
                return wasDragging
            }

            private fun setSlider(mouseX: Double) {
                val option = (item as? ShaderPackOptions.Item.Option)?.option ?: return
                val left = getContentX() + list.padding
                val right = getContentRight() - list.padding
                val fraction = ((mouseX - left) / (right - left).coerceAtLeast(1)).coerceIn(0.0, 1.0)
                list.screen.setOption(option, (fraction * (option.values.size - 1)).roundToInt())
            }
        }
    }
}

/** Reads the standard Iris/OptiFine shaders.properties menu grammar. */
private class ShaderPackOptions private constructor(
    private val properties: Properties,
    private val settings: Map<String, Setting>,
    private val values: MutableMap<String, String>,
) {
    sealed interface Item {
        data class Option(val option: ShaderPackOptions.Option) : Item {
            val key get() = option.key
            val values get() = option.values
        }
        data class Subscreen(val id: String, val title: String) : Item
        data class Info(val text: String) : Item
    }

    data class Option(val key: String, val values: List<String>, val slider: Boolean)
    private data class Setting(val default: String, val values: List<String>)

    fun items(menu: String): List<Item> {
        val line = properties.getProperty(menuKey(menu)).orEmpty()
        return line.split(Regex("\\s+"))
            .filter { it.isNotBlank() && it != "<empty>" }
            .distinct()
            .mapNotNull { token ->
                when {
                    token == "<profile>" -> option("profile")?.let(Item::Option)
                    token.startsWith("[") && token.endsWith("]") -> {
                        val id = token.substring(1, token.length - 1)
                        if (properties.getProperty("screen.$id") != null) Item.Subscreen(id, pretty(id)) else null
                    }
                    token == "ABOUT" && properties.getProperty("screen.ABOUT") == null -> Item.Info("${pretty(token)}（此光影未提供页面）")
                    else -> option(token)?.let(Item::Option)
                }
            }
    }

    fun profileValues(name: String): Map<String, String> = resolveProfile(name, mutableSetOf())

    private fun option(key: String): Option? {
        if (key == "ABOUT") return null
        val profile = profileDefaults()
        val setting = settings[key]
        val default = values[key] ?: if (key == "profile") {
            profileNames.firstOrNull { it.equals("HIGH", ignoreCase = true) }
                ?: profileNames.firstOrNull()
                ?: "default"
        } else profile[key] ?: properties.getProperty(key)?.trim() ?: setting?.default
            ?: if (key in profile.keys) "false" else "0"
        values.putIfAbsent(key, default)
        val slider = properties.getProperty("sliders", "").split(Regex("\\s+")).contains(key)
        val choices = when {
            key == "profile" -> profileNames
            setting != null && setting.values.isNotEmpty() -> setting.values
            default.equals("true", true) || default.equals("false", true) || key in profile.keys -> listOf("false", "true")
            slider -> numericChoices(key, default)
            else -> listOf(default)
        }
        return Option(key, choices.distinct().ifEmpty { listOf(default) }, slider)
    }

    private fun numericChoices(key: String, default: String): List<String> {
        val profileChoices = profileNames.flatMap { resolveProfile(it, mutableSetOf()).filterKeys { it == key }.values }
        if (profileChoices.size > 1) return (profileChoices + default).distinct()
            .sortedWith(compareBy<String> { it.toDoubleOrNull() ?: 0.0 }.thenBy { it })
        val number = default.toDoubleOrNull() ?: return listOf(default)
        if (key.contains("resolution", true)) return listOf("512", "1024", "1536", "2048", "3072", "4096")
        return listOf(number - .25, number, number + .25).map { if (it % 1.0 == 0.0) it.toInt().toString() else "%.2f".format(it) }
    }

    private fun profileDefaults(): Map<String, String> {
        val selected = values["profile"]?.takeIf { it in profileNames } ?: profileNames.firstOrNull { it == "HIGH" } ?: profileNames.firstOrNull()
        return selected?.let(::profileValues).orEmpty()
    }

    private fun resolveProfile(name: String, seen: MutableSet<String>): Map<String, String> {
        if (!seen.add(name)) return emptyMap()
        val out = linkedMapOf<String, String>()
        properties.getProperty("profile.$name").orEmpty().split(Regex("\\s+")).forEach { token ->
            when {
                token.isBlank() -> Unit
                token.startsWith("profile.") -> out.putAll(resolveProfile(token.removePrefix("profile."), seen))
                token.startsWith("!") -> out[token.substring(1)] = "false"
                '=' in token -> token.substringBefore('=') to token.substringAfter('=').also { out[token.substringBefore('=')] = it }
                else -> out[token] = "true"
            }
        }
        return out
    }

    private fun menuKey(menu: String) = if (menu == "screen") "screen" else "screen.$menu"
    private fun pretty(value: String) = value.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercaseChar)

    companion object {
        fun load(path: Path, values: MutableMap<String, String>): ShaderPackOptions {
            val properties = Properties()
            var settingsText = ""
            runCatching {
                if (Files.isDirectory(path)) {
                    val file = listOf(path.resolve("shaders/shaders.properties"), path.resolve("shaders.properties")).firstOrNull(Files::isRegularFile)
                    if (file != null) Files.newInputStream(file).use(properties::load)
                    val settings = listOf(path.resolve("shaders/lib/settings.glsl"), path.resolve("shaders/settings.glsl"))
                        .firstOrNull(Files::isRegularFile)
                    if (settings != null) settingsText = Files.readString(settings)
                } else ZipFile(path.toFile()).use { zip ->
                    val entry = zip.entries().asSequence().firstOrNull {
                        !it.isDirectory && (it.name.endsWith("shaders/shaders.properties") || it.name.endsWith("shaders.properties"))
                    }
                    if (entry != null) zip.getInputStream(entry).use(properties::load)
                    val settings = zip.entries().asSequence().firstOrNull {
                        !it.isDirectory && (it.name.endsWith("shaders/lib/settings.glsl") || it.name.endsWith("shaders/settings.glsl"))
                    }
                    if (settings != null) zip.getInputStream(settings).bufferedReader().use { settingsText = it.readText() }
                }
            }
            return ShaderPackOptions(properties, parseSettings(settingsText), values)
        }

        private fun parseSettings(source: String): Map<String, Setting> {
            val parsed = linkedMapOf<String, Setting>()
            source.lineSequence().forEach { line ->
                DEFINE.find(line)?.let { match ->
                    val key = match.groupValues[2]
                    val body = match.groupValues[3].trim()
                    val comment = body.substringAfter("//", "")
                    val choices = bracketValues(comment)
                    val default = if (match.groupValues[1].isNotBlank()) "false"
                    else body.substringBefore("//").trim().ifBlank { "true" }
                    parsed[key] = Setting(default, choices)
                    return@forEach
                }
                CONST.find(line)?.let { match ->
                    parsed[match.groupValues[1]] = Setting(match.groupValues[2].trim(), bracketValues(match.groupValues[3]))
                }
            }
            return parsed
        }

        private fun bracketValues(comment: String): List<String> = comment
            .substringAfter('[', "")
            .substringBefore(']', "")
            .trim()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)

        private val DEFINE = Regex("""^\s*(//\s*)?#define\s+([A-Za-z_]\w*)(?:\s+(.*))?\s*$""")
        private val CONST = Regex("""^\s*const\s+\w+(?:\s+\w+)?\s+([A-Za-z_]\w*)\s*=\s*([^;]+);\s*//\s*(\[[^]]*])""")
    }

    private val profileNames get() = properties.stringPropertyNames().asSequence()
        .filter { it.startsWith("profile.") && !it.removePrefix("profile.").contains('.') }
        .map { it.removePrefix("profile.") }.sorted().toList()
}
