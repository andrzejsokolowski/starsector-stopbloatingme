package stopbloatingme

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.CargoAPI
import com.fs.starfarer.api.campaign.InteractionDialogAPI
import com.fs.starfarer.api.campaign.listeners.ShowLootListener
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.impl.campaign.procgen.DropGroupRow

/**
 * The half of enforcement that keeps blacklisted things out of **loot**.
 *
 * Nearly all procedural loot in the game funnels through one method,
 * `SalvageEntity.generateSalvage(...)` -- salvaging wrecks, post-battle debris, tech mining,
 * procgen caches, planet surveys, blueprint specials. It rolls on a `DropData`, which is either a
 * named group out of `drop_groups.csv` or a picker built in code, and every pick ends up as a
 * [DropGroupRow] naming a commodity, `wpn_<id>`, `ftr_<id>` or `item_<id>`.
 *
 * Two layers, because the two halves of that pipeline need different levers:
 *
 * 1. **Spec-level blocking** ([apply]) -- the primary lever, and the one that stops the loot ever
 *    existing rather than taking it away afterwards. Three mechanisms, all of them things vanilla
 *    already does to its own content:
 *    - **Zeroing a drop row's frequency.** `DropGroupRow.getPicker()` re-reads `getFreq()` on every
 *      roll and `WeightedRandomPicker.add()` ignores anything weighing `<= 0`, so a zeroed row is
 *      not in the pool at all. Its weight goes to the rest of the group, so a wreck still yields as
 *      much loot -- just none of it the blocked thing. This is what catches every commodity and
 *      every *named* special item, since those are always fixed rows.
 *    - **The `no_drop` tag.** Checked against commodity, weapon, fighter and special-item specs all
 *      over the codebase (`FleetEncounterContext`, `CoreScript.addMiscToDropData`, `BlueprintIntel`,
 *      ground raids), and used by the `!no_drop` filters in the *rolled* rows of `drop_groups.csv`.
 *      Vanilla tags `omega_core` and the XIV blueprint package with it for exactly this reason.
 *    - **Zeroing rarity.** The weight a rolled `wpn_`/`ftr_` row uses when picking a specific spec.
 *
 * 2. **A cargo sweep** ([sweep]) -- the net under everything else, run from [LootSweepListener] just
 *    before any loot panel is shown. `DropData` pickers built in code are **serialised into the
 *    save** attached to the entity that owns them, so drops baked into a campaign before a thing was
 *    blocked never consult `drop_groups.csv` again; and mods can hand out cargo without going near
 *    the drop system at all. Both land here.
 *
 * Everything [apply] changes is **reverted first and re-applied from scratch** on every pass. Specs
 * live for the life of the process, not the campaign, so unblocking something at the main menu and
 * loading a save has to actually take effect -- and quitting to the menu must not leave the previous
 * session's blacklist welded onto the specs.
 */
object LootBlocker {

    private val log = Global.getLogger(LootBlocker::class.java)

    /** How to put back everything [apply] changed, newest first. */
    private val undo = ArrayList<() -> Unit>()

    private var rowsZeroed = 0
    private var specsTouched = 0

    // --- Spec-level blocking -------------------------------------------------------------------

    /**
     * Re-derives every spec-level block from the current blacklist. Idempotent, and safe to call as
     * often as you like.
     */
    fun apply() {
        revert()

        val ships = BlacklistStore.ids(Category.SHIPS)
        val weapons = BlacklistStore.ids(Category.WEAPONS)
        val fighters = BlacklistStore.ids(Category.FIGHTERS)
        val commodities = BlacklistStore.ids(Category.COMMODITIES)
        val items = BlacklistStore.ids(Category.ITEMS)
        if (ships.isEmpty() && weapons.isEmpty() && fighters.isEmpty() &&
            commodities.isEmpty() && items.isEmpty()
        ) return

        val started = System.nanoTime()
        tagSpecs(ships, weapons, fighters, commodities, items)
        zeroDropRows(weapons, fighters, commodities, items)
        log.info(
            "StopBloatingMe: loot blocking touched $rowsZeroed drop row(s) and $specsTouched spec(s) " +
                "in " + (System.nanoTime() - started) / 1_000_000 + " ms"
        )
        verifyRowsStuck()
    }

    /**
     * Confirms `getAllSpecs()` handed out the live drop rows rather than copies.
     *
     * Everything else here leans on documented behaviour, but this one leans on the spec store
     * returning the objects the picker will later read. It does today; if a future release ever
     * hands out clones, zeroing them would fail *silently* and drops would quietly come back. So
     * one row is read back through a fresh `getAllSpecs()` call, and a mismatch says so in the log
     * -- the tags and the cargo sweep still hold the line, just less cleanly.
     */
    private fun verifyRowsStuck() {
        if (rowsZeroed == 0) return
        val stuck = runCatching {
            Global.getSettings().getAllSpecs(DropGroupRow::class.java)
                .any { it != null && it.freq <= 0f }
        }.getOrDefault(true)
        if (!stuck) {
            log.warn(
                "StopBloatingMe: zeroed $rowsZeroed drop row(s) but the spec store still reports them " +
                    "as weighted, so getAllSpecs(DropGroupRow) is handing out copies. Blocked loot " +
                    "will be removed by the loot-panel sweep instead of never being generated."
            )
        }
    }

    /** Puts every spec we touched back the way we found it. */
    fun revert() {
        for (i in undo.indices.reversed()) runCatching { undo[i]() }
        undo.clear()
        rowsZeroed = 0
        specsTouched = 0
    }

    /**
     * Stamps `no_drop` (and `no_bp_drop`) onto every blacklisted spec, and zeroes the rarity weight
     * of blacklisted weapons and fighter wings.
     *
     * The hull pass is what stops a blocked *ship* still turning up as a blueprint: the
     * `item_ship_bp:{tags:[rare_bp, !no_drop, !restricted]}` row in `drop_groups.csv` resolves
     * through `ShipBlueprintItemPlugin.pickShip()`, which filters **hull** specs by those tags, and
     * raid blueprint loot checks `no_bp_drop` on the hull directly.
     */
    private fun tagSpecs(
        ships: Set<String>,
        weapons: Set<String>,
        fighters: Set<String>,
        commodities: Set<String>,
        items: Set<String>,
    ) {
        val settings = Global.getSettings()

        if (ships.isNotEmpty()) {
            for (spec in settings.allShipHullSpecs) {
                if (spec == null) continue
                // Auto-generated (D) hulls are never blacklisted on their own -- the browser doesn't
                // list them -- so match them off their base, the way the market sweep does.
                val blocked = ships.contains(spec.hullId) ||
                    (runCatching { spec.isDefaultDHull }.getOrDefault(false) &&
                        ships.contains(runCatching { spec.baseHullId }.getOrNull()))
                if (!blocked) continue
                tag(tagsOf { spec.tags }, Tags.NO_DROP, Tags.NO_BP_DROP)
            }
        }
        if (weapons.isNotEmpty()) {
            for (spec in settings.allWeaponSpecs) {
                if (spec == null || !weapons.contains(spec.weaponId)) continue
                tag(tagsOf { spec.tags }, Tags.NO_DROP, Tags.NO_BP_DROP)
                val was = runCatching { spec.rarity }.getOrDefault(0f)
                if (was > 0f && runCatching { spec.rarity = 0f }.isSuccess) {
                    undo += { spec.rarity = was }
                }
            }
        }
        if (fighters.isNotEmpty()) {
            for (spec in settings.allFighterWingSpecs) {
                if (spec == null || !fighters.contains(spec.id)) continue
                // WING_NO_DROP is the strongest of the three: DropGroupRow filters on it both when
                // building a group's picker and when resolving a rolled `ftr_` row.
                tag(tagsOf { spec.tags }, Tags.WING_NO_DROP, Tags.NO_DROP, Tags.NO_BP_DROP)
                val was = runCatching { spec.rarity }.getOrDefault(0f)
                if (was > 0f && runCatching { spec.rarity = 0f }.isSuccess) {
                    undo += { spec.rarity = was }
                }
            }
        }
        if (commodities.isNotEmpty()) {
            for (spec in settings.allCommoditySpecs) {
                if (spec == null || !commodities.contains(spec.id)) continue
                tag(tagsOf { spec.tags }, Tags.NO_DROP)
            }
        }
        if (items.isNotEmpty()) {
            for (spec in settings.allSpecialItemSpecs) {
                if (spec == null || !items.contains(spec.id)) continue
                tag(tagsOf { spec.tags }, Tags.NO_DROP)
            }
        }
    }

    /**
     * Zeroes the frequency of every `drop_groups.csv` row that names something blacklisted.
     *
     * The one hazard is emptying a group: `DropGroupRow.getPicker()` throws outright if *every* row
     * in a group weighs zero, which would crash the next salvage roll that used it. So each group is
     * left with at least one positive-weight row.
     *
     * Usually that costs nothing, because most groups carry a `nothing` row -- never blockable, so
     * always a survivor -- and blocking every real entry in such a group correctly leaves it silent:
     * `getPicker()` renormalises the `nothing` weight to zero, `pick()` still returns that row
     * because the item list isn't empty, and the caller skips it. The guard only bites on a group
     * with no `nothing` row whose every entry is blacklisted, and that case is logged rather than
     * swallowed, because blocking less than asked without saying so is worse than saying so.
     */
    private fun zeroDropRows(
        weapons: Set<String>,
        fighters: Set<String>,
        commodities: Set<String>,
        items: Set<String>,
    ) {
        val all = runCatching { Global.getSettings().getAllSpecs(DropGroupRow::class.java) }
            .getOrNull() ?: return

        // Group first: whether a row can be zeroed depends on its neighbours, not on the row.
        val byGroup = LinkedHashMap<String, MutableList<DropGroupRow>>()
        for (row in all) {
            if (row == null) continue
            val group = runCatching { row.group }.getOrNull() ?: continue
            byGroup.getOrPut(group) { ArrayList() } += row
        }

        for ((group, rows) in byGroup) {
            val live = rows.filter { runCatching { it.freq }.getOrDefault(0f) > 0f }
            val doomed = live.filter { isBlocked(it, weapons, fighters, commodities, items) }
            if (doomed.isEmpty()) continue

            val wouldEmpty = doomed.size >= live.size
            val toZero = if (wouldEmpty) doomed.dropLast(1) else doomed
            for (row in toZero) {
                val was = runCatching { row.freq }.getOrDefault(0f)
                if (runCatching { row.freq = 0f }.isFailure) continue
                undo += { row.freq = was }
                rowsZeroed++
            }
            if (wouldEmpty) {
                log.warn(
                    "StopBloatingMe: every entry in drop group [$group] is blacklisted and it has no " +
                        "'nothing' row, so [${runCatching { doomed.last().commodity }.getOrNull()}] " +
                        "is being left in it -- a drop group with no positive-weight row crashes the " +
                        "next salvage roll that uses it."
                )
            }
        }
    }

    /** Whether a fixed drop row names something on the blacklist. Rolled rows are the tags' job. */
    private fun isBlocked(
        row: DropGroupRow,
        weapons: Set<String>,
        fighters: Set<String>,
        commodities: Set<String>,
        items: Set<String>,
    ): Boolean = runCatching {
        // A multi-valued row -- `wpn_:{tier:3}`, `item_modspec:{}` -- names a filter, not a thing,
        // and only resolves to a specific id at roll time. Zeroing one of those would take out
        // everything else it can roll along with the blocked entry, so those go through the tags.
        if (row.isMultiValued || row.isNothing) return@runCatching false
        when {
            row.isWeapon -> weapons.contains(row.weaponId)
            row.isFighterWing -> fighters.contains(row.fighterWingId)
            row.isSpecialItem -> items.contains(row.specialItemId)
            else -> commodities.contains(row.commodity)
        }
    }.getOrDefault(false)

    /**
     * The spec's live tag set, or null if it can't be reached. Only `WeaponSpecAPI`,
     * `FighterWingSpecAPI` and `ShipHullSpecAPI` expose `addTag`; commodities and special items only
     * hand out the set, so going through the set is the one route that works for all five.
     */
    @Suppress("UNCHECKED_CAST")
    private fun tagsOf(supplier: () -> Set<String>?): MutableSet<String>? =
        runCatching { supplier() as? MutableSet<String> }.getOrNull()

    /** Adds tags the spec doesn't already carry, remembering only the ones we actually added. */
    private fun tag(tags: MutableSet<String>?, vararg toAdd: String) {
        if (tags == null) return
        var added = false
        for (t in toAdd) {
            if (tags.contains(t)) continue          // vanilla's own doing, and not ours to undo
            if (runCatching { tags.add(t) }.getOrDefault(false)) {
                undo += { tags.remove(t) }
                added = true
            }
        }
        if (added) specsTouched++
    }

    // --- Cargo sweep ---------------------------------------------------------------------------

    /**
     * Removes every blacklisted stack from [cargo]. Returns how many stacks went.
     *
     * Works off `getStacksCopy()` and `removeStack()` rather than the per-type removal calls, so
     * commodities, weapons, fighter chips and special items are all handled in one pass -- and so
     * iteration is over a copy, which is what makes removing while looping safe.
     *
     * [includeCommodities] is off for the market sweep. Blocking a commodity is a statement about
     * *loot*, and a market's commodity stock is the economy's, not a drop table's -- pulling
     * supplies off a shop that its colony demands would be a different and much larger change than
     * the one the player asked for.
     */
    fun sweep(cargo: CargoAPI?, includeCommodities: Boolean = true): Int {
        if (cargo == null) return 0
        val ships = BlacklistStore.ids(Category.SHIPS)
        val weapons = BlacklistStore.ids(Category.WEAPONS)
        val fighters = BlacklistStore.ids(Category.FIGHTERS)
        val commodities =
            if (includeCommodities) BlacklistStore.ids(Category.COMMODITIES) else emptySet()
        val items = BlacklistStore.ids(Category.ITEMS)
        if (ships.isEmpty() && weapons.isEmpty() && fighters.isEmpty() &&
            commodities.isEmpty() && items.isEmpty()
        ) return 0

        var removed = 0
        for (stack in runCatching { cargo.stacksCopy }.getOrNull().orEmpty()) {
            if (stack == null || runCatching { stack.isNull }.getOrDefault(true)) continue
            val blocked = runCatching {
                when {
                    stack.isCommodityStack -> commodities.contains(stack.resourceIfResource?.id)
                    stack.isSpecialStack -> items.contains(stack.specialItemSpecIfSpecial?.id)
                    stack.weaponSpecIfWeapon != null ->
                        weapons.contains(stack.weaponSpecIfWeapon?.weaponId)
                    stack.fighterWingSpecIfWing != null ->
                        fighters.contains(stack.fighterWingSpecIfWing?.id)
                    else -> false
                }
            }.getOrDefault(false)
            if (!blocked) continue
            if (runCatching { cargo.removeStack(stack) }.isSuccess) removed++
        }

        // Mothballed hulls ride in the cargo's own fleet, not in its stacks.
        if (ships.isNotEmpty()) {
            val mothballed = runCatching { cargo.mothballedShips }.getOrNull()
            for (member in runCatching { mothballed?.membersListCopy }.getOrNull().orEmpty()) {
                if (member == null) continue
                val blocked = ships.contains(member.hullId) ||
                    ships.contains(runCatching { member.hullSpec?.baseHullId }.getOrNull())
                if (blocked && runCatching { mothballed?.removeFleetMember(member) }.isSuccess) {
                    removed++
                }
            }
        }
        return removed
    }
}

/**
 * Strips blacklisted loot out of the cargo the engine is about to put on screen.
 *
 * `reportAboutToShowLootToPlayer` fires from `ListenerUtil` immediately before *any* loot panel is
 * built, with the cargo still mutable -- the one choke point every source of player-facing loot
 * passes through, whichever mod or script produced it.
 *
 * Mission items never reach this code: [ContentIndex] leaves them out of the browser entirely, so
 * they cannot be blacklisted and a scripted quest reward cannot be swallowed here.
 */
class LootSweepListener : ShowLootListener {

    override fun reportAboutToShowLootToPlayer(loot: CargoAPI?, dialog: InteractionDialogAPI?) {
        runCatching { LootBlocker.sweep(loot) }
    }
}
