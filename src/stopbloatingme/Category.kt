package stopbloatingme

/**
 * The three things you can blacklist, and the labels their facet groups get in the browser.
 *
 * Each category exposes the same four facet groups so the UI can render them generically; only the
 * headings differ. [designLabel] is null for fighter wings, which carry no manufacturer -- that group
 * is simply omitted for them.
 */
enum class Category(
    val label: String,
    val primaryLabel: String,
    val secondaryLabel: String,
    val designLabel: String?,
) {
    SHIPS("Ships", "Size", "Designation", "Design type"),
    WEAPONS("Weapons", "Type", "Mount", "Design type"),
    FIGHTERS("Fighters", "Role", "Tier", null),
}
