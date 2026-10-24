package io.github.betterzslf

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import io.github.betterzslf.core.net.ApiClient
import io.github.betterzslf.core.storage.SessionStore
import io.github.betterzslf.data.AppRepositories
import io.github.betterzslf.feature.settings.SettingsStore
import io.github.betterzslf.feature.runui.RunRuntime
import io.github.betterzslf.feature.update.GithubReleaseRepository
import io.github.betterzslf.platform.NativeAppUpdater
import io.github.betterzslf.platform.createNativeAppUpdater
import io.github.betterzslf.platform.createLocationTracker
import io.github.betterzslf.ui.run.RunTaskList
import kotlin.time.Clock
import kotlinx.coroutines.launch
import io.github.betterzslf.feature.water.KableWaterBleTransport
import io.github.betterzslf.feature.water.WaterBleController
import io.github.betterzslf.platform.createWaterEpilogueContext
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
    // Construction of the transport is inert; scanning is only collected after an explicit UI action.
    val water: WaterBleController by lazy { WaterBleController(applicationScope, KableWaterBleTransport(), ::createWaterEpilogueContext) }
    private var runRuntimeInstance: RunRuntime? = null
    val runRuntime: RunRuntime
        get() = runRuntimeInstance ?: RunRuntime(applicationScope, platform.storage, repositories.run,
            createLocationTracker(), { Clock.System.now().toEpochMilliseconds() }, sessions.session
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
        if (runCatching { repositories.auth.logout() }.isFailure) {
            storageNotice = "本机会话清理失败，请重试；下次启动可能仍读到旧会话。"
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
                    storageNotice = "运动记录尚未停止，本机数据暂未清空，请重试。"
                    return@launch
                }
                if (!closeUpdateOwners()) {
                    storageNotice = "更新任务尚未停止，本机数据暂未清空，请重试。"
                    return@launch
                }
                val sessionOk = runCatching { sessions.clear() }.isSuccess
                val storageOk = runCatching { platform.storage.clear() }.isSuccess
                val settingsOk = settings.reset()
                runRuntimeInstance = null
                storageNotice = if (sessionOk && storageOk && settingsOk) null else "部分本机数据清理失败，请重试。"
            } finally {
                clearingLocalData = false
            }
        }
    }
    fun close() { closeUpdateOwners(); applicationScope.cancel(); api.close(); http.close() }
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
    val existing = indexOf(route)
    if (existing >= 0) {
        while (lastIndex > existing) removeAt(lastIndex)
    } else add(route)
}

/** Preserve an explicit offline page across Activity recreation while keeping login underneath it. */
fun MutableList<NavKey>.reconcileLoggedOutRoute() {
    val top = lastOrNull() as? Route.Page
    if (top?.kind in setOf("water", "document", "resetpassword")) {
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
        MiuixTheme(controller = remember { ThemeController(ColorSchemeMode.System) }) {
            Screen("本机数据暂不可用") { padding ->
                PageList(padding) {
                    item { SectionCard("会话读取失败", "请先解锁设备或稍后重试。原有数据没有被自动删除。") }
                    item { TextButton("重试", onClick = { startupAttempt++ }) }
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
    val theme = remember(mode) { ThemeController(mode) }
    val root = rememberNavBackStack<Route>(Route.Main)
    // Keep tab navigation outside the login-gated subtree, including across a 401.
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val home = rememberNavBackStack<Route>(Route.TabRoot(0))
    val run = rememberNavBackStack<Route>(Route.TabRoot(1))
    val community = rememberNavBackStack<Route>(Route.TabRoot(2))
    val profile = rememberNavBackStack<Route>(Route.TabRoot(3))
    val tabStacks = listOf(home, run, community, profile)
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
    MiuixTheme(controller = theme) {
        PlatformAppearance(when (settings.theme) { "Dark" -> true; "Light" -> false; else -> null })
        NavDisplay(backStack = root, onBack = { if (root.size > 1 && root.last() != Route.Login) root.removeLastOrNull() },
            transition = NavTransitions.MiuixDefault) {
            entry<Route.Main> {
                if (session != null) MainShell(graph, selected, { selected = it }, tabStacks)
            }
            entry<Route.Login> {
                LoginScreen(graph, onDocument = { root.pushUnique(Route.Page("document", it, it)) },
                    onWater = { root.pushUnique(Route.Page("water", title = "宿舍热水器")) })
            }
            entry<Route.Page>(swipeDismiss = if ((root.lastOrNull() as? Route.Page)?.kind in setOf("water", "runtracker")) NavSwipeDirection.None else NavSwipeDirection.LeftToRight) {
                FeaturePage(graph, it, onOpen = root::pushUnique, onBack = { root.removeLastOrNull() })
            }
        }
    }
}

@Composable
private fun MainShell(graph: AppGraph, selected: Int, onSelect: (Int) -> Unit, stacks: List<NavBackStack>) {
    val stack = stacks[selected]
    val titles = listOf("首页", "校园跑", "社区", "我的")
    val icons = listOf(MiuixIcons.Home, MiuixIcons.Location, MiuixIcons.Community, MiuixIcons.Contacts)
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
        NavigationBar {
            titles.forEachIndexed { index, title ->
                NavigationBarItem(selected = selected == index, onClick = { onSelect(index) }, icon = icons[index], label = title)
            }
        }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).clipToBounds()) {
            NavDisplay(backStack = stack, onBack = { if (stack.size > 1) stack.removeLastOrNull() }, transition = NavTransitions.MiuixDefault) {
                entry<Route.TabRoot> { route ->
                    when (route.index) {
                        0 -> HomeScreen(graph, stack::pushUnique)
                        1 -> RunTaskList(graph, stack::pushUnique)
                        2 -> CommunityScreen(graph, stack::pushUnique)
                        3 -> ProfileScreen(graph, stack::pushUnique)
                    }
                }
                entry<Route.Page>(swipeDismiss = if ((stack.lastOrNull() as? Route.Page)?.kind in setOf("water", "runtracker")) NavSwipeDirection.None else NavSwipeDirection.LeftToRight) { route ->
                    FeaturePage(graph, route, stack::pushUnique) { stack.removeLastOrNull() }
                }
            }
        }
    }
}
