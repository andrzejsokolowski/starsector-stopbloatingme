package stopbloatingme.uiframework

// Vendored from Refit Filters by Starficz. Copyright Starficz, Licensed under LGPL-3.0-only.
// https://www.gnu.org/licenses/lgpl-3.0.html

data class Flag(var isEnabled: Boolean = true) {
    var isFiltered: Boolean
        get() = !isEnabled
        set(filtered) { isEnabled = !filtered }
}