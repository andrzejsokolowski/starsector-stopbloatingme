package stopbloatingme

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.ButtonAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.CutStyle
import com.fs.starfarer.api.ui.TextFieldAPI
import com.fs.starfarer.api.ui.TooltipMakerAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11
import stopbloatingme.uiframework.CustomPanel
import stopbloatingme.uiframework.TooltipMakerPanel
import stopbloatingme.uiframework.anchorInCenterOfParent
import stopbloatingme.uiframework.anchorInTopLeftOfParent
import stopbloatingme.uiframework.bottom
import stopbloatingme.uiframework.clearChildren
import stopbloatingme.uiframework.drawBorder
import stopbloatingme.uiframework.getFontPath
import stopbloatingme.uiframework.Font
import stopbloatingme.uiframework.left
import stopbloatingme.uiframework.onClick
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
 * **The list is virtualised.** With 8,644 hulls in one tab, handing the engine one component per
 * entry is not an option -- it would hang for seconds and hold thousands of live widgets. Instead we
 * render exactly the rows that fit on screen (about 30) and drive a [firstRow] cursor from the mouse
 * wheel ourselves, rebuilding that small window only when the cursor or the filter actually moves.
 * Cost is therefore constant no matter how large the catalogue gets. Facet lists get real components
 * per value, which is fine -- the biggest of them is ~105 source mods, not thousands.
 *
 * Rows use Victor 14, a monospaced font, so the columns line up from padded text without needing a
 * component per cell.
 */
object BrowserPanel {

    // --- Layout --------------------------------------------------------------------------------

    private const val PAD = 12f
    private const val GAP = 12f
    private const val HEADER_H = 40f
    private const val LEFT_W = 330f
    private const val ROW_H = 22f
    private const val COL_HEADER_H = 22f
    private const val FOOTER_H = 26f
    private const val FACET_GROUP_H = 132f

    /** Column widths of a row, in monospace characters. */
    private const val COL_NAME = 34
    private const val COL_PRIMARY = 11
    private const val COL_SECONDARY = 18
    private const val COL_DESIGN = 16
    private const val COL_MOD = 24

    private val BLOCKED_COLOR = Color(235, 90, 90)

    // --- Live panel state --------------------------------------------------------------------

    private var leftHost: CustomPanelAPI? = null
    private var listHost: CustomPanelAPI? = null
    private var footerHost: CustomPanelAPI? = null
    private var headerHost: CustomPanelAPI? = null
    private var searchField: TextFieldAPI? = null

    private var listWidth = 0f
    private var listHeight = 0f
    private var headerWidth = 0f
    private var visibleRows = 1

    private var filtered: List<Entry> = emptyList()
    private var firstRow = 0

    /** Last filter fingerprint we filtered against; a change re-filters and scrolls back to the top. */
    private var lastSignature: String? = null

    /** Bumped on every blacklist edit so the list re-filters and repaints without a signature change. */
    private var blacklistStamp = 0
    private var lastStamp = -1
    private var renderedFirstRow = -1

    /** Set when a facet/tab click needs the left column rebuilt; done in advance(), not in the click,
     *  so we never mutate the UI tree from inside the engine's own button dispatch. */
    private var leftDirty = false

    // --- Construction --------------------------------------------------------------------------

    fun create(screenPanel: UIPanelAPI): CustomPanelAPI {
        val settings = Global.getSettings()
        val width = min(1360f, settings.screenWidth - 120f)
        val height = min(820f, settings.screenHeight - 120f)

        val bodyH = height - PAD * 2 - HEADER_H
        val rightW = width - PAD * 2 - LEFT_W - GAP
        headerWidth = width - PAD * 2
        listWidth = rightW
        listHeight = bodyH - COL_HEADER_H - FOOTER_H
        visibleRows = max(1, floor(listHeight / ROW_H).toInt())

        resetView()

        val panel = screenPanel.CustomPanel(width, height) { plugin ->
            plugin.renderBelow { alpha -> drawBackdrop(plugin.customPanel, alpha) }
            plugin.onScroll { event ->
                // Negative scroll amount is "wheel down" == further into the list.
                val direction = if (event.eventValue > 0) -1 else 1
                scrollBy(direction * 3)
            }
            plugin.onKeyDown { event ->
                // Home/End/PageUp/PageDown are also text-editing keys, so they only scroll the list
                // when the search box doesn't have the caret -- otherwise typing a query would yank
                // the list around underneath you. Escape always closes.
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
                CustomPanel(rightW, COL_HEADER_H) {}
                    .also { it.anchorInTopLeftOfParent(0f, 0f) }
                    .also { buildColumnHeader(it, rightW) }
                listHost = CustomPanel(rightW, listHeight) {}
                    .also { it.anchorInTopLeftOfParent(0f, COL_HEADER_H) }
                footerHost = CustomPanel(rightW, FOOTER_H) {}
                    .also { it.anchorInTopLeftOfParent(0f, COL_HEADER_H + listHeight) }
            }
            rightHost.anchorInTopLeftOfParent(PAD + LEFT_W + GAP, PAD + HEADER_H)
        }
        panel.anchorInCenterOfParent()

        buildHeader()
        buildLeft()
        refilter(resetScroll = true)
        return panel
    }

    /** Called when the panel is torn down, so stale component references can't outlive it. */
    fun dispose() {
        leftHost = null
        listHost = null
        footerHost = null
        headerHost = null
        searchField = null
        filtered = emptyList()
        lastSignature = null
        renderedFirstRow = -1
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

        if (leftDirty) {
            leftDirty = false
            buildLeft()
        }

        val signature = filter.signature()
        if (signature != lastSignature) {
            refilter(resetScroll = true)
        } else if (blacklistStamp != lastStamp) {
            // A blacklist edit can drop rows out of an Allowed/Blocked view, but the player's place
            // in the list should survive it, so this path keeps the scroll position.
            refilter(resetScroll = false)
        }

        if (firstRow != renderedFirstRow) rebuildRows()
    }

    private fun refilter(resetScroll: Boolean) {
        val filter = FilterState.current()
        filtered = FilterState.apply(FilterState.category)
        lastSignature = filter.signature()
        lastStamp = blacklistStamp
        if (resetScroll) firstRow = 0
        clampScroll()
        renderedFirstRow = -1        // force a row rebuild even if the cursor didn't move
        buildFooter()
    }

    private fun scrollBy(rows: Int) {
        firstRow += rows
        clampScroll()
    }

    private fun clampScroll() {
        val maxFirst = max(0, filtered.size - visibleRows)
        firstRow = firstRow.coerceIn(0, maxFirst)
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

    private fun buildColumnHeader(host: CustomPanelAPI, width: Float) {
        host.clearChildren()
        val category = FilterState.category
        host.TooltipMakerPanel(width, COL_HEADER_H) {
            setParaFontVictor14()
            val text = fit("Name", COL_NAME) +
                fit(category.primaryLabel, COL_PRIMARY) +
                fit(category.secondaryLabel, COL_SECONDARY) +
                fit(category.designLabel ?: "", COL_DESIGN) +
                fit("Source mod", COL_MOD)
            addPara(text, Misc.getGrayColor(), 0f)
        }
    }

    // --- Left column ---------------------------------------------------------------------------

    private fun buildLeft() {
        val host = leftHost ?: return
        val category = FilterState.category
        val filter = FilterState.of(category)
        host.clearChildren()

        val innerW = LEFT_W - 8f
        host.TooltipMakerPanel(innerW, host.position.height, withScroller = true) {
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
                "Include stations, modules and (D) hulls", null,
                Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                innerW, 22f, 6f, true,
            ).apply {
                isChecked = filter.showHidden
                onClick {
                    filter.showHidden = !filter.showHidden
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
                addSectionHeading(
                    if (chosen.isEmpty()) heading else "$heading  (${chosen.size})",
                    Alignment.MID, 10f,
                )
                addCustom(facetList(innerW, values, chosen), 4f)
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
            addButton(
                "Clear filters", null,
                Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                Alignment.MID, CutStyle.TL_BR, innerW, 24f, 6f,
            ).apply {
                isEnabled = filter.isNarrowing()
                onClick {
                    filter.clear()
                    leftDirty = true
                }
            }
        }

        // Tab labels carry blocked counts, so any bulk edit has to refresh them too.
        buildHeader()
    }

    /** A facet group: one checkbox per value, in its own scroller so a 105-mod list stays compact. */
    private fun facetList(
        width: Float,
        values: List<Pair<String, Int>>,
        chosen: MutableSet<String>,
    ): CustomPanelAPI {
        val panel = Global.getSettings().createCustom(width, FACET_GROUP_H, null)
        panel.TooltipMakerPanel(width, FACET_GROUP_H, withScroller = true) {
            for ((value, count) in values) {
                addAreaCheckbox(
                    "$value  ($count)", null,
                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Misc.getBrightPlayerColor(),
                    width - 18f, 20f, 2f, true,
                ).apply {
                    isChecked = chosen.contains(value)
                    onClick {
                        if (!chosen.remove(value)) chosen.add(value)
                        leftDirty = true
                    }
                }
            }
        }
        return panel
    }

    // --- List ----------------------------------------------------------------------------------

    private fun rebuildRows() {
        val host = listHost ?: return
        val category = FilterState.category
        val blacklist = BlacklistStore.ids(category)

        host.clearChildren()
        renderedFirstRow = firstRow

        val last = min(filtered.size, firstRow + visibleRows)
        host.TooltipMakerPanel(listWidth, listHeight) {
            setParaFontVictor14()
            if (filtered.isEmpty()) {
                addPara("Nothing matches these filters.", Misc.getGrayColor(), 4f)
                return@TooltipMakerPanel
            }
            for (i in firstRow until last) {
                val entry = filtered[i]
                val blocked = blacklist.contains(entry.id)
                addAreaCheckbox(
                    rowText(entry), null,
                    if (blocked) BLOCKED_COLOR else Misc.getBasePlayerColor(),
                    Misc.getDarkPlayerColor(),
                    if (blocked) BLOCKED_COLOR else Misc.getBrightPlayerColor(),
                    listWidth - 4f, ROW_H - 2f, 0f, true,
                ).apply {
                    isChecked = blocked
                    onClick {
                        BlacklistStore.toggle(category, entry.id)
                        blacklistStamp++
                    }
                }
            }
        }
    }

    private fun rowText(entry: Entry): String =
        fit(entry.name, COL_NAME) +
            fit(entry.primary, COL_PRIMARY) +
            fit(entry.secondary, COL_SECONDARY) +
            fit(entry.design, COL_DESIGN) +
            fit(entry.sourceMod, COL_MOD)

    /** Pads or truncates to exactly [width] monospace characters, always leaving a trailing space. */
    private fun fit(text: String, width: Int): String =
        if (text.length >= width) text.take(width - 1) + " "
        else text + " ".repeat(width - text.length)

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

    /** A toggle-looking button: an area checkbox sized to its own host panel. */
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
