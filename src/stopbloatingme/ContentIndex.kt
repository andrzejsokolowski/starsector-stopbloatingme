package stopbloatingme

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ModSpecAPI
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipHullSpecAPI
import com.fs.starfarer.api.combat.WeaponAPI
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.loading.WingRole

/**
 * One browsable thing. Everything the list and the filters need is resolved once, at index time, so
 * the per-keystroke filter pass is nothing but field reads and one substring test.
 */
class Entry(
    val id: String,
    val name: String,
    val primary: String,
    val secondary: String,
    val design: String,
    val sourceMod: String,
    /** True for things that aren't really ownable products: stations, modules, fighter hulls,
     *  built-in/decorative weapons, and the accounting-only commodities the economy uses to talk to
     *  itself. Hidden unless the player asks to see them. */
    val hidden: Boolean,
    /** Sprite path for the hover preview, or "" when the spec doesn't declare one. Resolved at index
     *  time so hovering never has to go back to the spec. */
    val sprite: String = "",
) {
    /** Lowercased haystack for the search box: name, id, every facet value, and the source mod. */
    val searchBlob: String =
        "$name $id $primary $secondary $design $sourceMod".lowercase()
}

/**
 * The catalogue of every ship hull, weapon, fighter wing, commodity and special item the current mod
 * list defines.
 *
 * Built once and cached for the rest of the process. The spike measured a full scan of the three
 * big categories at **5 ms** on a 244-mod install (8,644 hulls / 1,801 weapons / 667 wings), and
 * commodities and special items are two orders of magnitude smaller, so there is no loading state
 * and no background thread here -- it is cheaper to just build it than to manage the machinery for
 * not building it.
 *
 * Source mod comes straight off the spec: all five spec types implement `WithSourceMod`, so
 * attribution needs no CSV parsing. Specs with no source mod are vanilla.
 */
object ContentIndex {

    const val VANILLA = "Vanilla"
    private const val UNSPECIFIED = "Unspecified"

    private var cache: Map<Category, List<Entry>>? = null

    fun entries(category: Category): List<Entry> = index()[category].orEmpty()

    /** Drops the cache. Only needed if specs are ever reloaded mid-process. */
    fun invalidate() {
        cache = null
    }

    private fun index(): Map<Category, List<Entry>> {
        cache?.let { return it }
        val started = System.nanoTime()
        val built = mapOf(
            Category.SHIPS to buildShips(),
            Category.WEAPONS to buildWeapons(),
            Category.FIGHTERS to buildFighters(),
            Category.COMMODITIES to buildCommodities(),
            Category.ITEMS to buildItems(),
        )
        cache = built
        Global.getLogger(ContentIndex::class.java).info(
            "StopBloatingMe: indexed " + built.values.sumOf { it.size } + " entries in " +
                (System.nanoTime() - started) / 1_000_000 + " ms"
        )
        return built
    }

    // --- Builders ------------------------------------------------------------------------------

    private fun buildShips(): List<Entry> {
        val specs = Global.getSettings().allShipHullSpecs
        val out = ArrayList<Entry>(specs.size)
        for (spec in specs) {
            if (spec == null) continue
            // Auto-generated (D) hulls never appear at all, not even behind the "show hidden" toggle.
            // They aren't independently spawnable: DModManager.setDHull() takes an already-picked
            // variant of the BASE hull and swaps in "<base>_D" afterwards, so blocking the base
            // already stops the (D) version, and blocking "<base>_D" on its own could never do
            // anything -- faction blueprint lists only ever hold the base id. Listing them would be
            // offering a button that cannot work. Hand-authored damaged hulls (isDHull without
            // isDefaultDHull) are real content and stay.
            if (runCatching { spec.isDefaultDHull }.getOrDefault(false)) continue
            out += Entry(
                id = spec.hullId,
                name = spec.hullName.orEmpty().ifBlank { spec.hullId },
                primary = sizeLabel(spec.hullSize),
                secondary = spec.designation.clean(),
                design = spec.manufacturer.clean(),
                sourceMod = modName(runCatching { spec.sourceMod }.getOrNull()),
                hidden = !isOwnableHull(spec),
                sprite = spec.spriteName.orEmpty(),
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun buildWeapons(): List<Entry> {
        val specs = Global.getSettings().allWeaponSpecs
        val out = ArrayList<Entry>(specs.size)
        for (spec in specs) {
            if (spec == null) continue
            out += Entry(
                id = spec.weaponId,
                name = spec.weaponName.orEmpty().ifBlank { spec.weaponId },
                primary = spec.type?.displayName ?: UNSPECIFIED,
                secondary = spec.size?.displayName ?: UNSPECIFIED,
                design = spec.manufacturer.clean(),
                sourceMod = modName(runCatching { spec.sourceMod }.getOrNull()),
                hidden = spec.type in NON_PRODUCT_WEAPON_TYPES,
                // Turret art is the recognisable view; hardpoint-only weapons fall back to theirs.
                sprite = spec.turretSpriteName.orEmpty().ifBlank { spec.hardpointSpriteName.orEmpty() },
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun buildFighters(): List<Entry> {
        val specs = Global.getSettings().allFighterWingSpecs
        val out = ArrayList<Entry>(specs.size)
        for (spec in specs) {
            if (spec == null) continue
            out += Entry(
                id = spec.id,
                name = spec.wingName.orEmpty().ifBlank { spec.id },
                primary = roleLabel(spec.role),
                secondary = "Tier ${spec.tier}",
                design = "",
                sourceMod = modName(runCatching { spec.sourceMod }.getOrNull()),
                hidden = false,
                // A wing has no art of its own; its single fighter's hull carries the sprite.
                sprite = runCatching { spec.variant?.hullSpec?.spriteName }.getOrNull().orEmpty(),
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun buildCommodities(): List<Entry> {
        val specs = Global.getSettings().allCommoditySpecs
        val out = ArrayList<Entry>(specs.size)
        for (spec in specs) {
            if (spec == null) continue
            out += Entry(
                id = spec.id,
                name = spec.name.orEmpty().ifBlank { spec.id },
                primary = commodityClass(spec),
                secondary = spec.demandClass.clean(),
                design = "",
                sourceMod = modName(runCatching { spec.sourceMod }.getOrNull()),
                // Meta commodities (`ships`, `blueprints`, `credits`) are accounting rows for the
                // economy, not cargo anyone can hold, and the ones vanilla already keeps out of the
                // codex (`ai_cores`, `survey_data`) are demand-class placeholders in the same vein.
                // The tag read here is always vanilla's own: we never stamp HIDE_IN_CODEX on
                // anything -- codex hiding works by pruning entries, not by marking specs.
                hidden = runCatching { spec.isMeta }.getOrDefault(false) ||
                    runCatching { spec.hasTag(Tags.HIDE_IN_CODEX) }.getOrDefault(false),
                sprite = spec.iconName.orEmpty(),
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun buildItems(): List<Entry> {
        val specs = Global.getSettings().allSpecialItemSpecs
        val out = ArrayList<Entry>(specs.size)
        for (spec in specs) {
            if (spec == null) continue
            // Mission items (janus device, planetkiller, wormhole scanner) never come out of a loot
            // roll -- scripted missions hand them over directly. Listing them would offer a button
            // whose only possible effect is breaking the quest that needs the item, so they are
            // dropped from the index outright rather than merely hidden. Same call as the
            // auto-generated (D) hulls over in buildShips.
            if (runCatching { spec.hasTag(Tags.MISSION_ITEM) }.getOrDefault(false)) continue
            out += Entry(
                id = spec.id,
                name = spec.name.orEmpty().ifBlank { spec.id },
                primary = itemType(spec),
                secondary = spec.manufacturer.clean(),
                design = "",
                sourceMod = modName(runCatching { spec.sourceMod }.getOrNull()),
                hidden = runCatching { spec.hasTag(Tags.HIDE_IN_CODEX) }.getOrDefault(false),
                sprite = spec.iconName.orEmpty(),
            )
        }
        return out.sortedBy { it.name.lowercase() }
    }

    // --- Labels --------------------------------------------------------------------------------

    /**
     * Which shelf a commodity sits on. Checked most-specific-first: a meta commodity is also
     * non-economic, and `lobster` is both exotic and a luxury good.
     */
    private fun commodityClass(spec: CommoditySpecAPI): String = when {
        runCatching { spec.isMeta }.getOrDefault(false) -> "Meta"
        runCatching { spec.isExotic }.getOrDefault(false) -> "Exotic"
        runCatching { spec.isNonEcon }.getOrDefault(false) -> "Non-economic"
        runCatching { spec.isPrimary }.getOrDefault(false) -> "Primary"
        else -> "Standard"
    }

    /**
     * Special items have no type field, only tags, so the type is read off the tags vanilla and mods
     * already use. `single_bp` covers the three generic blueprint items (`ship_bp`, `weapon_bp`,
     * `fighter_bp`) -- blocking one of those stops *every* blueprint of that kind dropping, which is
     * a much bigger hammer than blocking one hull, so it is worth naming clearly.
     */
    private fun itemType(spec: SpecialItemSpecAPI): String {
        fun has(tag: String) = runCatching { spec.hasTag(tag) }.getOrDefault(false)
        return when {
            has("colony_item") -> "Colony item"
            has("package_bp") -> "Blueprint package"
            has("single_bp") -> "Blueprint (any)"
            has("modspec") -> "Hullmod spec"
            has("ai_core") -> "AI core"
            has("threat") || has("monster") -> "Alien tech"
            else -> UNSPECIFIED
        }
    }


    /**
     * Hulls that aren't ownable vessels: station hulls and loose module parts, which can't be bought
     * or flown, and fighter hulls, which belong to their wing over in the fighters tab. Shown only
     * when the player asks for them. (Auto-generated (D) hulls are dropped from the index outright
     * rather than merely hidden -- see [buildShips].)
     */
    private fun isOwnableHull(spec: ShipHullSpecAPI): Boolean {
        val hints = spec.hints ?: return true
        if (hints.contains(ShipHullSpecAPI.ShipTypeHints.STATION)) return false
        if (hints.contains(ShipHullSpecAPI.ShipTypeHints.MODULE)) return false
        if (hints.contains(ShipHullSpecAPI.ShipTypeHints.UNDER_PARENT)) return false
        if (spec.hullSize == ShipAPI.HullSize.FIGHTER) return false
        return true
    }

    private val NON_PRODUCT_WEAPON_TYPES = setOf(
        WeaponAPI.WeaponType.BUILT_IN,
        WeaponAPI.WeaponType.DECORATIVE,
        WeaponAPI.WeaponType.SYSTEM,
        WeaponAPI.WeaponType.STATION_MODULE,
        WeaponAPI.WeaponType.LAUNCH_BAY,
    )

    private fun sizeLabel(size: ShipAPI.HullSize?): String = when (size) {
        ShipAPI.HullSize.FRIGATE -> "Frigate"
        ShipAPI.HullSize.DESTROYER -> "Destroyer"
        ShipAPI.HullSize.CRUISER -> "Cruiser"
        ShipAPI.HullSize.CAPITAL_SHIP -> "Capital"
        ShipAPI.HullSize.FIGHTER -> "Fighter"
        else -> UNSPECIFIED
    }

    private fun roleLabel(role: WingRole?): String = when (role) {
        WingRole.INTERCEPTOR -> "Interceptor"
        WingRole.FIGHTER -> "Fighter"
        WingRole.BOMBER -> "Bomber"
        WingRole.ASSAULT -> "Assault"
        WingRole.SUPPORT -> "Support"
        else -> UNSPECIFIED
    }

    private fun modName(mod: ModSpecAPI?): String = mod?.name?.trim().orEmpty().ifBlank { VANILLA }

    private fun String?.clean(): String = this?.trim().orEmpty().ifBlank { UNSPECIFIED }
}
