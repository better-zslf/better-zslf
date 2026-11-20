package io.github.betterzslf.feature.services.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServicesUiStateTest {
    private val run = ServiceEntryId("public.run")
    private val optional = ServiceEntryId("optional.feature")
    private fun state(entries: List<ServiceEntryUi>) = ServicesUiState("Services", "Explicit entries",
        listOf(ServiceSectionUi("campus", "Campus", entries)), searchLabel = "Search", searchEmptyLabel = "No matches")

    @Test fun unknownAndDisabledEntriesDoNotDispatch() {
        val model = state(listOf(ServiceEntryUi(run, "Run", ServiceAccent.Primary),
            ServiceEntryUi(optional, "Optional", ServiceAccent.Positive, enabled = false)))
        assertEquals(ServicesEvent.Selected(run), model.eventFor(run))
        assertNull(model.eventFor(optional))
        assertNull(model.eventFor(ServiceEntryId("unknown")))
    }

    @Test fun duplicateIdsFailBeforeRendering() {
        val entry = ServiceEntryUi(run, "Run", ServiceAccent.Primary)
        assertFailsWith<IllegalArgumentException> { state(listOf(entry, entry)) }
    }

    @Test fun sourceCollectionsCannotMutateTheRenderedSnapshot() {
        val entries = mutableListOf(ServiceEntryUi(run, "Run", ServiceAccent.Primary))
        val sections = mutableListOf(ServiceSectionUi("campus", "Campus", entries))
        val model = ServicesUiState("Services", "Explicit entries", sections, searchLabel = "Search", searchEmptyLabel = "No matches")
        entries.clear(); sections.clear()
        assertEquals(1, model.sections.single().entries.size)
        assertEquals(ServicesEvent.Selected(run), model.eventFor(run))
    }

    @Test fun publishedCollectionsCannotBeCastToMutableStorage() {
        val model = state(listOf(ServiceEntryUi(run, "Run", ServiceAccent.Primary),
            ServiceEntryUi(optional, "Optional", ServiceAccent.Positive, enabled = false)))
        assertFailsWith<Exception> { (model.sections as MutableList<ServiceSectionUi>).clear() }
        assertFailsWith<Exception> { (model.sections.single().entries as MutableList<ServiceEntryUi>).clear() }
        assertEquals(2, model.sections.single().entries.size)
        assertNull(model.eventFor(optional))
    }

    @Test fun productCanContributeAnEntryWithoutChangingPublicTypes() {
        val model = state(listOf(ServiceEntryUi(optional, "Optional", ServiceAccent.Positive)))
        assertEquals(ServicesEvent.Selected(optional), model.eventFor(optional))
    }

    @Test fun searchNarrowsTheCatalogWithoutDroppingSectionsThatStillMatch() {
        val both = ServicesUiState("Services", "intro", listOf(
            ServiceSectionUi("campus", "Campus", listOf(
                ServiceEntryUi(run, "Campus Run", ServiceAccent.Primary, summary = "gps tracking"),
                ServiceEntryUi(optional, "Dorm Network", ServiceAccent.Positive),
            )),
            ServiceSectionUi("app", "App", listOf(
                ServiceEntryUi(ServiceEntryId("app.update"), "Update", ServiceAccent.Neutral),
            )),
        )), searchLabel = "Search", searchEmptyLabel = "No matches")
        // Title match keeps the entry and its section.
        val byTitle = both.filtered("run")
        assertEquals(listOf("campus"), byTitle.sections.map { it.id })
        assertEquals(listOf("Campus Run"), byTitle.sections.single().entries.map { it.title })
        // Summary match counts too, otherwise searchable text would silently be title-only.
        assertEquals(listOf("Campus Run"), both.filtered("gps").sections.single().entries.map { it.title })
        // Sections whose entries all miss are dropped, not rendered as empty headings.
        assertEquals(listOf("app"), both.filtered("update").sections.map { it.id })
        // Case-insensitive, and a blank query is the identity.
        assertEquals(1, both.filtered("UPDATE").sections.size)
        assertEquals(both.sections.size, both.filtered("   ").sections.size)
        // A miss is a genuinely empty catalog, which is what drives the empty state.
        assertTrue(both.filtered("nothing-matches-this").isEmptyCatalog())
        assertFalse(both.isEmptyCatalog())
    }

    @Test fun searchResultsStayInteractiveThroughTheFilteredSnapshot() {
        val model = ServicesUiState("Services", "intro", listOf(
            ServiceSectionUi("campus", "Campus", listOf(
                ServiceEntryUi(run, "Campus Run", ServiceAccent.Primary),
                ServiceEntryUi(optional, "Dorm", ServiceAccent.Positive, enabled = false),
            )),
        )), searchLabel = "Search", searchEmptyLabel = "No matches").filtered("dorm")
        // A disabled entry stays disabled after filtering; the filter must not re-enable it.
        assertNull(model.eventFor(optional))
        assertNull(model.eventFor(run).takeIf { model.sections.single().entries.none { e -> e.id == run } })
    }
}
