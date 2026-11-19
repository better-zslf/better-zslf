package io.github.betterzslf.ui.run

import androidx.compose.foundation.lazy.LazyListScope
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.ui.InfoBanner
import io.github.betterzslf.ui.UiTone

fun LazyListScope.runWarnings(warnings: List<String>, keyPrefix: String = "warning") {
    if (warnings.isNotEmpty()) item("$keyPrefix:details") {
        RunDisclosure(AppText.Common.INFORMATION_AND_LIMITS_TITLE) {
            warnings.distinct().forEach { warning -> InfoBanner(warning, tone = UiTone.Neutral) }
        }
    }
}
