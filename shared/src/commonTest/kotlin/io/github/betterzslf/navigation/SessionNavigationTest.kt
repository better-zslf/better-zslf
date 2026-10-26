package io.github.betterzslf.navigation

import io.github.betterzslf.Route
import io.github.betterzslf.edition.BuildEdition
import io.github.betterzslf.reconcileLoggedOutRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import top.yukonga.miuix.kmp.nav.core.NavKey

class SessionNavigationTest {
    @Test fun firstLoggedOutLaunchShowsLogin() {
        val stack = mutableListOf<NavKey>(Route.Main)
        stack.reconcileLoggedOutRoute()
        assertEquals(listOf<NavKey>(Route.Main, Route.Login), stack)
    }
    @Test fun recreatedOfflineWaterPreservesPageAndLogin() {
        val page = Route.Page("water", title = "宿舍热水器")
        val stack = mutableListOf<NavKey>(Route.Main, Route.Login, page)
        repeat(3) { stack.reconcileLoggedOutRoute() }
        assertEquals(if (BuildEdition.isPro) listOf<NavKey>(Route.Main, Route.Login, page) else listOf<NavKey>(Route.Main, Route.Login), stack)
    }
    @Test fun expiredWaterSessionInsertsLoginUnderCurrentOfflinePage() {
        val page = Route.Page("water")
        val stack = mutableListOf<NavKey>(Route.Main, page)
        stack.reconcileLoggedOutRoute()
        assertEquals(if (BuildEdition.isPro) listOf<NavKey>(Route.Main, Route.Login, page) else listOf<NavKey>(Route.Main, Route.Login), stack)
    }
    @Test fun protectedPageIsHiddenOnLogout() {
        val stack = mutableListOf<NavKey>(Route.Main, Route.Page("runtracker"))
        stack.reconcileLoggedOutRoute()
        assertEquals(Route.Login, stack.last())
    }
    @Test fun publicDocumentIsPreserved() {
        val page = Route.Page("document", "隐私政策")
        val stack = mutableListOf<NavKey>(Route.Main, Route.Login, page)
        stack.reconcileLoggedOutRoute()
        assertEquals(page, stack.last())
        assertEquals(3, stack.size)
    }
}
