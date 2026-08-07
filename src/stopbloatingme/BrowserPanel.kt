package stopbloatingme

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.CutStyle
import com.fs.starfarer.api.ui.LabelAPI
import com.fs.starfarer.api.ui.TextFieldAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import stopbloatingme.uiframework.CustomPanel
import stopbloatingme.uiframework.Font
import stopbloatingme.uiframework.TooltipMakerPanel
import stopbloatingme.uiframework.anchorInCenterOfParent
import stopbloatingme.uiframework.anchorInTopLeftOfParent
import stopbloatingme.uiframework.bottom
import stopbloatingme.uiframework.clearChildren
import stopbloatingme.uiframework.drawBorder
import stopbloatingme.uiframework.getFontPath
import stopbloatingme.uiframework.left
import stopbloatingme.uiframework.onClick
import stopbloatingme.uiframework.opacity
import stopbloatingme.uiframework.right
import stopbloatingme.uiframework.top
import java.awt.Color
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The browser: three tabs of every ship, weapon and fighter your mod list defines, with search,
 * facet filters and bulk blacklisting.
 *
 * Two things drive the design here.
 *
 * **The list is virtualised and pooled.** With 3,294 browsable hulls in one tab, a component per
 * entry is not an option. We build exactly the rows that fit on screen (about 30) *once*, then
 * scrolling only rebinds their text -- no components are created or destroyed as you scroll, so the
 * cost is flat regardless of catalogue size or scroll speed.
 *
 * **Columns are real components, not padded text.** The first cut padded a single string per row and
 * relied on Victor being monospaced; it isn't, and the columns came out ragged. Each cell is now its
 * own positioned label, truncated to its own width, so alignment holds for any font.
 */
object BrowserPanel {

    // --- Layout --------------------------------------------------------------------------------

    private const val PAD = 12f
    private const val GAP = 12f
    private const val HEADER_H = 40f
    private const val LEFT_W = 340f
    private const val ROW_H = 22f
    private const val COL_HEADER_H = 22f
    private const val FOOTER_H = 26f
    private const val CELL_PAD = 6f
    private const val CELL_TEXT_Y = 3f

    /** Share of the list width each column gets. Name is widest; source mod is second, because with
     *  244 mods installed that is the column you actually read. */
    private val COLUMN_WEIGHTS = floatArrayOf(0.31f, 0.09f, 0.17f, 0.19f, 0.24f)

    private val BLOCKED_COLOR = Color(235, 90, 90)

    // --- Live panel state ----------------------------------------------------------------------

    private var leftHost: CustomPanelAPI? = null
    private var listHost: CustomPanelAPI? = null
    private var footerHost: CustomPanelAPI? = null
    private var headerHost: CustomPanelAPI? = null
    private var colHeaderHost: CustomPanelAPI? = null
    private var searchField: TextFieldAPI? = null

    private var listWidth = 0f
    private var listHeight = 0f
    private var headerWidth = 0f
    private var leftHeight = 0f
    private var visibleRows = 1
    private var columnWidths = FloatArray(0)

    private val rows = ArrayList<RowView>()

    private var filtered: List<Entry> = emptyList()
    private var firstRow = 0

    private var lastSignature: String? = null
    private var blacklistStamp = 0
    private var lastStamp = -1
    private var renderedFirstRow = -1
    private var renderedCategory: Category? = null

    private var leftDirty = false

    /**
     * When the reset button was armed, as wall-clock millis. The store is shared by every save, so an
     * accidental reset would erase work no single campaign could give back -- hence a deliberate
     * two-click confirm that disarms itself after [RESET_ARM_WINDOW_MS].
     */
    private var resetArmedAt = 0L
    private var lastResetArmed = false

    private const val RESET_ARM_WINDOW_MS = 5_000L

    // --- Construction --------------------------------------------------------------------------

    fun create(screenPanel: UIPanelAPI): CustomPanelAPI {
        val settings = Global.getSettings()
        val width = min(1500f, settings.screenWidth - 100f)
        val height = min(900f, settings.screenHeight - 100f)

        val bodyH = height - PAD * 2 - HEADER_H
        val rightW = width - PAD * 2 - LEFT_W - GAP
        headerWidth = width - PAD * 2
        leftHeight = bodyH
        listWidth = rightW
        listHeight = bodyH - COL_HEADER_H - FOOTER_H
        visibleRows = max(1, floor(listHeight / ROW_H).toInt())
        columnWidths = FloatArray(COLUMN_WEIGHTS.size) { COLUMN_WEIGHTS[it] * rightW }

        resetView()

        val panel = screenPanel.CustomPanel(width, height) { plugin ->
            plugin.renderBelow { alpha -> drawBackdrop(plugin.customPanel, alpha) }
            plugin.onKeyDown { event ->
                // Home/End/PageUp/PageDown are also text-editing keys, so they only move the list
                // when the search box doesn't have the caret. Escape always closes.
                val typing = searchField?.hasFocus() == true
                when (event.eventValue) {
                    Keyboard.KEY_ESCAPE -> MenuButton.close()
                    Keyboard.KEY_PRIOR -> if (!typing) scrollBy(-visibleRows)
                    Keyboard.KEY_NEXT -> if (!typing) scrollBy(visibleRows)
                    Keyboard.KEY_HOME -> if (!typing) scrollBy(-filtered.size)
                    Keyboard.KEY_END -> if (!typing) scrollBy(filtered.size)
                }
            }
            plugin.advance { tick() }

            headerHost = CustomPanel(width - PAD * 2, HEADER_H) {}
                .also { it.anchorInTopLeftOfParent(PAD, PAD) }
            leftHost = CustomPanel(LEFT_W, bodyH) {}
                .also { it.anchorInTopLeftOfParent(PAD, PAD + HEADER_H) }

            val rightHost = CustomPanel(rightW, bodyH) {
                colHeaderHost = CustomPanel(rightW, COL_HEADER_H) {}
                    .also { it.anchorInTopLeftOfParent(0f, 0f) }
                listHost = CustomPanel(rightW, listHeight) { listPlugin ->
                    // Scrolling is bound to the list panel, NOT the whole browser: when it lived on
                    // the root it swallowed the wheel everywhere, including over the filter column,
                    // which is why the left side couldn't be scrolled at all.
                    listPlugin.onScroll { event ->
                        scrollBy(if (event.eventValue > 0) -3 else 3)
                    }
                }.also { it.anchorInTopLeftOfParent(0f, COL_HEADER_H) }
                footerHost = CustomPanel(rightW, FOOTER_H) {}
                    .also { it.anchorInTopLeftOfParent(0f, COL_HEADER_H + listHeight) }
            }
            rightHost.anchorInTopLeftOfParent(PAD + LEFT_W + GAP, PAD + HEADER_H)
        }
        panel.anchorInCenterOfParent()

        buildHeader()
        buildColumnHeader()
        buildRowPool()
        buildLeft()
        refilter(resetScroll = true)
        return panel
    }

    fun dispose() {
        leftHost = null
        listHost = null
        footerHost = null
        headerHost = null
        colHeaderHost = null
        searchField = null
        rows.clear()
        filtered = emptyList()
        lastSignature = null
        renderedFirstRow = -1
        renderedCategory = null
    }

    private fun resetView() {
        firstRow = 0
        renderedFirstRow = -1
        lastSignature = null
        lastStamp = -1
    }

    // --- Per-frame -----------------------------------------------------------------------------

    private fun tick() {
        val filter = FilterState.current()

        searchField?.let { field ->
            val text = field.text?.trim().orEmpty()
            if (text != filter.search) filter.search = text
        }

        val armed = isResetArmed()
        if (armed != lastResetArmed) {
            lastResetArmed = armed
            leftDirty = true
        }

        if (leftDirty) {
            leftDirty = false
            buildLeft()
        }

        if (renderedCategory != FilterState.category) {
            renderedCategory = FilterState.category
            buildColumnHeader()
        }

        val signature = filter.signature()
        if (signature != lastSignature) {
            refilter(resetScroll = true)
        } else if (blacklistStamp != lastStamp) {
            // A blacklist edit can drop rows out of an Allowed/Blocked view, but the player's place
            // in the list should survive it, so this path keeps the scroll position.
            refilter(resetScroll = false)
        }

        if (firstRow != renderedFirstRow) bindRows()
    }

    private fun refilter(resetScroll: Boolean) {
        val filter = FilterState.current()
        filtered = FilterState.apply(FilterState.category)
        lastSignature = filter.signature()
        lastStamp = blacklistStamp
        if (resetScroll) firstRow = 0
        clampScroll()
        renderedFirstRow = -1
        bindRows()
        buildFooter()
    }

    private fun scrollBy(rows: Int) {
        firstRow += rows
        clampScroll()
    }

    private fun clampScroll() {
        firstRow = firstRow.coerceIn(0, max(0, filtered.size - visibleRows))
    }

    // --- Rows ----------------------------------------------------------------------------------

    /** One reusable row: a full-width click target plus one positioned label per column. */
    private class RowView(
        val panel: CustomPanelAPI,
        val button: ButtonAPI,
        val cells: List<Cell>,
    ) {
        var entry: Entry? = null

        fun bind(entry: Entry?, blocked: Boolean) {
            this.entry = entry
            if (entry == null) {
                panel.opacity = 0f
                cells.forEach { it.set("", Misc.getBasePlayerColor()) }
                button.isChecked = false
                return
            }
            panel.opacity = 1f
            button.isChecked = blocked
            val color = if (blocked) BLOCKED_COLOR else Misc.getBasePlayerColor()
            cells[0].set(entry.name, color)
            cells[1].set(entry.primary, color)
            cells[2].set(entry.secondary, color)
            cells[3].set(entry.design, color)
            cells[4].set(entry.sourceMod, color)
        }
    }

    /** One column of one row. Truncates to its own pixel width, so long mod names get ".." rather
     *  than being silently chopped mid-word by the column that follows. */
    private class Cell(
        private val tooltip: TooltipMakerAPI,
        private val label: LabelAPI,
        private val maxWidth: Float,
    ) {
        fun set(text: String, color: Color) {
            label.text = truncate(text)
            label.color = color
        }

        private fun truncate(text: String): String {
            if (text.isEmpty()) return ""
            val full = runCatching { tooltip.computeStringWidth(text) }.getOrDefault(0f)
            if (full <= maxWidth || full <= 0f) return text
            // Proportional first guess, then walk down; converges in a couple of steps instead of
            // one computeStringWidth call per character.
            var keep = (text.length * maxWidth / full).toInt().coerceIn(1, text.length)
            while (keep > 1 && tooltip.computeStringWidth(text.take(keep) + "..") > maxWidth) keep--
            return text.take(keep) + ".."
        }
    }

    private fun buildRowPool() {
        val host = listHost ?: return
        host.clearChildren()
        rows.clear()
        for (i in 0 until visibleRows) rows += createRow(host, i * ROW_H)
    }

    private fun createRow(host: CustomPanelAPI, y: Float): RowView {
        var button: ButtonAPI? = null
        val cells = ArrayList<Cell>()

        val rowPanel = host.CustomPanel(listWidth, ROW_H) {
            TooltipMakerPanel(listWidth, ROW_H) {
                // Empty label: this checkbox is only the click target and the selected-state tint.
                button = addAreaCheckbox(
                    "", null,
                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                    listWidth, ROW_H, 0f,
                )
            }
            var x = 0f
            for (columnWidth in columnWidths) {
                var tooltip: TooltipMakerAPI? = null
                var label: LabelAPI? = null
                val cellPanel = CustomPanel(columnWidth, ROW_H) {
                    tooltip = TooltipMakerPanel(columnWidth, ROW_H) {
                        // A space rather than "": an empty para can come back sized to nothing, and
                        // the label has to survive being re-texted on every bind.
                        label = addPara(" ", Misc.getBasePlayerColor(), 0f)
                    }
                }
                cellPanel.anchorInTopLeftOfParent(x + CELL_PAD, CELL_TEXT_Y)
                cells += Cell(tooltip!!, label!!, columnWidth - CELL_PAD * 2)
                x += columnWidth
            }
        }
        rowPanel.anchorInTopLeftOfParent(0f, y)

        val view = RowView(rowPanel, button!!, cells)
        view.button.onClick {
            val entry = view.entry ?: return@onClick     // an empty row past the end of the list
            BlacklistStore.toggle(FilterState.category, entry.id)
            blacklistStamp++
        }
        return view
    }

    private fun bindRows() {
        val blacklist = BlacklistStore.ids(FilterState.category)
        for ((offset, row) in rows.withIndex()) {
            val entry = filtered.getOrNull(firstRow + offset)
            row.bind(entry, entry != null && blacklist.contains(entry.id))
        }
        renderedFirstRow = firstRow
    }

    // --- Header --------------------------------------------------------------------------------

    private fun buildHeader() {
        val host = headerHost ?: return
        host.clearChildren()

        var x = 0f
        for (category in Category.entries) {
            val count = ContentIndex.entries(category).count { !it.hidden }
            val blocked = BlacklistStore.count(category)
            val label = if (blocked > 0) "${category.label}  ($count, $blocked blocked)"
            else "${category.label}  ($count)"
            val tabWidth = 250f
            host.tab(x, 0f, tabWidth, HEADER_H - 6f, label, category == FilterState.category) {
                if (FilterState.category != category) {
                    FilterState.category = category
                    resetView()
                    leftDirty = true
                }
            }
            x += tabWidth + 8f
        }

        host.smallButton(headerWidth - 110f, 0f, 110f, HEADER_H - 6f, "CLOSE") { MenuButton.close() }
    }

    /** Column titles, positioned with the same widths as the row cells so they stay in step. */
    private fun buildColumnHeader() {
        val host = colHeaderHost ?: return
        val category = FilterState.category
        host.clearChildren()

        val titles = listOf(
            "Name", category.primaryLabel, category.secondaryLabel,
            category.designLabel ?: "", "Source mod",
        )
        var x = 0f
        for ((index, columnWidth) in columnWidths.withIndex()) {
            host.CustomPanel(columnWidth, COL_HEADER_H) {
                TooltipMakerPanel(columnWidth, COL_HEADER_H) {
                    addPara(titles[index], Misc.getGrayColor(), 0f)
                }
            }.anchorInTopLeftOfParent(x + CELL_PAD, 4f)
            x += columnWidth
        }
    }

    // --- Left column ---------------------------------------------------------------------------

    private fun buildLeft() {
        val host = leftHost ?: return
        val category = FilterState.category
        val filter = FilterState.of(category)
        host.clearChildren()

        val innerW = LEFT_W - 20f
        host.TooltipMakerPanel(LEFT_W - 4f, leftHeight, withScroller = true) {
            setForceProcessInput(true)

            addSectionHeading("Search", Alignment.MID, 0f)
            searchField = addTextField(innerW, 24f, getFontPath(Font.VICTOR_14), 4f).apply {
                text = filter.search
            }

            addSectionHeading("Show", Alignment.MID, 8f)
            addCustom(
                buttonRow(innerW, 24f, Visibility.entries.map { visibility ->
                    RowButton(visibility.label, filter.visibility == visibility) {
                        filter.visibility = visibility
                        leftDirty = true
                    }
                }),
                4f,
            )
            addAreaCheckbox(
                "Include stations, modules and built-ins", null,
                Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                innerW, 22f, 6f, true,
            ).apply {
                isChecked = filter.showHidden
                onClick {
                    filter.showHidden = !filter.showHidden
                    leftDirty = true
                }
            }

            // Near the top on purpose: with the facet groups expanded the column gets long, and a
            // reset you have to hunt for is a reset you don't use.
            addButton(
                "Reset filters", null,
                Misc.getBrightPlayerColor(), Misc.getDarkPlayerColor(),
                Alignment.MID, CutStyle.TL_BR, innerW, 24f, 6f,
            ).apply {
                isEnabled = filter.isNarrowing()
                onClick {
                    filter.clear()
                    leftDirty = true
                }
            }

            for (group in FacetGroup.entries) {
                val heading = when (group) {
                    FacetGroup.PRIMARY -> category.primaryLabel
                    FacetGroup.SECONDARY -> category.secondaryLabel
                    FacetGroup.DESIGN -> category.designLabel ?: continue
                    FacetGroup.MOD -> "Source mod"
                }
                val values = FilterState.facetValues(category, group)
                if (values.isEmpty()) continue

                val chosen = filter.of(group)
                val folded = FilterState.isCollapsed(category, group)
                val marker = if (folded) "[+]" else "[-]"
                val chosenNote = if (chosen.isEmpty()) "" else "  (${chosen.size} of ${values.size})"

                // The heading is the fold control. Groups render at their natural height with no
                // inner scroller -- nested scrollers ate the wheel and a fixed height both cropped
                // long groups and wasted space on short ones (Mount has three values, not twelve).
                addAreaCheckbox(
                    "$marker $heading$chosenNote", null,
                    Misc.getBrightPlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                    innerW, 22f, 10f, true,
                ).onClick {
                    FilterState.toggleCollapsed(category, group)
                    leftDirty = true
                }
                if (folded) continue

                for ((value, count) in values) {
                    addAreaCheckbox(
                        "$value  ($count)", null,
                        Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                        innerW, 20f, 2f, true,
                    ).apply {
                        isChecked = chosen.contains(value)
                        onClick {
                            if (!chosen.remove(value)) chosen.add(value)
                            leftDirty = true
                        }
                    }
                }
            }

            addSectionHeading("Bulk", Alignment.MID, 12f)
            addCustom(
                buttonRow(innerW, 24f, listOf(
                    RowButton("Block shown", false) {
                        BlacklistStore.addAll(FilterState.category, filtered)
                        blacklistStamp++
                        leftDirty = true
                    },
                    RowButton("Unblock shown", false) {
                        BlacklistStore.removeAll(FilterState.category, filtered)
                        blacklistStamp++
                        leftDirty = true
                    },
                )),
                4f,
            )

            buildResetSection(this, innerW)
        }

        buildHeader()        // tab labels carry blocked counts
    }

    /**
     * The "stored data" section: where the blacklist lives, and the button that erases it.
     *
     * The store is shared by every save and survives restarts, so a session that starts with a full
     * blacklist has no other way back to empty. The button arms on the first click and only erases on
     * a second one within [RESET_ARM_WINDOW_MS], stating the exact number it is about to destroy.
     */
    private fun buildResetSection(tooltip: TooltipMakerAPI, width: Float) = with(tooltip) {
        val total = BlacklistStore.totalCount()
        val armed = isResetArmed()

        addSectionHeading("Stored choices", Alignment.MID, 14f)
        addPara(
            "Saved to %s and shared by every save, so this is the only way back to empty.",
            4f, Misc.getGrayColor(), Misc.getHighlightColor(), BlacklistStore.location(),
        )
        addButton(
            if (armed) "CONFIRM - ERASE ALL $total" else "Reset everything ($total)",
            null,
            if (armed) BLOCKED_COLOR else Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(),
            Alignment.MID, CutStyle.TL_BR, width, 26f, 6f,
        ).apply {
            isEnabled = total > 0
            onClick {
                if (isResetArmed()) {
                    BlacklistStore.clearAll()
                    resetArmedAt = 0L
                    blacklistStamp++
                } else {
                    resetArmedAt = System.currentTimeMillis()
                }
                leftDirty = true
            }
        }
        if (armed) {
            addPara("Click again to erase. Cancels itself in a few seconds.", BLOCKED_COLOR, 4f)
        }
        Unit
    }

    private fun isResetArmed(): Boolean =
        resetArmedAt != 0L && System.currentTimeMillis() - resetArmedAt < RESET_ARM_WINDOW_MS

    // --- Footer --------------------------------------------------------------------------------

    private fun buildFooter() {
        val host = footerHost ?: return
        val category = FilterState.category
        val total = ContentIndex.entries(category).count { !it.hidden }
        val blocked = BlacklistStore.count(category)
        host.clearChildren()
        host.TooltipMakerPanel(listWidth, FOOTER_H) {
            addPara(
                "Showing %s of %s   |   %s blocked   |   click a row to toggle, mouse wheel to scroll",
                0f, Misc.getHighlightColor(),
                filtered.size.toString(), total.toString(), blocked.toString(),
            )
        }
    }

    // --- Small UI helpers ----------------------------------------------------------------------

    private class RowButton(val label: String, val active: Boolean, val onPress: () -> Unit)

    /** Lays buttons out left-to-right; [TooltipMakerAPI] only flows vertically on its own. */
    private fun buttonRow(width: Float, height: Float, buttons: List<RowButton>): CustomPanelAPI {
        val panel = Global.getSettings().createCustom(width, height, null)
        if (buttons.isEmpty()) return panel
        val each = (width - (buttons.size - 1) * 4f) / buttons.size
        var x = 0f
        for (button in buttons) {
            panel.tab(x, 0f, each, height, button.label, button.active, button.onPress)
            x += each + 4f
        }
        return panel
    }

    private fun UIPanelAPI.tab(
        x: Float, y: Float, width: Float, height: Float,
        label: String, active: Boolean, onPress: () -> Unit,
    ) {
        CustomPanel(width, height) {
            TooltipMakerPanel(width, height) {
                addAreaCheckbox(
                    label, null,
                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                    width, height, 0f,
                ).apply {
                    isChecked = active
                    onClick(onPress)
                }
            }
        }.anchorInTopLeftOfParent(x, y)
    }

    private fun UIPanelAPI.smallButton(
        x: Float, y: Float, width: Float, height: Float, label: String, onPress: () -> Unit,
    ) {
        CustomPanel(width, height) {
            TooltipMakerPanel(width, height) {
                addButton(
                    label, null,
                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Alignment.MID, CutStyle.TL_BR, width, height, 0f,
                ).onClick(onPress)
            }
        }.anchorInTopLeftOfParent(x, y)
    }

    /** Opaque body plus a border, so the browser reads as a window over the drifting title screen. */
    private fun drawBackdrop(panel: CustomPanelAPI, alpha: Float) {
        val dark = Misc.getDarkPlayerColor()
        GL11.glDisable(GL11.GL_TEXTURE_2D)
        GL11.glColor4f(
            dark.red / 255f * 0.35f, dark.green / 255f * 0.35f, dark.blue / 255f * 0.35f,
            0.97f * alpha,
        )
        GL11.glRectf(panel.left, panel.bottom, panel.right, panel.top)
        val base = Misc.getBasePlayerColor()
        GL11.glColor4f(base.red / 255f, base.green / 255f, base.blue / 255f, alpha)
        drawBorder(panel.left, panel.top, panel.right, panel.bottom)
    }
}
