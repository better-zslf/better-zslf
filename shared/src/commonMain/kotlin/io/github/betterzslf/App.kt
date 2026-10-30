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

class AppGraph(val platform: PlatformServices) {
    val sessions = SessionStore(platform.storage)
    val settings = SettingsStore(platform.storage)
    private val http = platform.httpClient()
    val api = ApiClient(http, sessions)
    val repositories = AppRepositories(api, sessions)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val schedule = ScheduleRuntime(applicationScope, platform.storage, { createAcademicSource(platform.httpClient()) })
    init {
        applicationScope.launch {
            sessions.session.collect { session ->
                schedule.setOwner(session?.userId)
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
        val timetableOk = schedule.clearForLogout()
        val sessionOk = runCatching { repositories.auth.logout() }.isSuccess
        if (!timetableOk || !sessionOk) {
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
        MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System, lightColors = ardotLightColors(), darkColors = ardotDarkColors()) }) {
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
    val settings by graph.settings.settings.collectAsState()
    val mode = when (settings.theme) {
        "Light" -> if (settings.dynamicColor) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        "Dark" -> if (settings.dynamicColor) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
        else -> if (settings.dynamicColor) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
    }
    val theme = remember(mode) { ThemeController(mode, lightColors = ardotLightColors(), darkColors = ardotDarkColors()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { graph.onForeground() }
    // Invalidate old five-tab saved indices once; v3 then retains all four stacks on recreation.
    key(NavigationLayoutPolicy.SAVED_STATE_SCHEMA) {
    val root = rememberNavBackStack<Route>(Route.Main)
    // Keep tab navigation outside the login-gated subtree, including across a 401.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val home = rememberNavBackStack<Route>(Route.TabRoot(0))
    val run = rememberNavBackStack<Route>(Route.TabRoot(1))
    val timetable = rememberNavBackStack<Route>(Route.TabRoot(2))
    val profile = rememberNavBackStack<Route>(Route.TabRoot(3))
    val tabStacks = listOf(home, run, timetable, profile)
    val nativeOpenRequest by graph.schedule.native.openRequests.collectAsState()
    LaunchedEffect(nativeOpenRequest, session?.userId) {
        val request = nativeOpenRequest ?: return@LaunchedEffect
        if (session == null) return@LaunchedEffect // Keep the request while the campus login is visible.
        graph.schedule.native.consumeOpenRequest()
        if (request.ownerRevision != null && request.ownerRevision != graph.schedule.ownerRevision) return@LaunchedEffect
        if (graph.schedule.state.value.owner != session?.userId) return@LaunchedEffect
        val courseId = request.occurrenceId?.let(graph.schedule::courseIdForOccurrence)
        graph.schedule.state.value.sourceTermId?.let { graph.schedule.selectTerm(it) }
        selected = 2
        root.removeAll { it != Route.Main }
        if (root.isEmpty()) root.add(Route.Main)
        timetable.clear()
        timetable.add(Route.TabRoot(2))
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
                if (session != null) MainShell(graph, selected, { selected = it }, tabStacks,
                    onAccountRecovery = { root.pushUnique(Route.Page("resetpassword", title = AppText.Recovery.RECOVER_PASSWORD_TITLE)) })
            }
            entry<Route.Login> {
                LoginScreen(graph, onDocument = { root.pushUnique(Route.Page("document", it, it)) },
                    onWater = if (EditionAccess.waterEnabled) ({ root.pushUnique(Route.Page("water")) }) else null,
                    onResetPassword = { root.pushUnique(Route.Page("resetpassword", title = AppText.Recovery.RECOVER_PASSWORD_TITLE)) })
            }
            entry<Route.Page>(swipeDismiss = if ((root.lastOrNull() as? Route.Page)?.kind in setOf("water", "runtracker", "resetpassword")) NavSwipeDirection.None else NavSwipeDirection.LeftToRight) {
                FeaturePage(graph, it, onOpen = root::pushUnique, onBack = { root.removeLastOrNull() })
            }
        }
        if (session != null && root.lastOrNull() == Route.Main) {
            ScheduleBindingPrompt(graph) {
                selected = 2
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
    val titles = listOf(AppText.Navigation.HOME, AppText.Navigation.CAMPUS_RUN, AppText.Navigation.SCHEDULE, AppText.Navigation.PROFILE)
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Location, MiuixIcons.Notes, MiuixIcons.Contacts)
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
                                0 -> HomeScreen(graph, open)
                                1 -> RunTaskList(graph, open)
                                2 -> ScheduleRootPage(graph, open)
                                3 -> ProfileScreen(graph, open)
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
