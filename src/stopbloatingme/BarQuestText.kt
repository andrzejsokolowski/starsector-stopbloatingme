package stopbloatingme

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.rules.MemKeys
import com.fs.starfarer.api.campaign.rules.MemoryAPI
import com.fs.starfarer.api.campaign.rules.RulesAPI
import org.json.JSONArray
import org.json.JSONObject
import org.lazywizard.lazylib.JSONUtils

/** What a bar quest actually says: the line you click, and the paragraph above it. */
class BarQuestLines(val option: String, val blurb: String) {
    fun isEmpty(): Boolean = option.isBlank() && blurb.isBlank()
}

/**
 * The words each bar quest puts on screen, so you can recognise one without knowing its name.
 *
 * Nobody remembers bar quests as "Mercs On The Run". They remember *"a table of tattooed roughs are
 * drinking alarming amounts of liquor"*, because that is what the bar shows you. So the browser has
 * to show it too, or the list is a wall of names that mean nothing.
 *
 * The text is reachable because most bar quests don't hold it in code at all. A quest that is really
 * a mission draws its bar entry by firing two rules through the dialogue engine -- `<id>_blurbBar`
 * for the paragraph and `<id>_optionBar` for the clickable line -- and those rules are rows in
 * `rules.csv`. Asking [RulesAPI] for everything matching those two triggers gets both strings with
 * no dialogue, no market and no side effects, because matching a rule is not running it.
 *
 * Two limits worth being straight about:
 *
 * - **It needs a campaign.** `getRules()` hangs off the sector, and the browser lives on the main
 *   menu, so the text is harvested on every game load and cached in `saves/common/` next to the
 *   blacklist -- the same arrangement [LearnedBarEvents] uses, for the same reason.
 * - **Quests that build their bar entry in Java get nothing.** Vanilla's historian, delivery job,
 *   Tri-Tachyon loan and the rest of the hand-registered family write their prompt straight to the
 *   text panel from code that only runs inside a real dialogue. There is no honest way to read that
 *   without running their code in a context it was never written for, which is a good way to break
 *   somebody's save, so those rows simply say the text can't be read.
 */
object BarQuestText {

    /** Path under `saves/common/`. Starsector appends `.data` to the file on disk. */
    private const val COMMON_FILE = "stopbloatingme/bar_events_text.json"

    private const val FORMAT_VERSION = 1
    private const val KEY_VERSION = "version"
    private const val KEY_QUESTS = "quests"
    private const val KEY_ID = "id"
    private const val KEY_OPTION = "option"
    private const val KEY_BLURB = "blurb"

    private const val BLURB_TRIGGER = "_blurbBar"
    private const val OPTION_TRIGGER = "_optionBar"

    private val log = Global.getLogger(BarQuestText::class.java)

    private val known = LinkedHashMap<String, BarQuestLines>()

    private var loaded = false

    // --- Load / save ---------------------------------------------------------------------------

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true          // set first: a failed read must not retry on every frame
        runCatching { readFile() }.onFailure {
            log.error("StopBloatingMe: could not read $COMMON_FILE; bar quests will be listed by " +
                "name only.", it)
        }
    }

    private fun readFile() {
        val json: JSONObject = JSONUtils.loadCommonJSON(COMMON_FILE)
        if (json.length() == 0) return          // no file yet (or an empty one): nothing to restore
        val array = json.optJSONArray(KEY_QUESTS) ?: return
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val id = row.optString(KEY_ID, "").trim()
            if (id.isEmpty()) continue
            known[id] = BarQuestLines(row.optString(KEY_OPTION, ""), row.optString(KEY_BLURB, ""))
        }
    }

    private fun save() {
        runCatching {
            val json = JSONUtils.CommonDataJSONObject(COMMON_FILE)
            json.put(KEY_VERSION, FORMAT_VERSION)
            val array = JSONArray()
            for ((id, lines) in known) {
                array.put(
                    JSONObject().put(KEY_ID, id).put(KEY_OPTION, lines.option)
                        .put(KEY_BLURB, lines.blurb)
                )
            }
            json.put(KEY_QUESTS, array)
            json.save()
        }.onFailure {
            log.error("StopBloatingMe: could not write $COMMON_FILE; this session's bar text is in " +
                "memory only.", it)
        }
    }

    // --- Queries -------------------------------------------------------------------------------

    fun of(id: String): BarQuestLines? {
        ensureLoaded()
        return known[id]
    }

    /**
     * True before any campaign has been loaded on this install, when we have nothing at all.
     *
     * Worth telling the player apart from "this particular quest has no readable text", because the
     * two look identical in the browser and only one of them is fixed by loading a save.
     */
    fun nothingHarvestedYet(): Boolean {
        ensureLoaded()
        return known.isEmpty()
    }

    // --- Harvest -------------------------------------------------------------------------------

    /**
     * Reads the bar text for every quest we know of. Cheap: rules are already parsed and indexed by
     * trigger, so this is a couple of hash lookups per quest, not a scan of the 13 MB of `rules.csv`
     * a big mod list adds up to.
     */
    fun harvest() {
        ensureLoaded()
        val sector = Global.getSector() ?: return
        val rules = runCatching { sector.rules }.getOrNull() ?: return
        // Rule conditions are tested against memory. Handing over the global memory keeps that
        // working for the rules that check it, and the ones that want a market or a person simply
        // don't match -- which is what we want, since their unconditional sibling is the general
        // version of the text and the one worth showing.
        val memory = runCatching {
            HashMap<String, MemoryAPI>().apply { put(MemKeys.GLOBAL, sector.memoryWithoutUpdate) }
        }.getOrNull() ?: return

        var changed = false
        for (id in questIds()) {
            val found = runCatching { read(rules, memory, id) }.getOrNull() ?: continue
            if (found.isEmpty()) continue
            val existing = known[id]
            if (existing != null && existing.option == found.option && existing.blurb == found.blurb) {
                continue
            }
            known[id] = found
            changed = true
        }
        if (changed) {
            save()
            log.info("StopBloatingMe: have the bar text for ${known.size} quest(s).")
            ContentIndex.invalidate()
        }
    }

    private fun read(rules: RulesAPI, memory: Map<String, MemoryAPI>, id: String): BarQuestLines {
        val blurb = rules.matching(memory, id + BLURB_TRIGGER)
            .firstNotNullOfOrNull { rule ->
                runCatching { rule.text }.getOrNull()?.filterNotNull()
                    ?.firstOrNull { it.isNotBlank() }
            }
        val option = rules.matching(memory, id + OPTION_TRIGGER)
            .firstNotNullOfOrNull { rule ->
                runCatching { rule.options }.getOrNull()?.filterNotNull()
                    ?.firstOrNull { !it.text.isNullOrBlank() }?.text
            }
        return BarQuestLines(readable(option), readable(blurb))
    }

    /**
     * Every rule on a trigger, in the engine's own order.
     *
     * More than one row can answer the same trigger -- vanilla writes a second, seedier version of
     * the procurement blurb for underworld contacts -- and the engine puts the more specific ones
     * first. We take the first that produces text, which is the one you are most likely to have
     * read; listing every variant would turn a two-line preview into a page.
     */
    private fun RulesAPI.matching(memory: Map<String, MemoryAPI>, trigger: String) =
        runCatching { getAllMatching(null, trigger, null, memory) }
            .getOrNull()?.filterNotNull().orEmpty()

    /** Every bar quest the browser can list, whether or not it turns out to have readable text. */
    private fun questIds(): Set<String> {
        val out = LinkedHashSet<String>()
        runCatching { Global.getSettings().allBarEventSpecs }.getOrNull()?.forEach { spec ->
            runCatching { spec?.id }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        runCatching { LearnedBarEvents.all() }.getOrNull()?.forEach { out.add(it.id) }
        return out
    }

    // --- Token substitution --------------------------------------------------------------------

    /**
     * Turns a rule's raw text into something a human can read outside a dialogue.
     *
     * Rule text is written with `$token` placeholders the engine fills in from the person and place
     * you're actually talking to, and there is nobody to fill them in from here. Rather than leave
     * `$cheapCom_manOrWoman` sitting in the middle of a sentence, the pronoun tokens -- which are
     * most of them, and the only ones that break the grammar -- get a neutral stand-in, and anything
     * else is reduced to its own name with the quest-id prefix stripped. The sentence survives, and
     * survival is the whole point: this text exists to be recognised, not to be quoted.
     */
    private fun readable(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return TOKEN.replace(text) { standIn(it.value.removePrefix("$")) }
            .replace(WHITESPACE, " ").trim()
    }

    private val TOKEN = Regex("\\$[A-Za-z0-9_]+")
    private val WHITESPACE = Regex("\\s+")

    /**
     * A neutral word to put where a token was.
     *
     * The vocabulary in bar rules turns out to be tiny: across a 250-mod install it is 64 uses of
     * `manOrWoman`, a handful of pronouns, and single uses of four others. So these are chosen to
     * make real sentences rather than to be clever. `$sShip_rankAOrAn $sShip_rank` becoming
     * "an officer" is the whole exercise -- it is the difference between a line you recognise and a
     * line with debris in it.
     */
    private fun standIn(token: String): String {
        val segments = token.split('_').filter { it.isNotBlank() }
        if (segments.isEmpty()) return ""
        // A trailing `name` or `desc` says what kind of value the token holds, not what the thing
        // is, so step back one: `mmm_em_fleet_name` is a fleet, `mmm_rm_station_desc` is a station.
        var word = segments.last()
        if (word.lowercase() in DESCRIPTOR_SEGMENTS && segments.size > 1) {
            word = segments[segments.size - 2]
        }
        val key = word.lowercase()
        EXACT[key]?.let { return it }
        for ((suffix, replacement) in SUFFIXES) {
            if (key.endsWith(suffix)) return replacement
        }
        return spaced(word)
    }

    private val DESCRIPTOR_SEGMENTS = setOf("name", "desc", "description", "id", "title", "label")

    private val EXACT = mapOf(
        "rank" to "officer",
        "thefaction" to "the faction",
        "faction" to "a faction",
    )

    /** Longest first: `rankAOrAn` has to be caught before the bare `aOrAn` rule sees it. */
    private val SUFFIXES = listOf(
        "rankaoran" to "an",
        "manorwoman" to "person",
        "womanorman" to "person",
        "sirormadam" to "captain",
        "sirormaam" to "captain",
        "playername" to "you",
        "hisorher" to "their",
        "herorhis" to "their",
        "himorher" to "them",
        "heorshe" to "they",
        "sheorhe" to "they",
        "aoran" to "a",
    )

    /** `heavyIndustry` reads better as `heavy industry` once it is standing in for a real noun. */
    private fun spaced(token: String): String {
        val out = StringBuilder(token.length + 4)
        for ((index, c) in token.withIndex()) {
            if (index > 0 && c.isUpperCase() && !token[index - 1].isUpperCase()) out.append(' ')
            out.append(c)
        }
        return out.toString().lowercase()
    }
}
