package stopbloatingme

import com.fs.starfarer.api.Global
import org.json.JSONArray
import org.json.JSONObject
import org.lazywizard.lazylib.JSONUtils

/** One bar quest that exists only in code: its creator id, and the class that produced it. */
class LearnedBarEvent(val id: String, val className: String)

/**
 * The bar quests that can't be found from the main menu.
 *
 * Most bar quests are rows in a `bar_events.csv`, which the game parses at startup, so they can be
 * listed before any campaign exists. The rest -- vanilla's historian, delivery job, Tri-Tachyon
 * loan, Luddic farmer and the rest of that family, plus whatever mods register the same way -- are
 * only ever `addEventCreator()` calls made against a live sector. Outside a campaign there is
 * nothing to ask about them.
 *
 * So we remember them instead. Every game load walks the sector's creator list and writes down the
 * ones that came from code, into a file next to the blacklist in `saves/common/`. From then on they
 * appear in the browser like everything else, campaign or not.
 *
 * This means the tab is slightly short on a brand-new install and fills in the first time you load a
 * save -- which is unavoidable, because until then the information genuinely does not exist. Entries
 * are only ever added, never pruned: an id from a mod you've since disabled costs one row and starts
 * working again the moment the mod comes back, which is the same bargain [BlacklistStore] makes.
 */
object LearnedBarEvents {

    /** Path under `saves/common/`. Starsector appends `.data` to the file on disk. */
    private const val COMMON_FILE = "stopbloatingme/bar_events_seen.json"

    private const val FORMAT_VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_CREATORS = "creators"
    private const val KEY_ID = "id"
    private const val KEY_CLASS = "class"

    private val log = Global.getLogger(LearnedBarEvents::class.java)

    /** Keyed by id so a creator that changed class between mod versions updates rather than doubles. */
    private val known = LinkedHashMap<String, LearnedBarEvent>()

    private var loaded = false

    // --- Load / save ---------------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true          // set first: a failed read must not retry on every frame
        runCatching { readFile() }.onFailure {
            log.error("StopBloatingMe: could not read $COMMON_FILE; bar quests added in code will " +
                "be missing from the browser until the next game load.", it)
        }
    }

    private fun readFile() {
        val json: JSONObject = JSONUtils.loadCommonJSON(COMMON_FILE)
        if (json.length() == 0) return          // no file yet (or an empty one): nothing to restore
        val array = json.optJSONArray(KEY_CREATORS) ?: return
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val id = row.optString(KEY_ID, "").trim()
            if (id.isEmpty()) continue
            known[id] = LearnedBarEvent(id, row.optString(KEY_CLASS, "").trim())
        }
    }

    private fun save() {
        runCatching {
            val json = JSONUtils.CommonDataJSONObject(COMMON_FILE)
            json.put(KEY_VERSION, FORMAT_VERSION)
            val array = JSONArray()
            for (creator in known.values) {
                array.put(JSONObject().put(KEY_ID, creator.id).put(KEY_CLASS, creator.className))
            }
            json.put(KEY_CREATORS, array)
            json.save()
        }.onFailure {
            log.error("StopBloatingMe: could not write $COMMON_FILE; this session's discoveries are " +
                "in memory only.", it)
        }
    }

    // --- Queries -------------------------------------------------------------------------------

    fun all(): Collection<LearnedBarEvent> {
        ensureLoaded()
        return known.values
    }

    // --- Mutations -----------------------------------------------------------------------------

    /**
     * Records everything in [found], writing only if it actually taught us something.
     *
     * Returns true when the set changed, which is the browser's cue that its catalogue is now a
     * version behind -- see [ContentIndex.invalidate].
     */
    fun record(found: Collection<LearnedBarEvent>): Boolean {
        ensureLoaded()
        var changed = false
        for (creator in found) {
            if (creator.id.isEmpty()) continue
            if (known[creator.id]?.className == creator.className) continue
            known[creator.id] = creator
            changed = true
        }
        if (changed) {
            save()
            log.info("StopBloatingMe: know of ${known.size} bar quest(s) that only exist in code.")
        }
        return changed
    }
}
