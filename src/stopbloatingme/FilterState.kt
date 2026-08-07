package stopbloatingme

/** Which side of the blacklist the list should show. */
enum class Visibility(val label: String) {
    ALL("All"),
    ALLOWED("Allowed"),
    BLOCKED("Blocked"),
}

/** The four facet groups every category exposes. */
enum class FacetGroup {
    PRIMARY,
    SECONDARY,
    DESIGN,
    MOD,
}

/**
 * Filter selections for one category. Kept per-category so switching tabs doesn't throw away what
 * you'd set up on the other one.
 *
 * Within a facet group the selected values are OR'd; across groups they're AND'd. An empty group
 * means "no constraint", which is why clearing a group is the same as selecting everything in it.
 */
class CategoryFilter {
    var search = ""
    var visibility = Visibility.ALL

    /** Stations, modules, (D) duplicates and built-in weapons are off by default; see [Entry.hidden]. */
    var showHidden = false

    val selected: Map<FacetGroup, MutableSet<String>> =
        FacetGroup.entries.associateWith { LinkedHashSet<String>() }

    fun of(group: FacetGroup): MutableSet<String> = selected.getValue(group)

    fun clear() {
        search = ""
        visibility = Visibility.ALL
        showHidden = false
        selected.values.forEach { it.clear() }
    }

    /** True when anything at all is narrowing the list -- drives the "Clear filters" button state. */
    fun isNarrowing(): Boolean =
        search.isNotBlank() || visibility != Visibility.ALL || showHidden ||
            selected.values.any { it.isNotEmpty() }

    /** Cheap fingerprint of everything that affects the result, so we only re-filter when it moves. */
    fun signature(): String = buildString {
        append(search.trim().lowercase()).append('|')
        append(visibility.name).append('|')
        append(showHidden).append('|')
        for (group in FacetGroup.entries) {
            append(group.name).append('=')
            of(group).sorted().joinTo(this, ",")
            append(';')
        }
    }
}

/**
 * Session state of the browser: which tab is open and how each one is filtered.
 *
 * Deliberately a singleton that outlives the panel, so closing and reopening the browser puts you
 * back where you were. Blacklist *membership* lives in [BlacklistStore]; this is only which view of
 * it you're looking at.
 */
object FilterState {

    var category = Category.SHIPS

    private val filters: Map<Category, CategoryFilter> =
        Category.entries.associateWith { CategoryFilter() }

    fun of(category: Category): CategoryFilter = filters.getValue(category)

    fun current(): CategoryFilter = of(category)

    /**
     * The entries the list should show, in index order (already sorted by name).
     *
     * Ordered cheapest-test-first: the facet checks are set lookups on interned-ish strings and the
     * substring search runs last, so a narrow facet selection short-circuits most of the scan. Over
     * 8,644 hulls this is comfortably sub-millisecond, which is why the browser can afford to
     * re-filter on a signature change rather than maintaining incremental indexes.
     */
    fun apply(category: Category): List<Entry> {
        val filter = of(category)
        val query = filter.search.trim().lowercase()
        val blacklist = BlacklistStore.ids(category)

        val primary = filter.of(FacetGroup.PRIMARY)
        val secondary = filter.of(FacetGroup.SECONDARY)
        val design = filter.of(FacetGroup.DESIGN)
        val mods = filter.of(FacetGroup.MOD)

        val out = ArrayList<Entry>(256)
        for (entry in ContentIndex.entries(category)) {
            if (entry.hidden && !filter.showHidden) continue

            val blocked = blacklist.contains(entry.id)
            when (filter.visibility) {
                Visibility.ALLOWED -> if (blocked) continue
                Visibility.BLOCKED -> if (!blocked) continue
                Visibility.ALL -> {}
            }

            if (primary.isNotEmpty() && !primary.contains(entry.primary)) continue
            if (secondary.isNotEmpty() && !secondary.contains(entry.secondary)) continue
            if (design.isNotEmpty() && !design.contains(entry.design)) continue
            if (mods.isNotEmpty() && !mods.contains(entry.sourceMod)) continue
            if (query.isNotEmpty() && !entry.searchBlob.contains(query)) continue

            out += entry
        }
        return out
    }

    /**
     * Distinct values of [group] within [category], each with how many entries carry it, ordered for
     * display. Size and mount-size get their natural order; everything else falls back to
     * most-common-first so the values worth clicking float to the top of a long list.
     */
    fun facetValues(category: Category, group: FacetGroup): List<Pair<String, Int>> {
        val counts = LinkedHashMap<String, Int>()
        for (entry in ContentIndex.entries(category)) {
            if (entry.hidden) continue          // facet lists describe the default view
            val value = entry.valueFor(group)
            if (value.isBlank()) continue
            counts[value] = (counts[value] ?: 0) + 1
        }
        return counts.entries
            .sortedWith(compareBy({ naturalRank(it.key) }, { -it.value }, { it.key.lowercase() }))
            .map { it.key to it.value }
    }

    private fun Entry.valueFor(group: FacetGroup): String = when (group) {
        FacetGroup.PRIMARY -> primary
        FacetGroup.SECONDARY -> secondary
        FacetGroup.DESIGN -> design
        FacetGroup.MOD -> sourceMod
    }

    /** Hull sizes and mount sizes read wrong alphabetically; pin them to their real progression. */
    private val NATURAL_ORDER = listOf(
        "Frigate", "Destroyer", "Cruiser", "Capital",
        "Small", "Medium", "Large",
    ).withIndex().associate { (index, value) -> value to index }

    private fun naturalRank(value: String): Int = NATURAL_ORDER[value] ?: Int.MAX_VALUE
}
