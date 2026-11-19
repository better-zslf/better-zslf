package io.github.betterzslf.edition

import io.github.betterzslf.Route
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.update.GithubReleasePolicy
import io.github.betterzslf.feature.update.ProductUpdateChannel
import io.github.betterzslf.feature.update.ProductUpdatePolicy
import io.github.betterzslf.feature.update.StableVersion
import io.github.betterzslf.navigation.StandardNavigation
import io.github.betterzslf.platform.ApkUpdateSpec
import top.yukonga.miuix.kmp.nav.core.NavEntryBuilder
import kotlin.test.*

/** Public-product defaults only. Optional capabilities are not enabled by a global edition flag. */
class EditionAccessTest {
    @Test fun standardProductRejectsEveryRetiredPrivateOrStringExtensionDestination() {
        for (kind in listOf("water", "runimport", "runmanual", "runplan", "runsupplement", "extension:water", "extension:fixture")) {
            val key = Route.Page(kind)
            assertFalse(StandardNavigation.accepts(key))
            assertFalse(StandardNavigation.isOffline(key))
            assertFalse(StandardNavigation.swipeDismiss(key))
        }
    }

    @Test fun realRunRoutesRemainPublicWithoutAnOptionalFeatureSwitch() {
        for (kind in listOf("runs", "rundetail", "runrecords", "runrecord", "runtracker")) {
            assertTrue(StandardNavigation.accepts(Route.Page(kind)))
            assertFalse(StandardNavigation.isOffline(Route.Page(kind)))
        }
        assertFalse(StandardNavigation.swipeDismiss(Route.Page("runtracker")))
        assertTrue(StandardNavigation.swipeDismiss(Route.Page("runrecord")))
    }

    @Test fun onlyExplicitPublicOfflinePagesSurviveWithoutACampusSession() {
        for (kind in listOf("document", "resetpassword")) assertTrue(StandardNavigation.isOffline(Route.Page(kind)))
        for (key in listOf(Route.Main, Route.Login, Route.TabRoot(0), Route.Page("article"), Route.Page("runtracker"))) {
            assertFalse(StandardNavigation.isOffline(key))
        }
        assertFalse(StandardNavigation.swipeDismiss(Route.Login))
        assertFalse(StandardNavigation.swipeDismiss(Route.Page("resetpassword")))
        assertTrue(StandardNavigation.swipeDismiss(Route.Page("document")))
    }

    @Test fun standardNavigationDoesNotRegisterOrInvokePrivateEntries() {
        var opened = false
        var backed = false
        StandardNavigation.registerEntries(NavEntryBuilder(), { opened = true }, { backed = true })
        assertFalse(opened)
        assertFalse(backed)
    }

    @Test fun omittedUpdateConfigurationSelectsThePublicStableProduct() {
        val policy = ProductUpdatePolicy.Standard
        assertEquals("better-zslf/better-zslf", policy.repository)
        assertEquals("io.github.betterzslf", policy.androidApplicationId)
        assertEquals("", policy.versionNameSuffix)
        assertEquals("better-zslf-", policy.apkAssetPrefix)
        assertEquals(ProductUpdateChannel.STABLE, policy.channel)
        assertEquals("https://api.github.com/repos/better-zslf/better-zslf/releases/latest", policy.latestApiUrl)
        assertEquals("better-zslf-1.2.3.apk", policy.apkAssetName("1.2.3"))
    }

    @Test fun defaultUpdatesRejectOtherRepositoryAndProductSuffix() {
        val url = "https://github.com/better-zslf/better-zslf/releases/download/v1.2.3/better-zslf-1.2.3.apk"
        val spec = ApkUpdateSpec("1.2.3", url, "a".repeat(64), 1024)
        assertTrue(GithubReleasePolicy.isInstallSpec(spec))
        assertFalse(GithubReleasePolicy.isInstallSpec(spec.copy(downloadUrl = url.replace("better-zslf/better-zslf", "fixture/other-product"))))
        assertFalse(GithubReleasePolicy.isInstallSpec(spec.copy(downloadUrl = "$url?other=product")))
        assertFalse(GithubReleasePolicy.isInstallSpec(spec.copy(versionName = "1.2.3-pro")))
    }

    @Test fun defaultRemoteVersionParserIsStableAndPublicOnly() {
        assertEquals("1.2.3", assertNotNull(StableVersion.parse("1.2.3")).versionName)
        assertTrue(assertNotNull(StableVersion.parse("1.2.3")) < assertNotNull(StableVersion.parse("1.2.4")))
        for (invalid in listOf("1.2.3-pro", "1.2.3-beta", "1.2.3-pro-pro", "1.2.3+pro", "1.2.3-PRO", "1.2.3-rc1")) {
            assertNull(StableVersion.parse(invalid))
        }
    }

    @Test fun unavailableAndStandardPrivacyCopyHaveIndependentGoldenExpectations() {
        assertEquals("当前版本未包含此功能", AppText.Edition.FEATURE_UNAVAILABLE_TITLE)
        assertEquals("请返回，选择当前版本提供的功能。", AppText.Edition.REOPEN_AVAILABLE_ENTRY_HINT)
        assertEquals("无广告、无统计和崩溃上报。校园服务直连学校；真实 GPS 记录只保存在本机，当前版本未启用上传。", AppText.Edition.STANDARD_PRIVACY_HINT)
    }
}
