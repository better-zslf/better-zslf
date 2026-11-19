package io.github.betterzslf.composition

import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.services.presentation.*
import top.yukonga.miuix.kmp.nav.core.NavKey

/** Product assembly owns targets. Feature content only receives display state and entry ids. */
internal class ServiceDirectory(contributions: ApplicationContributions) {
    private val productEntries = contributions.services.toList()
    private val routes = linkedMapOf<ServiceEntryId, NavKey>(
        ServiceEntryId("public.run") to Route.Page("runs", title = AppText.Navigation.CAMPUS_RUN),
        ServiceEntryId("public.dorm-network") to Route.Page("dormnetwork", title = AppText.Auth.ENTRY_DORM_LINK),
        ServiceEntryId("public.schedule-manage") to Route.Page("schedulemanage", title = AppText.Schedule.MANAGE),
        ServiceEntryId("public.schedule-periods") to Route.Page("scheduleperiods", title = AppText.Schedule.PERIODS_TITLE),
        ServiceEntryId("public.updates") to Route.Page("updates", title = AppText.Updates.VERSION_UPDATE_TITLE),
        ServiceEntryId("public.about") to Route.Page("about", title = AppText.App.ABOUT_APP_TITLE),
    ).apply {
        productEntries.forEach { contribution ->
            require(contribution.entry.id !in this) { "Duplicate product service" }
            require(contributions.navigation.accepts(contribution.target)) { "Unregistered service target" }
            put(contribution.entry.id, contribution.target)
        }
    }

    val state = ServicesUiState(AppText.Services.TITLE, AppText.Services.INTRO, listOf(
        ServiceSectionUi("campus", AppText.Services.GROUP_CAMPUS_LIFE, listOf(
            entry("public.run", AppText.Services.CAMPUS_RUN, ServiceAccent.Primary),
            entry("public.dorm-network", AppText.Services.DORM_NETWORK_LOGIN, ServiceAccent.Warning),
        ) + productEntries.map { it.entry }),
        ServiceSectionUi("academic", AppText.Services.GROUP_ACADEMIC, listOf(
            entry("public.schedule-manage", AppText.Services.ACADEMIC_MANAGE, ServiceAccent.Secondary),
            entry("public.schedule-periods", AppText.Services.PERIOD_CALIBRATION, ServiceAccent.Secondary),
        )),
        ServiceSectionUi("app", AppText.Services.GROUP_APP, listOf(
            entry("public.updates", AppText.Services.APP_UPDATE, ServiceAccent.Neutral),
            entry("public.about", AppText.Services.ABOUT_APP, ServiceAccent.Neutral),
        )),
    ))

    fun routeFor(event: ServicesEvent): NavKey? = when (event) {
        is ServicesEvent.Selected -> state.eventFor(event.id)?.let { routes[it.id] }
    }

    private fun entry(id: String, title: String, accent: ServiceAccent) = ServiceEntryUi(ServiceEntryId(id), title, accent)
}
