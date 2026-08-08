package stopbloatingme

/**
 * The five things you can blacklist, and the labels their facet groups get in the browser.
 *
 * Each category exposes the same four facet groups so the UI can render them generically; only the
 * headings differ. [designLabel] is null where a category carries no third facet -- fighter wings
 * have no manufacturer, and commodities and special items have nothing worth a third column -- and
 * that group is simply omitted for them.
 */
enum class Category(
    val label: String,
    val primaryLabel: String,
    val secondaryLabel: String,
    val designLabel: String?,
    /** Label for the "show the entries hidden by default" toggle; see [Entry.hidden]. */
    val hiddenLabel: String,
) {
    SHIPS("Ships", "Size", "Designation", "Design type", "Include stations, modules and hulks"),
    WEAPONS("Weapons", "Type", "Mount", "Design type", "Include built-in and decorative weapons"),
    FIGHTERS("Fighters", "Role", "Tier", null, "Include hidden wings"),
    COMMODITIES("Commodities", "Class", "Demand class", null, "Include economy-internal entries"),
    ITEMS("Special items", "Type", "Tech", null, "Include hidden items"),
}
