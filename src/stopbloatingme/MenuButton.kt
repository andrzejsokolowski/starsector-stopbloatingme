package stopbloatingme

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ui.Alignment
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.CutStyle
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.Misc
import stopbloatingme.uiframework.Button
import stopbloatingme.uiframework.CustomPanel
import stopbloatingme.uiframework.Font
import stopbloatingme.uiframework.TooltipMakerPanel
import stopbloatingme.uiframework.anchorInBottomRightOfParent
import stopbloatingme.uiframework.onClick

/**
 * The main-menu entry point: a single button anchored bottom-left of the title screen, and the
 * open/close lifecycle of the panel it toggles.
 *
 * Injection is driven by [TitleScreenHook], which calls [injectInto] once per screen-panel instance
 * and [forget] when we leave the menu.
 */
object MenuButton {

    private const val BUTTON_WIDTH = 150f
    private const val BUTTON_HEIGHT = 30f

    /**
     * Padding from the screen edge. We sit bottom-**right**: the bottom-left corner is already taken
     * by another mod's title-screen button, and the vanilla menu column stops well above the bottom
     * of the screen, so this corner is clear.
     */
    private const val EDGE_PAD = 24f

    /** The button's own container, so we can drop it when the menu is rebuilt. */
    private var buttonPanel: CustomPanelAPI? = null

    /** The open browser panel, or null when closed. */
    private var openPanel: CustomPanelAPI? = null

    /** The screen panel both of the above are parented to. */
    private var host: UIPanelAPI? = null

    fun injectInto(screenPanel: UIPanelAPI) {
        host = screenPanel

        val container = screenPanel.CustomPanel(BUTTON_WIDTH, BUTTON_HEIGHT) { _ ->
            TooltipMakerPanel(BUTTON_WIDTH, BUTTON_HEIGHT) {
                Button(
                    text = "DE-BLOAT",
                    baseColor = Misc.getBasePlayerColor(),
                    bgColor = Misc.getDarkPlayerColor(),
                    align = Alignment.MID,
                    style = CutStyle.TL_BR,
                    width = BUTTON_WIDTH,
                    height = BUTTON_HEIGHT,
                    font = Font.ORBITRON_20,
                ) {
                    onClick { toggle(screenPanel) }
                }
            }
        }
        container.anchorInBottomRightOfParent(EDGE_PAD, EDGE_PAD)
        buttonPanel = container
    }

    /** Drops our references without touching the panels -- the engine discards the whole tree. */
    fun forget() {
        buttonPanel = null
        openPanel = null
        host = null
        BrowserPanel.dispose()
    }

    private fun toggle(screenPanel: UIPanelAPI) {
        if (openPanel != null) close() else open(screenPanel)
    }

    private fun open(screenPanel: UIPanelAPI) {
        openPanel = runCatching { BrowserPanel.create(screenPanel) }
            .onFailure {
                Global.getLogger(MenuButton::class.java)
                    .error("StopBloatingMe: browser failed to open.", it)
                BrowserPanel.dispose()
            }
            .getOrNull()
    }

    fun close() {
        val panel = openPanel ?: return
        openPanel = null
        runCatching { host?.removeComponent(panel) }
        BrowserPanel.dispose()
        // The browser is the only place the blacklist changes, and the codex reads visibility off
        // the live spec tags -- so re-deriving here is what makes an edit show up in the codex
        // straight away, rather than only after the next game load.
        runCatching { LootBlocker.apply() }
    }
}
