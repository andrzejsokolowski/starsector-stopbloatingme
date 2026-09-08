package stopbloatingme

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.ModSpecAPI
import com.fs.starfarer.api.impl.campaign.ids.Tags
import com.fs.starfarer.api.loading.BarEventSpec
import org.json.JSONArray

/**
 * The bar-quest half of the catalogue.
 *
 * This category is built by hand rather than read off a spec list like the other five, because a
 * bar quest is not one kind of thing:
 *
 * - Most are rows in a `bar_events.csv`. The game parses every mod's copy into one merged list at
 *   startup, so [BarEventSpec] gives us the whole set without a campaign.
 * - The rest are `addEventCreator()` calls against a live sector -- vanilla's historian, delivery
 *   job, Tri-Tachyon loan and friends, and the dozen-odd mods that register the same way. Those are
 *   remembered across sessions by [LearnedBarEvents]; see its notes for why the tab can start out
 *   slightly short.
 *
 * Neither kind carries a source mod or a display name, which the other five categories get for
 * free, so both have to be worked out: see [specOwners] and [displayName].
 */
object BarEventIndex {

    private const val BAR_EVENTS_CSV = "data/campaign/bar_events.csv"
    private const val ID_COLUMN = "bar event id"
    private const val PLUGIN_COLUMN = "plugin"

    /** Source-mod value for a quest we can see but can't pin on anyone. */
    private const val UNIDENTIFIED = "Unidentified mod"

    private const val VANILLA_PACKAGE = "com.fs.starfarer."

    private val log = Global.getLogger(BarEventIndex::class.java)

    fun build(): List<Entry> {
        val out = ArrayList<Entry>(64)
        val seen = HashSet<String>()
        val owners = runCatching { specOwners() }
            .onFailure { log.error("StopBloatingMe: could not attribute bar quests to mods.", it) }
            .getOrDefault(emptyMap())

        for (spec in specs()) {
            val id = runCatching { spec.id }.getOrNull()?.trim().orEmpty()
            if (id.isEmpty() || !seen.add(id)) continue
            // specOwners fills in every live id, so the fallback is only reached if it threw.
            out += fromSpec(spec, id, owners[id] ?: UNIDENTIFIED)
        }
        for (learned in LearnedBarEvents.all()) {
            if (!seen.add(learned.id)) continue
            out += fromLearned(learned)
        }
        return out.sortedBy { it.name.lowercase() }
    }

    private fun specs(): List<BarEventSpec> =
        runCatching { Global.getSettings().allBarEventSpecs }.getOrNull()?.filterNotNull().orEmpty()

    // --- Entries -------------------------------------------------------------------------------

    private fun fromSpec(spec: BarEventSpec, id: String, sourceMod: String): Entry {
        // A quest with a matching person-mission spec is contract work that contacts hand out too,
        // which is worth saying plainly: blocking it here only quiets the bar.
        val mission = runCatching { Global.getSettings().getMissionSpec(id) }.getOrNull()
        val priority = runCatching { spec.hasTag(Tags.MISSION_PRIORITY) }.getOrDefault(false)
        return Entry(
            id = id,
            name = displayName(runCatching { spec.pluginClass }.getOrNull(), id),
            primary = frequencyLabel(runCatching { spec.freq }.getOrDefault(0f)),
            secondary = if (mission != null) "Bar and contacts" else "Bar only",
            design = "",
            sourceMod = sourceMod,
            hidden = priority,
            sprite = runCatching { mission?.icon }.getOrNull().orEmpty(),
            note = note(alsoFromContacts = mission != null, priority = priority, learned = false),
        )
    }

    private fun fromLearned(learned: LearnedBarEvent): Entry = Entry(
        id = learned.id,
        name = displayName(learned.className, learned.id),
        // Code-added creators set their weight in Java rather than in a row we can read, and the
        // default they inherit is vanilla's 10, so "Common" is the honest answer here.
        primary = "Common",
        secondary = "Bar only",
        design = "",
        sourceMod = modOf(learned.className),
        hidden = false,
        sprite = "",
        note = note(alsoFromContacts = false, priority = false, learned = true),
    )

    private fun note(alsoFromContacts: Boolean, priority: Boolean, learned: Boolean): String =
        buildString {
            append("Blocking clears the offer out of bars. Quests you have already accepted are ")
            append("never touched.")
            if (alsoFromContacts) {
                append(" Contacts hand this one out as a job as well, and that stays.")
            }
            if (learned) {
                append(" This one is added in code rather than in a data file, so it was picked up ")
                append("from a campaign you loaded.")
            }
            if (priority) {
                append(" STORY-CRITICAL: the game gives this quest priority over ordinary ones, ")
                append("which usually means a questline starts here. Blocking it can end that line ")
                append("before it begins.")
            }
        }

    // --- Labels --------------------------------------------------------------------------------

    /**
     * How often the game will reach for this quest, from its frequency weight.
     *
     * Vanilla uses 1, 5 and 10, and mods stay in that neighbourhood, so the buckets are cut around
     * those rather than spread evenly. The exact weight is meaningless on its own -- it only matters
     * next to every other weight in the pool -- which is why this is a word and not a number.
     */
    private fun frequencyLabel(freq: Float): String = when {
        freq <= 0f -> "Never"
        freq <= 2f -> "Rare"
        freq <= 6f -> "Uncommon"
        freq <= 12f -> "Common"
        else -> "Very common"
    }

    /**
     * A name a player can recognise, from the only thing on offer: the class that implements it.
     *
     * Bar quests have no display name anywhere. Their ids are shorthand (`cheapCom`, `ddro`, `psb`)
     * and the text you actually read in the bar is generated on the spot from the market and the
     * person, so there is nothing static to show. The class name is the one human-written label in
     * the whole spec, and it is usually a good one: `RelicOfThePastBarEvent` becomes "Relic Of The
     * Past", `LuddicFarmerBarEventCreator` becomes "Luddic Farmer".
     */
    private fun displayName(pluginClass: String?, id: String): String {
        var simple = pluginClass.orEmpty().substringAfterLast('.').trim()
        for (suffix in NOISE_SUFFIXES) {
            if (simple.length > suffix.length && simple.endsWith(suffix)) {
                simple = simple.dropLast(suffix.length)
                break
            }
        }
        return splitWords(simple.replace('_', ' ')).ifBlank { id }
    }

    /** Suffixes that say what the class is rather than what the quest is. Longest first. */
    private val NOISE_SUFFIXES = listOf("BarEventCreator", "BarEvent", "Creator")

    /**
     * Splits `MercsOnTheRun` into `Mercs On The Run` while leaving acronyms alone, so `UAFCrate`
     * comes out as `UAF Crate` rather than `U A F Crate`. A capital starts a new word only when the
     * character before it isn't a capital, or when the one after it is lower case, which is the
     * boundary at the tail of an acronym.
     */
    private fun splitWords(raw: String): String {
        val out = StringBuilder(raw.length + 8)
        for ((index, c) in raw.withIndex()) {
            if (index > 0 && c.isUpperCase()) {
                val previous = raw[index - 1]
                val next = if (index + 1 < raw.length) raw[index + 1] else null
                if (!previous.isUpperCase() || (next != null && next.isLowerCase())) out.append(' ')
            }
            out.append(c)
        }
        return out.toString().split(' ').filter { it.isNotBlank() }
            .joinToString(" ").replaceFirstChar { it.uppercaseChar() }
    }

    // --- Source mod ----------------------------------------------------------------------------

    /**
     * Which mod owns each bar-quest spec, worked out by reading every enabled mod's own copy of
     * `bar_events.csv`.
     *
     * A [BarEventSpec] has no source mod on it, unlike hulls, weapons and the rest, which carry
     * their origin; the game hands out one merged list with nothing to say where a row came from.
     * So we go back to the files.
     *
     * Two things make that less obvious than it sounds.
     *
     * **The base game's rows have to be subtracted first.** There is no API that asks whether a mod
     * ships a given file, and `loadCSV(path, modId)` for a mod that ships none is not documented to
     * come back empty -- if it falls back to the core file, a naive read hands every mod vanilla's
     * rows and credits all of vanilla's quests to whichever mod was scanned last. So a row counts as
     * a mod's own only when that exact id-and-plugin pair is absent from the core file. Vanilla is
     * then read off the core file directly rather than inferred from nobody having claimed a quest,
     * and overrides come out right for free: More Military Missions redefines `cheapCom` with its
     * own plugin class, so its row survives the subtraction and the quest is credited to it.
     *
     * **A row only counts if its plugin class is the one that reached the loaded spec.** When two
     * mods define the same id exactly one wins the merge, and that is the one whose name belongs in
     * the column.
     *
     * If two mods ever hand back the same set of rows, the per-mod read isn't per-mod at all and
     * every answer it gives is guesswork, so the whole pass is abandoned rather than trusted. The
     * column then reads "Vanilla" for what the core file proves and [UNIDENTIFIED] for the rest,
     * which is worth far more than a confident wrong mod name.
     */
    private fun specOwners(): Map<String, String> {
        val settings = Global.getSettings()

        val livePlugin = HashMap<String, String>()
        for (spec in specs()) {
            val id = runCatching { spec.id }.getOrNull()?.trim() ?: continue
            livePlugin[id] = runCatching { spec.pluginClass }.getOrNull()?.trim().orEmpty()
        }
        if (livePlugin.isEmpty()) return emptyMap()

        val core = HashSet<Pair<String, String>>()
        runCatching { settings.loadCSV(BAR_EVENTS_CSV, false) }.getOrNull()?.let { rows ->
            forEachRow(rows) { id, plugin -> core.add(id to plugin) }
        }

        val started = System.nanoTime()
        val owners = HashMap<String, String>()
        for ((id, plugin) in livePlugin) {
            if (core.contains(id to plugin)) owners[id] = ContentIndex.VANILLA
        }

        val rowSets = HashMap<Set<Pair<String, String>>, String>()
        var probed = 0
        var trustworthy = true
        for (mod in enabledMods()) {
            val modId = runCatching { mod.id }.getOrNull()?.trim().orEmpty()
            if (modId.isEmpty()) continue
            // A mod without the file has to announce itself by throwing; there is nothing to ask.
            // This runs once per process, the first time the browser is opened.
            val rows = runCatching { settings.loadCSV(BAR_EVENTS_CSV, modId) }.getOrNull() ?: continue
            val own = LinkedHashSet<Pair<String, String>>()
            forEachRow(rows) { id, plugin -> if (!core.contains(id to plugin)) own.add(id to plugin) }
            if (own.isEmpty()) continue
            probed++
            val modName = runCatching { mod.name }.getOrNull()?.trim().orEmpty().ifBlank { modId }
            val alreadySeenOn = rowSets.put(own, modName)
            if (alreadySeenOn != null) {
                log.warn(
                    "StopBloatingMe: $modId and $alreadySeenOn report the same bar quest rows, so " +
                        "the per-mod read is not per-mod on this install. Dropping mod attribution " +
                        "for bar quests rather than guessing."
                )
                trustworthy = false
                break
            }
            for ((id, plugin) in own) {
                if (livePlugin[id] == plugin) owners[id] = modName
            }
        }
        if (!trustworthy) owners.values.retainAll(setOf(ContentIndex.VANILLA))

        // Vanilla is what the core file says, so anything left over is modded but unattributable --
        // unless the core file itself failed to load, in which case absence proves nothing and the
        // old assumption is the better one.
        val fallback = if (core.isEmpty()) ContentIndex.VANILLA else UNIDENTIFIED
        for (id in livePlugin.keys) owners.putIfAbsent(id, fallback)

        log.info(
            "StopBloatingMe: attributed " + owners.size + " bar quest(s) across " + probed +
                " mod file(s) in " + (System.nanoTime() - started) / 1_000_000 + " ms"
        )
        return owners
    }

    /** Walks a parsed `bar_events.csv`, skipping blanks and the `#`-prefixed rows modders leave in. */
    private inline fun forEachRow(rows: JSONArray, action: (id: String, plugin: String) -> Unit) {
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val id = row.optString(ID_COLUMN, "").trim()
            if (id.isEmpty() || id.startsWith("#")) continue
            action(id, row.optString(PLUGIN_COLUMN, "").trim())
        }
    }

    /**
     * Best guess at which mod a code-added quest came from, by matching its class against the
     * package each mod's own entry-point class lives in.
     *
     * There is no file to read here and no API that maps a class back to the mod that shipped it, so
     * the package is all there is. It works whenever a mod uses a package of its own, as Nexerelin
     * does with its creators under `exerelin` right next to `exerelin.ExerelinModPlugin`, and gives
     * up honestly when a mod uses one of the generic roots half the ecosystem shares. Naming the
     * wrong mod would be worse than admitting we don't know.
     */
    private fun modOf(className: String): String {
        if (className.isEmpty()) return UNIDENTIFIED
        val classPath = className.split('.')
        var best = ""
        var bestDepth = 0
        for (mod in enabledMods()) {
            val plugin = runCatching { mod.modPluginClassName }.getOrNull()?.trim().orEmpty()
            if (plugin.isEmpty()) continue
            val depth = sharedPackageDepth(classPath, plugin.split('.'))
            if (depth <= bestDepth) continue
            bestDepth = depth
            best = runCatching { mod.name }.getOrNull()?.trim().orEmpty()
                .ifBlank { runCatching { mod.id }.getOrNull().orEmpty() }
        }
        if (best.isNotEmpty()) return best
        if (className.startsWith(VANILLA_PACKAGE)) return ContentIndex.VANILLA
        return UNIDENTIFIED
    }

    /** Leading package segments two classes share, or 0 when the first one they share tells us nothing. */
    private fun sharedPackageDepth(a: List<String>, b: List<String>): Int {
        val limit = minOf(a.size, b.size) - 1          // never count the class name itself
        var depth = 0
        while (depth < limit && a[depth] == b[depth]) depth++
        if (depth == 0) return 0
        if (a[0] in GENERIC_ROOTS) return 0
        return depth
    }

    /** Package roots too many mods share to identify anyone by. */
    private val GENERIC_ROOTS = setOf("com", "org", "net", "io", "java", "kotlin", "data", "scripts")

    private fun enabledMods(): List<ModSpecAPI> =
        runCatching { Global.getSettings().modManager?.enabledModsCopy }
            .getOrNull()?.filterNotNull().orEmpty()
}
