package io.github.betterzslf.feature.services.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServicesUiStateTest {
    private val run = ServiceEntryId("public.run")
    private val optional = ServiceEntryId("optional.feature")
    private fun state(entries: List<ServiceEntryUi>) = ServicesUiState("Services", "Explicit entries", listOf(ServiceSectionUi("campus", "Campus", entries)))

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
        val model = ServicesUiState("Services", "Explicit entries", sections)
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
}
