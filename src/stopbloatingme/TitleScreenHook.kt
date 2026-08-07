package stopbloatingme

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.GameState
import com.fs.starfarer.api.combat.BaseEveryFrameCombatPlugin
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.state.AppDriver
import stopbloatingme.uiframework.ReflectionUtils.invoke

/**
 * Puts the "DE-BLOAT" button on the main menu.
 *
 * The title screen runs a combat engine behind the menu (that's what the drifting ships are), so an
 * [com.fs.starfarer.api.combat.EveryFrameCombatPlugin] registered under `"plugins"` in our
 * `data/config/settings.json` gets ticked there. That's the officially-sanctioned registration point
 * -- vanilla's own `settings.json` says "add EveryFrameCombatPlugins here (with unique keys)" -- and
 * it's the same mechanism FleetBuilder uses for its title-screen autofit button.
 *
 * Reaching the menu's UI tree is one reflective call: the current [AppDriver] state exposes a public
 * `getScreenPanel()` whose name survives obfuscation, returning the root [UIPanelAPI] we can hang a
 * component on.
 *
 * The panel is rebuilt by the engine whenever you come back to the menu from a campaign, so we track
 * the one we injected into by identity and re-add whenever it changes rather than assuming a single
 * injection lasts for the process.
 */
class TitleScreenHook : BaseEveryFrameCombatPlugin() {

    /** The screen panel we last injected into; identity-compared so a rebuilt menu re-triggers us. */
    private var injectedInto: UIPanelAPI? = null

    override fun advance(amount: Float, events: MutableList<InputEventAPI>?) {
        if (Global.getCurrentState() != GameState.TITLE) {
            // Left the menu: drop the reference so returning here re-injects into the fresh panel.
            injectedInto = null
            MenuButton.forget()
            return
        }

        val screenPanel = screenPanel() ?: return
        if (screenPanel === injectedInto) return

        injectedInto = screenPanel
        runCatching { MenuButton.injectInto(screenPanel) }
            .onFailure { log.error("StopBloatingMe: failed to add the main-menu button.", it) }
    }

    /** The title screen's root UI panel, or null if the state doesn't expose one (shouldn't happen). */
    private fun screenPanel(): UIPanelAPI? {
        val state = AppDriver.getInstance()?.currentState ?: return null
        return runCatching { state.invoke("getScreenPanel") as? UIPanelAPI }.getOrNull()
    }

    companion object {
        private val log = Global.getLogger(TitleScreenHook::class.java)
    }
}
