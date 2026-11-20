package io.github.betterzslf.feature.services.presentation

/** Stable product-owned catalog key; never an executable route or a service locator key. */
@JvmInline
value class ServiceEntryId(val value: String) {
    init { require(value.isNotBlank() && value.length <= 96 && value.none(Char::isISOControl)) }
}

enum class ServiceAccent { Primary, Positive, Warning, Secondary, Neutral }

data class ServiceEntryUi(
    val id: ServiceEntryId,
    val title: String,
    val accent: ServiceAccent,
    val summary: String? = null,
    val enabled: Boolean = true,
) {
    init { require(title.isNotBlank()) }
}

data class ServiceSectionUi(val id: String, val title: String, val entries: List<ServiceEntryUi>) {
    init { require(id.isNotBlank() && title.isNotBlank()) }
}

/** A renderable snapshot. Products supply the catalog; UI knows neither edition nor application graph. */
class ServicesUiState(
    title: String,
    val introduction: String,
    sections: List<ServiceSectionUi>,
    /**
     * Search affordance copy.
     *
     * Injected by the product rather than read from `AppText` here: `:feature:services:ui` may
     * depend only on `:design-system` and this module, so the UI cannot reach `core:text`, and a
     * Han literal is not allowed in this module either. Passing the copy the same way `title` and
     * every section/entry title already arrive satisfies both guards and keeps the UI a pure
     * function of the snapshot.
     */
    val searchLabel: String,
    val searchEmptyLabel: String,
) {
    val title: String = title.also { require(it.isNotBlank()) }

    val sections: List<ServiceSectionUi> = SnapshotList(sections.map { it.copy(entries = SnapshotList(it.entries)) })

    init {
        require(this.sections.map { it.id }.distinct().size == this.sections.size)
        val ids = this.sections.flatMap { it.entries }.map { it.id }
        require(ids.distinct().size == ids.size) { "Duplicate service entry id" }
    }

    /** Selection is an intent only. Disabled/unknown entries never dispatch an action. */
    fun eventFor(id: ServiceEntryId): ServicesEvent.Selected? =
        sections.asSequence().flatMap { it.entries.asSequence() }
            .firstOrNull { it.id == id && it.enabled }?.let { ServicesEvent.Selected(it.id) }

    /**
     * Catalog filtered by a search query, as a new snapshot.
     *
     * Matching is title-or-summary substring, case-insensitive, on the already-snapshotted text.
     * Sections that lose every entry are dropped rather than rendered as empty headings, and an
     * empty query returns the full catalog unchanged.
     *
     * Kept here rather than in the composable so the filter is unit-testable without a UI host,
     * and so the UI stays a pure function of the returned state.
     */
    fun filtered(query: String): ServicesUiState {
        val needle = query.trim()
        if (needle.isEmpty()) return this
        val matched = sections.mapNotNull { section ->
            val hits = section.entries.filter { entry ->
                entry.title.contains(needle, ignoreCase = true) ||
                    entry.summary?.contains(needle, ignoreCase = true) == true
            }
            if (hits.isEmpty()) null else section.copy(entries = hits)
        }
        return ServicesUiState(title, introduction, matched, searchLabel, searchEmptyLabel)
    }

    /** True when [filtered] would drop everything, used to render the empty-result state. */
    fun isEmptyCatalog(): Boolean = sections.all { it.entries.isEmpty() }
}

sealed interface ServicesEvent {
    data class Selected(val id: ServiceEntryId) : ServicesEvent
}

/** Backing storage is never exposed as a MutableList, including from Java/Kotlin casts. */
private class SnapshotList<T>(source: Collection<T>) : AbstractList<T>() {
    private val values: List<T> = source.toList()
    override val size: Int get() = values.size
    override fun get(index: Int): T = values[index]
}
