package io.github.betterzslf.composition

import io.github.betterzslf.Route
import io.github.betterzslf.feature.services.presentation.ServiceAccent
import io.github.betterzslf.feature.services.presentation.ServiceEntryId
import io.github.betterzslf.feature.services.presentation.ServiceEntryUi
import io.github.betterzslf.feature.services.presentation.ServicesEvent
import io.github.betterzslf.navigation.StandardNavigation
import kotlin.test.*

class ServiceDirectoryTest {
    private val publicOnly = object : ApplicationContributions {}

    @Test fun standardHasOnlyPublicEntriesAndRoutes() {
        val directory = ServiceDirectory(publicOnly)
        val entries = directory.state.sections.flatMap { it.entries }
        assertEquals(6, entries.size)
        entries.forEach { entry ->
            assertTrue(entry.id.value.startsWith("public."))
            assertTrue(StandardNavigation.accepts(assertNotNull(directory.routeFor(ServicesEvent.Selected(entry.id)))))
        }
        assertNull(directory.routeFor(ServicesEvent.Selected(ServiceEntryId("pro.water"))))
    }

    @Test fun productEntryIsVisibleButDisabledAndUnknownEventsNeverDispatch() {
        val extra = ServiceEntryId("product.help")
        val contributions = object : ApplicationContributions {
            override val services = listOf(ContributedService(
                ServiceEntryUi(extra, "Help", ServiceAccent.Positive, enabled = false), Route.Page("help")))
        }
        val directory = ServiceDirectory(contributions)
        assertEquals(7, directory.state.sections.flatMap { it.entries }.size)
        assertNull(directory.routeFor(ServicesEvent.Selected(extra)))
        assertNull(directory.routeFor(ServicesEvent.Selected(ServiceEntryId("unknown.item"))))
    }

    @Test fun entryIdCannotOverridePublicTarget() {
        val contributions = object : ApplicationContributions {
            override val services = listOf(ContributedService(
                ServiceEntryUi(ServiceEntryId("public.run"), "Override", ServiceAccent.Positive), Route.Page("help")))
        }
        assertFailsWith<IllegalArgumentException> { ServiceDirectory(contributions) }
    }

    @Test fun duplicateAndUnregisteredProductTargetsFailAtAssembly() {
        val entry = ServiceEntryUi(ServiceEntryId("product.help"), "Help", ServiceAccent.Positive)
        val duplicate = object : ApplicationContributions {
            override val services = listOf(ContributedService(entry, Route.Page("help")), ContributedService(entry, Route.Page("about")))
        }
        assertFailsWith<IllegalArgumentException> { ServiceDirectory(duplicate) }
        val retired = object : ApplicationContributions {
            override val services = listOf(ContributedService(entry, Route.Page("runimport")))
        }
        assertFailsWith<IllegalArgumentException> { ServiceDirectory(retired) }
    }
}
