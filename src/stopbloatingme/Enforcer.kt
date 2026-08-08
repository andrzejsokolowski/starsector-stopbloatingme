package stopbloatingme

import com.fs.starfarer.api.EveryFrameScript
import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.FactionAPI
import com.fs.starfarer.api.campaign.PlayerMarketTransaction
import com.fs.starfarer.api.campaign.econ.MarketAPI
import com.fs.starfarer.api.campaign.listeners.ColonyInteractionListener
import com.fs.starfarer.api.combat.ShipHullSpecAPI.ShipTypeHints
import com.fs.starfarer.api.impl.campaign.ids.Submarkets
import com.fs.starfarer.api.impl.campaign.ids.Tags
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
     * Stamps codex-hiding onto every blacklisted spec: the `HIDE_IN_CODEX` *hint* for ship hulls,
     * and the equivalent `Tags.HIDE_IN_CODEX` *tag* for weapons and fighter wings -- which is the
     * check `CodexDataV2.populateWeapons()`/`populateFighters()` make, and a tag vanilla content
     * already uses on its own weapons.
     *
     * Crash-safe by construction: this is called from `onAboutToStartGeneratingCodex`, which fires
     * at the top of codex generation during application load, before any category is built -- so a
     * hidden entry is simply never created. Every cross-link the generator makes afterwards goes
     * through `getEntry()` + a null check (or the null-checked `addRelatedEntry`), so a visible
     * ship whose variant mounts a hidden weapon just loses that one "related entry" chip.
     *
     * The codex is generated once per process, so un-blocking shows things again on the next game
     * restart, not immediately. Auto-generated (D) hulls of a blocked base are hidden too.
     *
     * Commodities and special items are deliberately **not** hidden. There are a few dozen of each,
     * so they are not what makes a codex unreadable, and hiding them would be self-defeating: the
     * browser reads `HIDE_IN_CODEX` off those two spec types to decide which entries are engine
     * plumbing rather than cargo (see [ContentIndex]), so stamping the tag on a blocked commodity
     * would quietly drop it out of the very list you'd use to unblock it.
     */
    @JvmStatic
    fun applyCodexHiding() {
        val ships = BlacklistStore.ids(Category.SHIPS)
        val weapons = BlacklistStore.ids(Category.WEAPONS)
        val fighters = BlacklistStore.ids(Category.FIGHTERS)
        if (ships.isEmpty() && weapons.isEmpty() && fighters.isEmpty()) return

        var hidden = 0
        if (ships.isNotEmpty()) {
            for (spec in Global.getSettings().allShipHullSpecs) {
                if (spec == null) continue
                val blocked = ships.contains(spec.hullId) ||
                    (runCatching { spec.isDefaultDHull }.getOrDefault(false) &&
                        ships.contains(runCatching { spec.baseHullId }.getOrNull()))
                if (blocked && runCatching { spec.hints?.add(ShipTypeHints.HIDE_IN_CODEX) }.getOrNull() == true) {
                    hidden++
                }
            }
        }
        if (weapons.isNotEmpty()) {
            for (spec in Global.getSettings().allWeaponSpecs) {
                if (spec == null || !weapons.contains(spec.weaponId)) continue
                if (runCatching { spec.addTag(Tags.HIDE_IN_CODEX) }.isSuccess) hidden++
            }
        }
        if (fighters.isNotEmpty()) {
            for (spec in Global.getSettings().allFighterWingSpecs) {
                if (spec == null || !fighters.contains(spec.id)) continue
                if (runCatching { spec.addTag(Tags.HIDE_IN_CODEX) }.isSuccess) hidden++
            }
        }
        if (hidden > 0) log.info("StopBloatingMe: hid $hidden blacklisted entries from the codex.")
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
