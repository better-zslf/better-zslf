package io.github.betterzslf.ui.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.betterzslf.feature.update.AppUpdateController
import io.github.betterzslf.feature.update.AppUpdateEvent
import io.github.betterzslf.feature.update.GithubReleaseSource
import io.github.betterzslf.feature.update.ProductUpdatePolicy
import io.github.betterzslf.feature.update.ui.AppUpdateContent
import io.github.betterzslf.platform.NativeAppUpdater

/** Product binder only. No automatic check/resume work; application owners outlive this page. */
@Composable
fun AppUpdatePage(
    currentVersionName: String,
    repository: GithubReleaseSource,
    updater: NativeAppUpdater,
    onOpenRelease: (String) -> Unit,
    onBack: () -> Unit,
    policy: ProductUpdatePolicy,
) {
    val scope = rememberCoroutineScope()
    val controller = remember(currentVersionName, repository, updater, scope, policy) {
        AppUpdateController(currentVersionName, repository, updater, scope, policy)
    }
    val state by controller.state.collectAsState()
    DisposableEffect(controller) { onDispose { controller.close() } }
    AppUpdateContent(state) { event ->
        when (event) {
            AppUpdateEvent.Back -> onBack()
            AppUpdateEvent.OpenRelease -> {
                try { onOpenRelease(policy.releasesUrl) }
                catch (_: Exception) { controller.browserFailed() }
            }
            else -> controller.onEvent(event)
        }
    }
}
