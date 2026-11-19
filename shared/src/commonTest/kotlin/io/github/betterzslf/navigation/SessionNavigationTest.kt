package io.github.betterzslf.navigation

import io.github.betterzslf.Route
import io.github.betterzslf.reconcileLoggedOutRoute
import io.github.betterzslf.reconcileShellAccess
import top.yukonga.miuix.kmp.nav.core.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals

/** Logout uses the current product's admitted typed keys, not a compile-time edition boolean. */
class SessionNavigationTest {
    private data class OfflineFixture(val id: String) : NavKey
    private val fixtureNavigation = object : ProductNavigation by StandardNavigation {
        override val schemaId: String = "fixture.product.navigation"
        override fun accepts(key: NavKey): Boolean = key is OfflineFixture || StandardNavigation.accepts(key)
        override fun isOffline(key: NavKey): Boolean = key is OfflineFixture || StandardNavigation.isOffline(key)
    }

    @Test fun independentAcademicBindingAdmitsTheShellWithoutACampusSession() {
        val stack = mutableListOf<NavKey>(Route.Main, Route.Login)
        repeat(3) { stack.reconcileShellAccess(false, true) }
        assertEquals(listOf<NavKey>(Route.Main), stack)
    }

    @Test fun academicOnlyNativeDetailRemainsVisibleWhileCampusSessionIsAbsent() {
        val detail = Route.Page("scheduledetail", "fixture-local-selection")
        val stack = mutableListOf<NavKey>(Route.Main, detail)
        repeat(3) { stack.reconcileShellAccess(false, true) }
        assertEquals(listOf<NavKey>(Route.Main, detail), stack)
    }

    @Test fun losingBothIndependentAuthoritiesHidesProtectedDestinationsAgain() {
        val stack = mutableListOf<NavKey>(Route.Main, Route.Page("scheduledetail"))
        stack.reconcileShellAccess(false, false)
        assertEquals(Route.Login, stack.last())
        stack.reconcileShellAccess(false, true)
        assertEquals(listOf<NavKey>(Route.Main, Route.Page("scheduledetail")), stack)
    }

    @Test fun admittedShellStillRejectsForeignKeysAndRetainsProductOfflineKeys() {
        val page = OfflineFixture("fixture")
        val stack = mutableListOf<NavKey>(Route.Main, Route.Login, page)
        stack.reconcileShellAccess(false, true, fixtureNavigation)
        assertEquals(listOf<NavKey>(Route.Main, page), stack)
        stack.reconcileShellAccess(true, false, StandardNavigation)
        assertEquals(listOf<NavKey>(Route.Main), stack)
    }

    @Test fun firstLoggedOutLaunchShowsLogin() {
        val stack = mutableListOf<NavKey>(Route.Main)
        stack.reconcileLoggedOutRoute()
        assertEquals(listOf<NavKey>(Route.Main, Route.Login), stack)
    }

    @Test fun restoredLegacyPrivatePagesAreRemovedFromThePublicProduct() {
        for (kind in listOf("water", "runimport", "runmanual", "runplan", "runsupplement", "extension:fixture")) {
            val stack = mutableListOf<NavKey>(Route.Main, Route.Login, Route.Page(kind))
            repeat(3) { stack.reconcileLoggedOutRoute(StandardNavigation) }
            assertEquals(listOf<NavKey>(Route.Main, Route.Login), stack)
        }
    }

    @Test fun rejectedTypedKeyCannotRemainInTheStandardLoggedOutStack() {
        val stack = mutableListOf<NavKey>(Route.Main, OfflineFixture("fixture"))
        stack.reconcileLoggedOutRoute(StandardNavigation)
        assertEquals(listOf<NavKey>(Route.Main, Route.Login), stack)
    }

    @Test fun currentProductPreservesItsTypedOfflineDestinationAndExistingLogin() {
        val page = OfflineFixture("fixture")
        val stack = mutableListOf<NavKey>(Route.Main, Route.Login, page)
        repeat(3) { stack.reconcileLoggedOutRoute(fixtureNavigation) }
        assertEquals(listOf<NavKey>(Route.Main, Route.Login, page), stack)
    }

    @Test fun currentProductInsertsLoginUnderItsTypedOfflineDestinationExactlyOnce() {
        val page = OfflineFixture("fixture")
        val stack = mutableListOf<NavKey>(Route.Main, page)
        repeat(3) { stack.reconcileLoggedOutRoute(fixtureNavigation) }
        assertEquals(listOf<NavKey>(Route.Main, Route.Login, page), stack)
    }

    @Test fun protectedPageIsHiddenOnLogout() {
        val stack = mutableListOf<NavKey>(Route.Main, Route.Page("runtracker"))
        stack.reconcileLoggedOutRoute()
        assertEquals(Route.Login, stack.last())
    }

    @Test fun publicDocumentIsPreserved() {
        val page = Route.Page("document", "privacy")
        val stack = mutableListOf<NavKey>(Route.Main, Route.Login, page)
        stack.reconcileLoggedOutRoute()
        assertEquals(page, stack.last())
        assertEquals(3, stack.size)
    }
}
