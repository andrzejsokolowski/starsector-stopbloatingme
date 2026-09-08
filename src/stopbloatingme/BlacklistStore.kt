package stopbloatingme

import com.fs.starfarer.api.Global
import org.json.JSONArray
import org.json.JSONObject
import org.lazywizard.lazylib.JSONUtils

/**
 * Per-**installation** storage for the blacklist: every ship, weapon, fighter, commodity, special
 * item and bar quest you have told the game to stop showing you.
 *
 * Backed by a single JSON file in Starsector's common data folder (`saves/common/` -- see
 * [COMMON_FILE]), never by the save, so curating your collection once carries into every future
 * playthrough. That's the whole point of the mod: the bloat is a property of your mod list, not of
 * one campaign. It also means the browser works at the main menu, where there is no campaign to
 * write to.
 *
 * Everything is stored as **plain ids**; nothing here ever resolves an id to a spec. That keeps the
 * store safe across mod-list changes -- ids belonging to a mod you've currently disabled are carried
 * through untouched and start matching again the moment it comes back.
 *
 * Written on every change. The file is tiny and edits are user-driven, so a crash or an alt-F4 can
 * never lose a decision.
 */
object BlacklistStore {

    /** Path under `saves/common/`. Starsector appends `.data` to the file on disk. */
    private const val COMMON_FILE = "stopbloatingme/blacklist.json"

    private const val FORMAT_VERSION = 1
    private const val KEY_VERSION = "version"

    private val log = Global.getLogger(BlacklistStore::class.java)

    private val sets: Map<Category, MutableSet<String>> =
        Category.entries.associateWith { LinkedHashSet<String>() }

    private var loaded = false

    // --- Load / save ---------------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true          // set first: a failed read must not retry on every frame
        runCatching { readFile() }.onFailure {
            log.error("StopBloatingMe: could not read $COMMON_FILE, starting from an empty blacklist.", it)
        }
    }

    private fun readFile() {
        val json: JSONObject = JSONUtils.loadCommonJSON(COMMON_FILE)
        if (json.length() == 0) return          // no file yet (or an empty one): nothing to restore
        for (category in Category.entries) {
            val array = json.optJSONArray(category.key()) ?: continue
            val into = sets.getValue(category)
            for (i in 0 until array.length()) {
                val id = array.optString(i, "").trim()
                if (id.isNotEmpty()) into.add(id)
            }
        }
    }

    /** Writes the whole store back. Never throws -- a failed write is logged and the in-memory state
     *  stays authoritative for the rest of the session. */
    private fun save() {
        runCatching {
            val json = JSONUtils.CommonDataJSONObject(COMMON_FILE)
            json.put(KEY_VERSION, FORMAT_VERSION)
            for (category in Category.entries) {
                json.put(category.key(), JSONArray(sets.getValue(category)))
            }
            json.save()
        }.onFailure {
            log.error("StopBloatingMe: could not write $COMMON_FILE; this session's changes are in memory only.", it)
        }
    }

    private fun Category.key(): String = name.lowercase()

    // --- Queries -------------------------------------------------------------------------------

    fun ids(category: Category): Set<String> = mutable(category)

    private fun mutable(category: Category): MutableSet<String> {
        ensureLoaded()
        return sets.getValue(category)
    }

    fun isBlacklisted(category: Category, id: String): Boolean = ids(category).contains(id)

    fun count(category: Category): Int = ids(category).size

    /** Every blocked id in every category -- what the reset button is offering to erase. */
    fun totalCount(): Int = Category.entries.sumOf { count(it) }

    /** Where the store lives, for the "this is shared by every save" note in the UI. */
    fun location(): String = "saves/common/$COMMON_FILE"

    // --- Mutations -----------------------------------------------------------------------------

    /** Toggles one id. Returns the new state (true = now blacklisted). */
    fun toggle(category: Category, id: String): Boolean {
        val set = mutable(category)
        val nowBlacklisted = if (set.remove(id)) false else { set.add(id); true }
        save()
        return nowBlacklisted
    }

    /** Blacklists every id in [entries]. One write for the whole batch. */
    fun addAll(category: Category, entries: Collection<Entry>) {
        if (entries.isEmpty()) return
        val set = mutable(category)
        entries.forEach { set.add(it.id) }
        save()
    }

    /** Un-blacklists every id in [entries]. One write for the whole batch. */
    fun removeAll(category: Category, entries: Collection<Entry>) {
        if (entries.isEmpty()) return
        val set = mutable(category)
        entries.forEach { set.remove(it.id) }
        save()
    }

    /**
     * Erases the whole store: every category, back to nothing.
     *
     * This is the only way to undo choices made in an earlier session, because the store is shared
     * by every save rather than living in one campaign. The file is overwritten with empty lists
     * rather than deleted, so a partly-written file can never be left behind.
     *
     * The UI gates this behind a confirm step -- see the reset button in [BrowserPanel].
     */
    fun clearAll() {
        ensureLoaded()
        sets.values.forEach { it.clear() }
        save()
        log.info("StopBloatingMe: blacklist reset; ${location()} is now empty.")
    }
}
