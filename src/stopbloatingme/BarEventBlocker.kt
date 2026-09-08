package stopbloatingme

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarData
import com.fs.starfarer.api.impl.campaign.intel.bar.PortsideBarEvent
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager
import com.fs.starfarer.api.impl.campaign.intel.bar.events.BarEventManager.GenericBarEventCreator

/**
 * Keeps blacklisted bar quests out of the bar.
 *
 * The game builds every bar offer the same way: `BarEventManager` holds a pool of *creators*, picks
 * one at random every half day or so, and the quest it makes goes into `PortsideBarData` to sit in
 * whichever bar you walk into next. Alongside the pool it keeps a cooldown list of creators that
 * have gone recently and shouldn't go again yet, and the picker skips anything on it.
 *
 * **That cooldown is the lever this mod pulls.** A blocked quest gets its cooldown pushed back to
 * [SUPPRESSION_DAYS] on every sweep, so it can never come up, and any offer it already made is
 * pulled off the shelf.
 *
 * The obvious alternative is to lift the creator out of the pool instead, which is what other mods
 * in this space do. It was rejected for two reasons, both about what happens *after* you change your
 * mind:
 *
 * - **Nothing is destroyed.** Some mods register their quest types once, when a campaign is created,
 *   and never again. Delete one of those and un-blocking it later does nothing, because there is no
 *   longer anything in that save to bring back. A cooldown leaves the creator exactly where it was.
 * - **It expires on its own.** The pool and the cooldown list both live in your save. Whatever we do
 *   to them, we are writing into a file that has to survive this mod being uninstalled. A cooldown
 *   we stop refreshing simply runs out, and every quest is back within a game month; the game does
 *   this to itself constantly, so there is nothing here it doesn't already expect to see.
 *
 * **Quests you have already accepted are never touched.** Accepted work leaves the bar entirely and
 * lives in the intel screen as its own mission, with no route back to the creator that started it.
 * That is deliberate: killing a quest you are halfway through would be far worse than seeing one
 * offered a last time.
 *
 * Sweeping repeatedly is not just for the refresh. Mod plugins run in mod-list order and we have no
 * say in where we land, so a mod that registers its quest types after us would slip past a load-time
 * pass alone. [EnforcerScript] sweeps faster than the game generates offers, which closes that
 * window; the work is a scan of a list of about fifty.
 */
object BarEventBlocker {

    /**
     * How long a blocked quest is held down for, in game days.
     *
     * Long enough that it can never come up between two sweeps -- those are a quarter day apart, so
     * the margin is a hundredfold -- and short enough that uninstalling the mod puts everything back
     * inside a game month rather than leaving a save quietly poisoned.
     */
    private const val SUPPRESSION_DAYS = 30f

    private val log = Global.getLogger(BarEventBlocker::class.java)

    /**
     * Writes down the bar quests that exist only as code, so the main-menu browser can list them.
     *
     * Cheap, and worth doing on every sweep rather than only on load: a mod that registers its quest
     * types late still gets into the browser the same day.
     */
    fun learn() {
        val manager = manager() ?: return
        val settings = Global.getSettings()
        val found = ArrayList<LearnedBarEvent>()
        for (creator in creators(manager)) {
            // Auto-added creators are the ones the manager built from the spec list, and the browser
            // reads that list directly. Only hand-registered ones are invisible from the menu.
            if (runCatching { creator.wasAutoAdded() }.getOrDefault(false)) continue
            val id = idOf(creator)
            if (id.isEmpty()) continue
            if (runCatching { settings.getBarEventSpec(id) }.getOrNull() != null) continue
            found += LearnedBarEvent(id, creator.javaClass.name)
        }
        if (LearnedBarEvents.record(found)) ContentIndex.invalidate()
    }

    /**
     * Holds every blacklisted quest down, and pulls any offer it already made off the shelf.
     *
     * [verbose] is for the once-per-load pass, which should say what it did even when there was
     * nothing to pull. The recurring sweeps stay quiet unless they actually took something away,
     * because four of them go past per game day.
     */
    fun apply(reason: String, verbose: Boolean = false) {
        val blocked = BlacklistStore.ids(Category.BAR_EVENTS)
        if (blocked.isEmpty()) return
        val manager = manager() ?: return

        var held = 0
        val cooldown = runCatching { manager.timeout }.getOrNull()
        if (cooldown != null) {
            for (creator in creators(manager)) {
                if (!blocked.contains(idOf(creator))) continue
                held++
                // set(), not add(): this has to be idempotent across sweeps, or a long campaign
                // would pile a quarter day onto the same cooldown forever.
                runCatching { cooldown.set(creator, SUPPRESSION_DAYS) }
            }
        }

        // Offers made before the quest was blocked. The blacklist is edited at the main menu, so
        // this is the normal case rather than an edge one: you block something, load your save, and
        // the offer it made last session is still sitting in a bar.
        val data = runCatching { PortsideBarData.getInstance() }.getOrNull()
        var pulled = 0
        for (event in offered(manager, data)) {
            if (!isBlocked(manager, event, blocked)) continue
            pulled++
            // Clears the offer and the manager's own timer for it in one call.
            runCatching { data?.removeEvent(event) }
            runCatching { manager.active.remove(event) }
        }

        // The manager's event-to-creator map keeps a stale key for anything pulled here, and its own
        // housekeeping sweeps those out on the next tick -- an event that is no longer active is
        // exactly what it calls orphaned -- so there is nothing to unpick by hand.
        if (pulled > 0 || (verbose && held > 0)) {
            log.info("StopBloatingMe: bar quest pass ($reason) held $held quest type(s) down and " +
                "pulled $pulled offer(s).")
        }
    }

    // --- Helpers -------------------------------------------------------------------------------

    private fun manager(): BarEventManager? {
        if (Global.getSector() == null) return null
        return runCatching { BarEventManager.getInstance() }.getOrNull()
    }

    private fun creators(manager: BarEventManager): List<GenericBarEventCreator> =
        runCatching { manager.creators }.getOrNull()?.filterNotNull()?.toList().orEmpty()

    /** Everything currently on offer anywhere, from both lists that track it. */
    private fun offered(manager: BarEventManager, data: PortsideBarData?): List<PortsideBarEvent> {
        val out = LinkedHashSet<PortsideBarEvent>()
        runCatching { data?.events }.getOrNull()?.let { out.addAll(it.filterNotNull()) }
        runCatching { manager.active.items }.getOrNull()?.let { out.addAll(it.filterNotNull()) }
        return out.toList()
    }

    /**
     * Whether this offer came from a blacklisted quest.
     *
     * An offer and the creator that made it do not have to share an id: the core game's
     * `DeliveryBarEventCreator` produces an event calling itself `DeliveryBarEvent`. The blacklist
     * stores creator ids, because that is what the browser lists, so an offer has to be traced back
     * to its maker before it can be recognised. The direct id check first still earns its keep --
     * every quest from a `bar_events.csv` row does use one id for both.
     */
    private fun isBlocked(
        manager: BarEventManager,
        event: PortsideBarEvent,
        blocked: Set<String>,
    ): Boolean {
        val eventId = runCatching { event.barEventId }.getOrNull()?.trim().orEmpty()
        if (eventId.isNotEmpty() && blocked.contains(eventId)) return true
        val creator = runCatching { manager.getCreatorFor(event) }.getOrNull() ?: return false
        return blocked.contains(idOf(creator))
    }

    private fun idOf(creator: GenericBarEventCreator): String =
        runCatching { creator.barEventId }.getOrNull()?.trim().orEmpty()
}
