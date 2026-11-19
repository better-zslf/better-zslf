package io.github.betterzslf.composition

import androidx.compose.runtime.Composable
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.run.api.RunPreviewSelection
import io.github.betterzslf.feature.run.api.RunPreviewSource
import io.github.betterzslf.feature.services.presentation.ServiceEntryUi
import io.github.betterzslf.feature.update.ProductUpdatePolicy
import io.github.betterzslf.navigation.ProductNavigation
import io.github.betterzslf.navigation.StandardNavigation
import io.github.betterzslf.platform.RunMapProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.nav.core.NavKey

/** Product-owned optional entries. No contribution receives AppGraph, credentials or a repository. */
interface ApplicationContributions {
    val navigation: ProductNavigation get() = StandardNavigation
    val services: List<ContributedService> get() = emptyList()
    val offlineEntry: ContributedOfflineEntry? get() = null
    fun runActions(selection: RunPreviewSelection?): List<ContributedAction> = emptyList()
    suspend fun shutdown() {}
}

data class ContributedAction(val title: String, val target: NavKey)
data class ContributedService(val entry: ServiceEntryUi, val target: NavKey)
data class ContributedOfflineEntry(val title: String, val target: NavKey)

enum class PublicThemePreference { SYSTEM, LIGHT, DARK }

/** Only a public provider API key; never the campus Authorization token. */
class PublicRunPreferences(
    val metricUnits: Boolean,
    val samplingSeconds: Int,
    val theme: PublicThemePreference,
    val provider: RunMapProvider?,
    val publicMapKey: String?,
    val skipMapWarning: Boolean,
) {
    override fun toString(): String = "PublicRunPreferences(metric=$metricUnits, sampling=$samplingSeconds, theme=$theme, provider=$provider, redacted)"
}

class PublicFeatureDependencies(
    val runPreviewSource: RunPreviewSource,
    val runPreferences: StateFlow<PublicRunPreferences>,
)

class ProductIdentity(
    val id: String,
    val displayName: String,
    val privacySummary: String,
    val updatePolicy: ProductUpdatePolicy = ProductUpdatePolicy.Standard,
) {
    init { require(id.matches(Regex("[a-z][a-z0-9-]{0,31}")) && displayName.isNotBlank() && privacySummary.isNotBlank()) }
}

/** One invocation per owner generation; construction must not perform IO or request permission. */
fun interface ProductComposition {
    fun create(scope: CoroutineScope, public: PublicFeatureDependencies): ApplicationContributions
    val identity: ProductIdentity get() = StandardIdentity
}

val StandardIdentity = ProductIdentity("standard", "Oh My ZSC · Standard", AppText.Edition.STANDARD_PRIVACY_HINT)

object StandardComposition : ProductComposition {
    override fun create(scope: CoroutineScope, public: PublicFeatureDependencies): ApplicationContributions = EmptyContributions
}

private object EmptyContributions : ApplicationContributions
