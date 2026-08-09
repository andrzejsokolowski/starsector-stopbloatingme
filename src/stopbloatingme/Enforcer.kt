package stopbloatingme

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.FactionAPI
import com.fs.starfarer.api.campaign.PlayerMarketTransaction
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.listeners.ColonyInteractionListener
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import com.fs.starfarer.api.impl.codex.CodexDataV2
import com.fs.starfarer.api.impl.codex.CodexEntryPlugin
import com.fs.starfarer.api.util.IntervalUtil

/**
 * The half of the mod that actually changes the game: takes what [BlacklistStore] says and removes
 * it from play.
 *
 * Four layers, because no single lever covers everything:
 *
 * 1. **Faction known-lists** ([stripFactions]) -- the primary lever for spawning. Fleet composition
 *    and vanilla market stock are both driven by each faction's known ships/weapons/fighters, so
 *    removing an id there stops it spawning at the source. Per the `FactionAPI.removeKnownShip`
 *    javadoc, the `.faction`-file blueprints are re-added on **every game load**, so this must
 *    re-run from `onGameLoad` each time -- which is exactly why the store keeps plain ids forever.
 * 2. **A periodic re-strip** ([EnforcerScript]) -- factions learn new blueprints mid-game (raids,
 *    story events, Nexerelin diplomacy), so once a day we quietly strip again.
 * 3. **A market-open cargo sweep** ([MarketSweepListener]) -- the safety net for stock that was
 *    generated before a strip landed, and for mod submarkets that fill their cargo without
 *    consulting known-lists at all.
 * 4. **Loot blocking** ([LootBlocker]) -- drops come from a separate pipeline that never looks at
 *    faction lists, so commodities, special items, weapons and fighters are kept out of it by
 *    zeroing drop-table weights and stamping the `no_drop` tags vanilla already honours.
 *
 * Ships are stripped everywhere (fleets, markets and loot); weapons and fighters stop being stocked
 * and stop dropping, but their specs stay loaded and predefined `.variant` loadouts keep working,
 * which is what keeps this crash-free.
 */
object Enforcer {

    private val log = Global.getLogger(Enforcer::class.java)

    /** Everything that needs to happen when a campaign becomes current. Called from `onGameLoad`. */
    @JvmStatic
    fun onGameLoad() {
        val sector = Global.getSector() ?: return
        stripFactions(reason = "game load")
        // Specs outlive the campaign, so this has to re-derive from scratch every load: the player
        // can change the blacklist at the main menu between two saves in the same session.
        runCatching { LootBlocker.apply() }
            .onFailure { log.error("StopBloatingMe: loot blocking failed; drops are unfiltered.", it) }
        // Transient on purpose: nothing of ours is ever written into the save, so removing the mod
        // can never corrupt one. The plugin re-adds all three on every load.
        sector.addTransientScript(EnforcerScript())
        sector.listenerManager.addListener(MarketSweepListener(), true)
        sector.listenerManager.addListener(LootSweepListener(), true)
    }

    /**
     * Removes every blacklisted id from every faction's spawn-driving lists.
     *
     * Bulk `removeAll` on the live sets rather than per-id `removeKnownShip()`, because the per-id
     * methods each call `clearShipRoleCache()` -- doing that hundreds of times per faction is the
     * one way this could get slow. We mutate directly and clear the cache once per faction, only
     * when something actually changed, which the `clearShipRoleCache` javadoc explicitly sanctions.
     */
    fun stripFactions(reason: String) {
        val sector = Global.getSector() ?: return
        val ships = BlacklistStore.ids(Category.SHIPS)
        val weapons = BlacklistStore.ids(Category.WEAPONS)
        val fighters = BlacklistStore.ids(Category.FIGHTERS)
        if (ships.isEmpty() && weapons.isEmpty() && fighters.isEmpty()) return

        val started = System.nanoTime()
        var touched = 0
        for (faction in sector.allFactions) {
            if (faction == null) continue
            if (stripFaction(faction, ships, weapons, fighters)) touched++
        }
        log.info(
            "StopBloatingMe: enforcement pass ($reason) touched $touched faction(s) in " +
                (System.nanoTime() - started) / 1_000_000 + " ms"
        )
    }

    private fun stripFaction(
        faction: FactionAPI,
        ships: Set<String>,
        weapons: Set<String>,
        fighters: Set<String>,
    ): Boolean {
        var changed = false
        if (ships.isNotEmpty()) {
            // All four drive ship spawning; hullFrequency would keep steering fleet composition
            // toward a hull even after it stopped being "known".
            var shipsChanged = faction.knownShips.removeAll(ships)
            if (faction.alwaysKnownShips.removeAll(ships)) shipsChanged = true
            if (faction.priorityShips.removeAll(ships)) shipsChanged = true
            if (faction.hullFrequency.keys.removeAll(ships)) shipsChanged = true
            if (shipsChanged) {
                faction.clearShipRoleCache()
                changed = true
            }
        }
        if (weapons.isNotEmpty()) {
            if (faction.knownWeapons.removeAll(weapons)) changed = true
            if (faction.priorityWeapons.removeAll(weapons)) changed = true
        }
        if (fighters.isNotEmpty()) {
            if (faction.knownFighters.removeAll(fighters)) changed = true
            if (faction.priorityFighters.removeAll(fighters)) changed = true
        }
        return changed
    }

    // --- Market sweep --------------------------------------------------------------------------

    /**
     * Removes blacklisted stock from every submarket of [market].
     *
     * Player property is sacred: storage-style submarkets (vanilla storage, local resources, and
     * any mod submarket with free transfer -- the defining trait of "this cargo is yours") are
     * never touched. Everything else is a shop, and a shop's stock is fair game.
     */
    fun sweepMarket(market: MarketAPI) {
        for (submarket in market.submarketsCopy) {
            if (submarket == null) continue
            if (submarket.specId == Submarkets.SUBMARKET_STORAGE) continue
            if (submarket.specId == Submarkets.LOCAL_RESOURCES) continue
            if (runCatching { submarket.plugin?.isFreeTransfer == true }.getOrDefault(false)) continue
            val cargo = runCatching { submarket.cargoNullOk }.getOrNull() ?: continue
            // Ships (including hulls listed as their auto-generated (D) version), weapons, fighter
            // chips and special items; commodity stock is the economy's business, not ours.
            runCatching { LootBlocker.sweep(cargo, includeCommodities = false) }
        }
    }

    // --- Codex ---------------------------------------------------------------------------------

    /**
     * Removes every blacklisted thing's codex entry, **after** the whole codex has been built and
     * linked.
     *
     * This used to work the other way round: stamp `HIDE_IN_CODEX` from `onAboutToStartGeneratingCodex`
     * so `populateWeapons()` and friends never created the entry in the first place. That was
     * cheaper, and it was safe against *vanilla*, whose cross-links all go through `getEntry()` plus
     * a null check. It was not safe against other mods. `CodexDataV2.init()` runs
     * `onAboutToLinkCodexEntries()` on every enabled mod plugin between populating and linking, and
     * a mod that links its own content to a vanilla entry has no reason to expect that entry to be
     * missing -- JaydeePiracy dereferences the result directly and took the title screen down with
     * an NPE the moment anything was blacklisted.
     *
     * So we let every entry be created and linked exactly as if the mod weren't installed, and prune
     * afterwards from `onCodexDataGenerated`, the last hook in `init()`. Three steps, because a
     * detached entry that something still points at would show up as a related-entry chip leading
     * nowhere: unhook each doomed entry from its parent, strip it out of every surviving entry's
     * related set, then rebuild the id map so `getEntry()` agrees with the tree.
     *
     * Nothing has rendered at this point -- `init()` is still on the stack -- so this is invisible.
     * The codex is generated once per process, so un-blocking shows things again on the next game
     * restart, not immediately. Auto-generated (D) hulls of a blocked base go too.
     */
    @JvmStatic
    fun pruneCodex() {
        val doomed = doomedEntryIds()
        if (doomed.isEmpty()) return
        val root = runCatching { CodexDataV2.ROOT }.getOrNull() ?: return

        val removed = HashSet<CodexEntryPlugin>()
        runCatching { detach(root, doomed, removed) }
            .onFailure { log.error("StopBloatingMe: could not prune the codex; leaving it intact.", it) }
        if (removed.isEmpty()) return

        runCatching { unlink(root, removed) }
        runCatching { CodexDataV2.rebuildIdToEntryMap() }
        log.info("StopBloatingMe: pruned ${removed.size} blacklisted entries from the codex.")
    }

    /** Codex entry ids for everything on the blacklist, in the `codex_<kind>_<id>` form the codex uses. */
    private fun doomedEntryIds(): Set<String> {
        val out = HashSet<String>()
        val ships = BlacklistStore.ids(Category.SHIPS)
        if (ships.isNotEmpty()) {
            for (spec in Global.getSettings().allShipHullSpecs) {
                if (spec == null) continue
                // The browser never lists auto-generated (D) hulls, so they are matched off the base
                // hull the same way the market sweep and the loot blocker do.
                val blocked = ships.contains(spec.hullId) ||
                    (runCatching { spec.isDefaultDHull }.getOrDefault(false) &&
                        ships.contains(runCatching { spec.baseHullId }.getOrNull()))
                if (blocked) out += CodexDataV2.getShipEntryId(spec.hullId)
            }
        }
        BlacklistStore.ids(Category.WEAPONS).forEach { out += CodexDataV2.getWeaponEntryId(it) }
        BlacklistStore.ids(Category.FIGHTERS).forEach { out += CodexDataV2.getFighterEntryId(it) }
        BlacklistStore.ids(Category.COMMODITIES).forEach { out += CodexDataV2.getCommodityEntryId(it) }
        BlacklistStore.ids(Category.ITEMS).forEach { out += CodexDataV2.getItemEntryId(it) }
        return out
    }

    /** Unhooks doomed entries from the tree, collecting them into [removed]. */
    private fun detach(node: CodexEntryPlugin, doomed: Set<String>, removed: MutableSet<CodexEntryPlugin>) {
        val children = node.children ?: return
        // Over a copy: we mutate the live list as we go.
        for (child in children.toList()) {
            if (child == null) continue
            if (doomed.contains(child.id)) {
                children.remove(child)
                removed += child
            } else {
                detach(child, doomed, removed)
            }
        }
    }

    /** Strips every reference to a removed entry out of what's left, so no chip points at a ghost. */
    private fun unlink(node: CodexEntryPlugin, removed: Set<CodexEntryPlugin>) {
        val goneIds = removed.mapNotNull { runCatching { it.id }.getOrNull() }.toSet()
        // getChildrenRecursive includes the node it's called on, so this is the whole surviving tree.
        for (entry in runCatching { node.getChildrenRecursive(true) }.getOrNull().orEmpty()) {
            if (entry == null) continue
            for (related in runCatching { entry.relatedEntries?.toList() }.getOrNull().orEmpty()) {
                if (related != null && removed.contains(related)) {
                    runCatching { entry.removeRelatedEntry(related) }
                }
            }
            // The unresolved id set too: it is what a later re-link would read from.
            for (id in runCatching { entry.relatedEntryIds?.toList() }.getOrNull().orEmpty()) {
                if (id != null && goneIds.contains(id)) runCatching { entry.removeRelatedEntry(id) }
            }
        }
    }
}

/**
 * Re-strips faction lists roughly once a game day, catching blueprints factions acquire mid-game.
 * A full pass is a handful of set operations per faction, so this is effectively free.
 */
class EnforcerScript : EveryFrameScript {

    private val interval = IntervalUtil(0.8f, 1.2f)

    override fun isDone(): Boolean = false

    override fun runWhilePaused(): Boolean = false

    override fun advance(amount: Float) {
        val days = Global.getSector()?.clock?.convertToDays(amount) ?: return
        interval.advance(days)
        if (interval.intervalElapsed()) {
            Enforcer.stripFactions(reason = "daily")
        }
    }
}

/** Sweeps a market's stock the moment the player opens it -- after the engine refreshes cargo. */
class MarketSweepListener : ColonyInteractionListener {

    override fun reportPlayerOpenedMarket(market: MarketAPI?) {}

    override fun reportPlayerOpenedMarketAndCargoUpdated(market: MarketAPI?) {
        market?.let { runCatching { Enforcer.sweepMarket(it) } }
    }

    override fun reportPlayerClosedMarket(market: MarketAPI?) {}

    override fun reportPlayerMarketTransaction(transaction: PlayerMarketTransaction?) {}
}
