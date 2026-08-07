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
import stopbloatingme.uiframework.anchorInCenterOfParent
import stopbloatingme.uiframework.bottom
import stopbloatingme.uiframework.drawBorder
import stopbloatingme.uiframework.left
import stopbloatingme.uiframework.onClick
import stopbloatingme.uiframework.right
import stopbloatingme.uiframework.top
import org.lwjgl.opengl.GL11

/**
 * Throwaway panel for the title-screen spike. It exists to answer the questions I could not settle
 * by reading jars, and it prints the answers on screen instead of into the log:
 *
 *  1. Does an injected component actually render and take clicks over the main menu?
 *  2. Are the spec lists populated at the title screen, where [Global.getSector] is null?
 *  3. Does [com.fs.starfarer.api.loading.WithSourceMod.getSourceMod] resolve there, so the
 *     "which mod is this from" filter works without parsing CSVs?
 *  4. How long does a full index scan of every hull, weapon and wing actually take?
 *
 * Once those are confirmed this file gets replaced by the real browser.
 */
object SpikePanel {

    private const val WIDTH = 720f
    private const val HEIGHT = 460f
    private const val PAD = 16f

    fun create(screenPanel: UIPanelAPI): CustomPanelAPI {
        val stats = scan()

        val panel = screenPanel.CustomPanel(WIDTH, HEIGHT) { plugin ->
            // Opaque backdrop + border, so it reads as a window over the drifting title-screen ships.
            plugin.renderBelow { alpha ->
                val c = Misc.getDarkPlayerColor()
                GL11.glDisable(GL11.GL_TEXTURE_2D)
                GL11.glColor4f(c.red / 255f * 0.4f, c.green / 255f * 0.4f, c.blue / 255f * 0.4f, 0.95f * alpha)
                GL11.glRectf(left, bottom, right, top)
                val b = Misc.getBasePlayerColor()
                GL11.glColor4f(b.red / 255f, b.green / 255f, b.blue / 255f, alpha)
                drawBorder(left, top, right, bottom)
            }

            TooltipMakerPanel(WIDTH - PAD * 2, HEIGHT - PAD * 2) {
                // ASCII only: Starsector's bitmap fonts have no glyph for an em dash and render it
                // as "?". Applies to every user-facing string in this mod.
                addSectionHeading("StopBloatingMe - title screen spike", Alignment.MID, 0f)

                addPara("Injection worked: this panel is a child of the title screen's screenPanel.", 10f)

                addSectionHeading("Spec access at title screen", Alignment.MID, 12f)
                addPara("Sector is null here: %s", 6f, Misc.getHighlightColor(), "${Global.getSector() == null}")
                addPara("Ship hulls:    %s", 4f, Misc.getHighlightColor(), stats.hulls.toString())
                addPara("Weapons:       %s", 4f, Misc.getHighlightColor(), stats.weapons.toString())
                addPara("Fighter wings: %s", 4f, Misc.getHighlightColor(), stats.wings.toString())
                addPara("Total entries: %s", 4f, Misc.getHighlightColor(), stats.total.toString())

                addSectionHeading("Source-mod attribution", Alignment.MID, 12f)
                addPara(
                    "Distinct source mods resolved: %s   (of %s enabled)", 6f, Misc.getHighlightColor(),
                    stats.distinctMods.toString(), stats.enabledMods.toString(),
                )
                addPara(
                    "Specs with no source mod (vanilla): %s", 4f, Misc.getHighlightColor(),
                    stats.unattributed.toString(),
                )
                if (stats.sampleMods.isNotEmpty()) {
                    addPara("Sample: " + stats.sampleMods.joinToString(", "), Misc.getGrayColor(), 4f)
                }

                addSectionHeading("Index build cost", Alignment.MID, 12f)
                addPara(
                    "Full scan of all three categories: %s ms", 6f, Misc.getHighlightColor(),
                    stats.millis.toString(),
                )
                addPara(
                    "This is the one-time cost paid on first open; the result is cached for the " +
                        "rest of the session.", Misc.getGrayColor(), 4f,
                )

                // Placed with TooltipMakerAPI's own addButton (which takes a trailing pad) rather than
                // the DSL's UIPanelAPI.Button: the latter resolves to the reflective panel overload,
                // which ignores the tooltip's layout cursor and parks the button at the top-left,
                // on top of the heading.
                addButton(
                    "CLOSE", null,
                    Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(),
                    Alignment.MID, CutStyle.TL_BR,
                    120f, 28f, 16f,
                ).onClick { MenuButton.close() }
            }
        }
        panel.anchorInCenterOfParent()
        return panel
    }

    private class Stats(
        val hulls: Int,
        val weapons: Int,
        val wings: Int,
        val distinctMods: Int,
        val unattributed: Int,
        val enabledMods: Int,
        val sampleMods: List<String>,
        val millis: Long,
    ) {
        val total: Int get() = hulls + weapons + wings
    }

    /**
     * Walks every spec exactly the way the real index will, so the reported timing is representative
     * rather than optimistic: it touches [com.fs.starfarer.api.loading.WithSourceMod.getSourceMod]
     * on each spec, which is the field the source-mod filter depends on.
     */
    private fun scan(): Stats {
        val settings = Global.getSettings()
        val start = System.nanoTime()

        val mods = HashSet<String>()
        var unattributed = 0

        val hulls = settings.allShipHullSpecs
        for (spec in hulls) {
            val mod = runCatching { spec.sourceMod }.getOrNull()
            if (mod == null) unattributed++ else mods.add(mod.name)
        }
        val weapons = settings.allWeaponSpecs
        for (spec in weapons) {
            val mod = runCatching { spec.sourceMod }.getOrNull()
            if (mod == null) unattributed++ else mods.add(mod.name)
        }
        val wings = settings.allFighterWingSpecs
        for (spec in wings) {
            val mod = runCatching { spec.sourceMod }.getOrNull()
            if (mod == null) unattributed++ else mods.add(mod.name)
        }

        val millis = (System.nanoTime() - start) / 1_000_000

        return Stats(
            hulls = hulls.size,
            weapons = weapons.size,
            wings = wings.size,
            distinctMods = mods.size,
            unattributed = unattributed,
            enabledMods = settings.modManager.enabledModsCopy.size,
            sampleMods = mods.sorted().take(3),
            millis = millis,
        )
    }
}
