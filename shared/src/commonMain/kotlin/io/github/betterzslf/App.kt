package io.github.betterzslf

import io.github.betterzslf.core.text.AppText
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.storage.SessionStore
import io.github.betterzslf.data.AppRepositories
import io.github.betterzslf.feature.settings.SettingsStore
import io.github.betterzslf.feature.schedule.createAcademicSource
import io.github.betterzslf.feature.schedule.runtime.ScheduleRuntime
import io.github.betterzslf.ui.schedule.ScheduleBindingPrompt
import io.github.betterzslf.ui.schedule.ScheduleRootPage
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.betterzslf.feature.runui.RunRuntime
import io.github.betterzslf.feature.update.GithubReleaseRepository
import io.github.betterzslf.platform.NativeAppUpdater
import io.github.betterzslf.platform.createNativeAppUpdater
import io.github.betterzslf.platform.createLocationTracker
import io.github.betterzslf.ui.run.RunTaskList
import io.github.betterzslf.ui.campusnet.CampusNetworkFeaturePage
import kotlin.time.Clock
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import io.github.betterzslf.edition.EditionAccess
import io.github.betterzslf.edition.EditionFeatures
import io.github.betterzslf.edition.createEditionFeatures
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import io.github.betterzslf.platform.PlatformServices
import io.github.betterzslf.platform.PlatformAppearance
import io.github.betterzslf.platform.createPlatformServices
import io.github.betterzslf.ui.*
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.nav.core.*
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.*

/**
 * Slot identifier for the single local timetable record.
 *
 * It is deliberately a constant rather than the campus user id: the academic system has its own
 * credentials, so the timetable must remain usable with no campus account at all. The value is
 * stable across installs and versions, which is what keeps an existing binding attributable.
 */
internal const val ScheduleSlotKey: String = "local-timetable"

class AppGraph(val platform: PlatformServices) {
    val sessions = SessionStore(platform.storage)
    val settings = SettingsStore(platform.storage)
    private val http = platform.httpClient()
    val api = ApiClient(http, sessions)
    val repositories = AppRepositories(api, sessions)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val schedule = ScheduleRuntime(applicationScope, platform.storage, { createAcademicSource(platform.httpClient()) })
    // The portal probe is the one request allowed to speak cleartext, and only to the gateway the
    // school hands us. It gets its own derived client: no campus token, no cookies, no logging.
    private var portalProbeInstance: io.github.betterzslf.feature.campusnet.KtorPortalProbe? = null
    val portalProbe: io.github.betterzslf.feature.campusnet.KtorPortalProbe
        get() = portalProbeInstance
            ?: io.github.betterzslf.feature.campusnet.KtorPortalProbe(platform.httpClient()).also { portalProbeInstance = it }
    // The cipher-negotiation handshake. Same cleartext allowlist as the probe: it only ever talks
    // to the two fixed carrier auth servers on port 7001 that the user's own network resolves.
    private var dormHandshakeInstance: io.github.betterzslf.feature.campusnet.KtorDormHandshake? = null
    val dormHandshake: io.github.betterzslf.feature.campusnet.KtorDormHandshake
        get() = dormHandshakeInstance
            ?: io.github.betterzslf.feature.campusnet.KtorDormHandshake(platform.httpClient()).also { dormHandshakeInstance = it }
    // The dialer's own transport: plain HTTP to the two fixed carrier servers, nothing else.
    private var dormTransportInstance: io.github.betterzslf.feature.campusnet.KtorDormTransport? = null
    val dormTransport: io.github.betterzslf.feature.campusnet.KtorDormTransport
        get() = dormTransportInstance
            ?: io.github.betterzslf.feature.campusnet.KtorDormTransport(platform.httpClient()).also { dormTransportInstance = it }
    /**
     * Records a live dorm session so a process that dies without signing out can be cleaned up on
     * the next start. Without it the account stays online server-side and the next attempt is
     * refused as "already signed in elsewhere".
     */
    val dormLogoutStore by lazy { io.github.betterzslf.feature.campusnet.DormLogoutStore(platform.storage) }
    init {
        // The timetable has its own slot and does not wait for a campus login. The academic system
        // authenticates with separate credentials, so gating timetable access on a campus session
        // made the academic entry unreachable for anyone who had not signed in to 掌上莲峰 first.
        applicationScope.launch {
            schedule.setOwner(ScheduleSlotKey)
            // A confirmed binding lives on disk; restore it before the shell decides whether the
            // timetable has anything to show, otherwise a restart looked like "never bound".
            schedule.reloadFromStorage()
            schedule.onForeground()
        }
        applicationScope.launch {
            sessions.session.collect { session ->
                if (session != null) onForeground()
            }
        }
    }
    fun onForeground() {
        applicationScope.launch { schedule.onForeground() }
    }
    // The selected facade contains no Pro implementation in standard compiler inputs.
    val editionFeatures: EditionFeatures by lazy { createEditionFeatures(applicationScope) }
    private var runRuntimeInstance: RunRuntime? = null
    val runRuntime: RunRuntime
        get() = runRuntimeInstance ?: RunRuntime(applicationScope, platform.storage, repositories.run,
            createLocationTracker(), { Clock.System.now().toEpochMilliseconds() }, sessions.session,
            transportFactory = null // Both editions retain explicit local real-GPS recording only.
        ).also { runRuntimeInstance = it }
    // Public release traffic has its own client and never borrows school authentication.
    private var githubUpdatesInstance: GithubReleaseRepository? = null
    val githubUpdates: GithubReleaseRepository
        get() = githubUpdatesInstance ?: GithubReleaseRepository(platform.httpClient()).also { githubUpdatesInstance = it }
    private var appUpdaterInstance: NativeAppUpdater? = null
    val appUpdater: NativeAppUpdater
        get() = appUpdaterInstance ?: createNativeAppUpdater().also { appUpdaterInstance = it }
    private fun closeUpdateOwners(): Boolean {
        val nativeOk = runCatching { appUpdaterInstance?.close() }.isSuccess
        if (nativeOk) appUpdaterInstance = null
        val repositoryOk = runCatching { githubUpdatesInstance?.close() }.isSuccess
        if (repositoryOk) githubUpdatesInstance = null
        return nativeOk && repositoryOk
    }
    var clearingLocalData by mutableStateOf(false)
        private set
    var storageNotice by mutableStateOf<String?>(null)
        private set
    fun clearStorageNotice() { storageNotice = null }
    fun logout() {
        // Campus logout must NOT clear the timetable: the academic binding has its own credentials
        // and survives a campus session ending. Only an explicit local-data wipe removes it.
        val sessionOk = runCatching { repositories.auth.logout() }.isSuccess
        if (!sessionOk) {
            storageNotice = AppText.App.SESSION_CLEANUP_FAILED_OLD_SESSION_MAY_REAPPEAR_HINT
        }
    }
    fun clearLocalData() {
        if (clearingLocalData) return
        clearingLocalData = true
        applicationScope.launch {
            try {
                // Await native capture and any ambiguous in-flight upload cleanup BEFORE erasing storage.
                // A fresh runtime is created only after the old one has become quiescent.
                val stopped = runCatching { runRuntimeInstance?.close() }.isSuccess
                if (!stopped) {
                    storageNotice = AppText.App.LOCAL_DATA_CLEAR_BLOCKED_BY_ACTIVITY_HINT
                    return@launch
                }
                if (!closeUpdateOwners()) {
                    storageNotice = AppText.App.LOCAL_DATA_CLEAR_BLOCKED_BY_UPDATE_HINT
                    return@launch
                }
                val scheduleOk = schedule.clearForLogout()
                val sessionOk = runCatching { sessions.clear() }.isSuccess
                val storageOk = runCatching { platform.storage.clear() }.isSuccess
                val settingsOk = settings.reset()
                runRuntimeInstance = null
                // Storage is empty now; re-arm the timetable slot so the academic entry still opens.
                schedule.setOwner(ScheduleSlotKey, force = true)
                storageNotice = if (scheduleOk && sessionOk && storageOk && settingsOk) null else AppText.App.PARTIAL_LOCAL_DATA_CLEAR_FAILED
            } finally {
                clearingLocalData = false
            }
        }
    }
    fun close() { schedule.close(); closeUpdateOwners(); applicationScope.cancel(); api.close(); http.close() }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Main : Route
    @Serializable data object Login : Route
    @Serializable data class TabRoot(val index: Int) : Route
    @Serializable data class Page(val kind: String, val id: String = "", val title: String = "") : Route
}

/** A route can be on a stack once; repeated taps never create duplicate keys. */
fun MutableList<NavKey>.pushUnique(route: Route) {
    if (route is Route.Page && !EditionAccess.allowsPage(route.kind)) return
    val existing = indexOf(route)
    if (existing >= 0) {
        while (lastIndex > existing) removeAt(lastIndex)
    } else add(route)
}

/** Preserve an explicit offline page across Activity recreation while keeping login underneath it. */
fun MutableList<NavKey>.reconcileLoggedOutRoute() {
    removeAll { it is Route.Page && !EditionAccess.allowsPage(it.kind) }
    val top = lastOrNull() as? Route.Page
    if (top != null && EditionAccess.allowsOfflinePage(top.kind)) {
        if (none { it == Route.Login }) add(lastIndex.coerceAtLeast(0), Route.Login)
    } else {
        pushUnique(Route.Login)
    }
}



@Composable
fun App(platform: PlatformServices = remember { createPlatformServices() }, graphProvider: (() -> AppGraph)? = null) {
    var startupAttempt by remember { mutableIntStateOf(0) }
    val initialized = remember(platform, graphProvider, startupAttempt) { runCatching { graphProvider?.invoke() ?: AppGraph(platform) } }
    val graph = initialized.getOrNull()
    if (graph == null) {
        // §10.2: the startup-failure surface must use the same shared text styles as the normal
        // graph, otherwise the exception page silently falls back to library typography.
        MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System, lightColors = ardotLightColors(), darkColors = ardotDarkColors()) },
            textStyles = remember { ardotTextStyles() }) {
            Screen(AppText.App.LOCAL_DATA_TEMPORARILY_UNAVAILABLE_TITLE) { padding ->
                PageList(padding) {
                    item { SectionCard(AppText.App.SESSION_READ_FAILED_TITLE, AppText.App.UNLOCK_OR_RETRY_ORIGINAL_DATA_RETAINED_HINT) }
                    item { TextButton(AppText.Common.RETRY, onClick = { startupAttempt++ }) }
                }
            }
        }
        return
    }
    DisposableEffect(graph, graphProvider) { onDispose { if (graphProvider == null) graph.close() } }
    val session by graph.sessions.session.collectAsState()
    // A confirmed academic binding is an independent way into the app: it has its own credentials
    // and must not require a campus login just to reach the timetable.
    val scheduleState by graph.schedule.state.collectAsState()
    val hasAcademicBinding = scheduleState.boundAccount != null
    val settings by graph.settings.settings.collectAsState()
    val mode = when (settings.theme) {
        "Light" -> if (settings.dynamicColor) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        "Dark" -> if (settings.dynamicColor) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
        else -> if (settings.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
    }
    val theme = remember(mode) { ThemeController(mode, lightColors = ardotLightColors(), darkColors = ardotDarkColors()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { graph.onForeground() }
    // Ardot 24:22 / P00 / P46: five tabs 首页/课表/校园网/服务/我的. v4 invalidates old
    // four-tab saved indices once, then retains all five stacks on recreation.
    key(NavigationLayoutPolicy.SAVED_STATE_SCHEMA) {
    val root = rememberNavBackStack<Route>(Route.Main)
    // Keep tab navigation outside the login-gated subtree, including across a 401.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    // Tab order: 0 首页 (landing), 1 课表, 2 校园网, 3 服务, 4 我的.
    val home = rememberNavBackStack<Route>(Route.TabRoot(0))
    val timetable = rememberNavBackStack<Route>(Route.TabRoot(1))
    val campusNetwork = rememberNavBackStack<Route>(Route.TabRoot(2))
    val services = rememberNavBackStack<Route>(Route.TabRoot(3))
    val profile = rememberNavBackStack<Route>(Route.TabRoot(4))
    val tabStacks = listOf(home, timetable, campusNetwork, services, profile)
    val nativeOpenRequest by graph.schedule.native.openRequests.collectAsState()
    LaunchedEffect(nativeOpenRequest, session?.userId) {
        val request = nativeOpenRequest ?: return@LaunchedEffect
        if (session == null) return@LaunchedEffect // Keep the request while the campus login is visible.
        graph.schedule.native.consumeOpenRequest()
        if (request.ownerRevision != null && request.ownerRevision != graph.schedule.ownerRevision) return@LaunchedEffect
        if (graph.schedule.state.value.owner != session?.userId) return@LaunchedEffect
        val courseId = request.occurrenceId?.let(graph.schedule::courseIdForOccurrence)
        graph.schedule.state.value.sourceTermId?.let { graph.schedule.selectTerm(it) }
        // A native timetable open request belongs to the 课表 tab (index 1), not 首页.
        selected = 1
        root.removeAll { it != Route.Main }
        if (root.isEmpty()) root.add(Route.Main)
        timetable.clear()
        timetable.add(Route.TabRoot(1))
        if (courseId != null) timetable.pushUnique(Route.Page("scheduledetail", courseId, AppText.Schedule.DETAILS_TITLE))
    }
    // The root stack owns a single route while a tab is open, so the platform back gesture must
    // first pop the active tab's stack and then let the system background the task.
    DisposableEffect(root, tabStacks, selected) {
        AppBackDispatcher.register {
            val top = root.lastOrNull()
            when {
                top is Route.Page -> {
                    root.removeLastOrNull()
                    true
                }
                top is Route.Login -> false
                else -> {
                    val stack = NavigationLayoutPolicy.stackFor(tabStacks, selected)
                    if (stack.size > 1) {
                        stack.removeLastOrNull()
                        true
                    } else false
                }
            }
        }
        onDispose { AppBackDispatcher.register(null) }
    }
    var previousOwner by rememberSaveable { mutableStateOf(session?.userId) }
    LaunchedEffect(session?.token) {
        if (session == null) {
            root.reconcileLoggedOutRoute()
        } else {
            val owner = session?.userId
            if (previousOwner != null && previousOwner != owner) {
                tabStacks.forEachIndexed { index, stack -> stack.clear(); stack.add(Route.TabRoot(index)) }
                selected = 0
                root.removeAll { it != Route.Main && it != Route.Login }
            }
            previousOwner = owner
            root.removeAll { it == Route.Login }
            if (root.isEmpty()) root.add(Route.Main)
        }
    }
    MiuixTheme(controller = theme, textStyles = remember { ardotTextStyles() }) {
        PlatformAppearance(when (settings.theme) { "Dark" -> true; "Light" -> false; else -> null })
        NavDisplay(backStack = root, modifier = Modifier.fillMaxSize().clipToBounds(),
            onBack = { if (root.size > 1 && root.last() != Route.Login) root.removeLastOrNull() },
            transition = NavTransitions.MiuixDefault, effects = NavDisplayEffects(cornerClipRadius = 0.dp)) {
            entry<Route.Main> {
                // The timetable is usable without a campus session, so "signed in" is no longer the
                // only way in: a confirmed academic binding also unlocks the shell. Feature pages
                // that genuinely need a campus session still guard themselves.
                if (session != null || hasAcademicBinding) MainShell(graph, selected, { selected = it }, tabStacks,
                    onAccountRecovery = { root.pushUnique(Route.Page("resetpassword", title = AppText.Recovery.RECOVER_PASSWORD_TITLE)) })
            }
            entry<Route.Login> {
                LoginScreen(graph, onDocument = { root.pushUnique(Route.Page("document", it, it)) },
                    onEnter = {
                        // Entering from the chooser replaces the login surface with the shell; the
                        // academic binding it was gated on is already stored on this device.
                        root.clear()
                        root.add(Route.Main)
                    },
                    onWater = if (EditionAccess.waterEnabled) ({ root.pushUnique(Route.Page("water")) }) else null,
                    onResetPassword = { root.pushUnique(Route.Page("resetpassword", title = AppText.Recovery.RECOVER_PASSWORD_TITLE)) })
            }
            entry<Route.Page>(swipeDismiss = if ((root.lastOrNull() as? Route.Page)?.kind in setOf("water", "runtracker", "resetpassword")) NavSwipeDirection.None else NavSwipeDirection.LeftToRight) {
                FeaturePage(graph, it, onOpen = root::pushUnique, onBack = { root.removeLastOrNull() })
            }
        }
        if (session != null && root.lastOrNull() == Route.Main) {
            ScheduleBindingPrompt(graph) {
                selected = 1
                timetable.pushUnique(Route.Page("schedulebind", title = AppText.Schedule.BIND_TITLE))
            }
        }
    }
    }
}

@Composable
private fun MainShell(graph: AppGraph, selected: Int, onSelect: (Int) -> Unit, stacks: List<NavBackStack>, onAccountRecovery: () -> Unit) {
    val stack = NavigationLayoutPolicy.stackFor(stacks, selected)
    val activeTab = NavigationLayoutPolicy.tabIndex(selected)
    val open: (Route) -> Unit = { route ->
        if (route is Route.Page && route.kind == "resetpassword") onAccountRecovery() else stack.pushUnique(route)
    }
    // Ardot 24:22: five tabs 首页/课表/校园网/服务/我的. 校园跑 moves into the 服务 directory
    // (P46) but its pages stay reachable through the third tab's stack.
    val titles = listOf(AppText.Navigation.HOME, AppText.Navigation.TIMETABLE, AppText.Navigation.CAMPUS_NETWORK,
        AppText.Navigation.SERVICES, AppText.Navigation.PROFILE)
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Notes, MiuixIcons.Carrier, MiuixIcons.GridView, MiuixIcons.Contacts)
    val showBottomNavigation = stack.lastOrNull() is Route.TabRoot
    // Miuix measures the whole bottomBar before composing its body. Its padding includes the
    // pill, outer spacing and system navigation area, unlike measuring inside padding modifiers.
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val useRail = NavigationLayoutPolicy.useRail(maxWidth.value)
        Scaffold(containerColor = MiuixTheme.colorScheme.surface,
            contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
            if (!useRail && showBottomNavigation) {
                Column(Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
                    NavigationBar(
                        modifier = Modifier.clip(RoundedCornerShape(AppUiTokens.NavigationRadius)),
                        // An opaque Miuix card surface keeps scrolled content behind the pill.
                        color = MiuixTheme.colorScheme.surfaceContainer,
                        showDivider = false,
                        defaultWindowInsetsPadding = false,
                    ) {
                        titles.forEachIndexed { index, title ->
                            NavigationBarItem(selected = activeTab == index,
                                onClick = { onSelect(NavigationLayoutPolicy.selectTab(selected, index)) }, icon = icons[index], label = title)
                        }
                    }
                }
            }
        }) { padding ->
            // Reserve the complete measured bar only on tab roots. Overlay padding is not applied
            // to this pane, so do not mark its safe areas consumed before a child actually uses them.
            val overlayInset = navigationOverlayInset(padding, showBottomNavigation && !useRail)
            CompositionLocalProvider(LocalNavigationBottomInset provides overlayInset) {
            Row(Modifier.fillMaxSize().clipToBounds()) {
                if (useRail) NavigationRail(
                    modifier = Modifier.fillMaxHeight(),
                    // Miuix 0.9.4 already supplies a default ScrollState for its item column.
                    // Passing it explicitly only expresses scrolling intent; it is not a
                    // clipping fix. Keep the rail bounded, without an outer verticalScroll.
                    scrollState = rememberScrollState(),
                    defaultWindowInsetsPadding = true,
                ) {
                    titles.forEachIndexed { index, title ->
                        NavigationRailItem(selected = activeTab == index,
                            onClick = { onSelect(NavigationLayoutPolicy.selectTab(selected, index)) }, icon = icons[index], label = title)
                    }
                }
                // The rail already moves this pane past logical Start cutout/side-navigation insets.
                // Top/End/Bottom remain unconsumed for each page's own Scaffold; no double safe area.
                val railConsumedInsets = if (useRail) WindowInsets.displayCutout.union(WindowInsets.navigationBars).only(WindowInsetsSides.Start)
                    else WindowInsets(0, 0, 0, 0)
                Box(Modifier.weight(1f).fillMaxHeight().consumeWindowInsets(railConsumedInsets).clipToBounds()
                    .background(MiuixTheme.colorScheme.surface)) {
                    // One stable host for both layouts: resizing changes chrome, never the tab's owner.
                    NavDisplay(backStack = stack, modifier = Modifier.fillMaxSize().clipToBounds(),
                        onBack = { if (stack.size > 1) stack.removeLastOrNull() }, transition = NavTransitions.MiuixDefault,
                        effects = NavDisplayEffects(cornerClipRadius = 0.dp)) {
                        entry<Route.TabRoot> { route ->
                            when (route.index) {
                                0 -> HomePage(graph, open)
                                1 -> ScheduleRootPage(graph, open)
                                2 -> CampusNetworkFeaturePage(graph, onBack = null,
                                    onOpenDormSignIn = { open(Route.Page("dormnetwork", title = io.github.betterzslf.core.text.AppText.Auth.ENTRY_DORM_LINK)) })
                                3 -> ServicesPage(graph, open)
                                4 -> ProfileScreen(graph, open)
                            }
                        }
                        entry<Route.Page>(swipeDismiss = if ((stack.lastOrNull() as? Route.Page)?.kind in setOf("water", "runtracker", "resetpassword")) NavSwipeDirection.None else NavSwipeDirection.LeftToRight) { route ->
                            FeaturePage(graph, route, open) { stack.removeLastOrNull() }
                        }
                    }
                }
            }
            }
        }
    }
}
