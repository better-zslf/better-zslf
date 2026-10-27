package io.github.betterzslf.edition

import io.github.betterzslf.Route
import io.github.betterzslf.pushUnique
import io.github.betterzslf.core.text.AppText
import io.github.betterzslf.feature.update.GithubReleasePolicy
import io.github.betterzslf.feature.update.StableVersion
import io.github.betterzslf.platform.ApkUpdateSpec
import kotlinx.coroutines.test.runTest
import top.yukonga.miuix.kmp.nav.core.NavKey
import kotlin.test.*

class EditionAccessTest {
    @Test fun omittedProSwitchesAreClosedEvenInAProBuild() {
        val flags = EditionFeatureFlags()
        assertFalse(EditionAccess.waterEnabled(true, flags))
        assertFalse(EditionAccess.routeEditorsEnabled(true, flags))
        for (kind in listOf("water", "runimport", "runmanual", "runplan", "runsupplement")) {
            assertFalse(EditionAccess.allowsPage(kind, true, flags))
        }
    }

    @Test fun standardNeverOpensProRoutesEvenWithTrueSwitches() {
        val flags = EditionFeatureFlags(water = true, routeEditors = true)
        for (kind in listOf("water", "runimport", "runmanual", "runplan", "runsupplement")) {
            assertFalse(EditionAccess.allowsPage(kind, false, flags))
            assertTrue(EditionAccess.allowsPage(kind, true, flags))
        }
    }

    @Test fun realRunRoutesAreNotProFeatures() {
        for (kind in listOf("runs", "rundetail", "runrecords", "runrecord", "runtracker")) {
            assertTrue(EditionAccess.allowsPage(kind, false, EditionFeatureFlags()))
            assertTrue(EditionAccess.allowsPage(kind))
        }
    }

    @Test fun selectedEditionControlsBothNavigationAndOfflineRestoration() {
        val stack = mutableListOf<NavKey>(Route.Main)
        for (kind in listOf("water", "runimport")) stack.pushUnique(Route.Page(kind))
        assertEquals(if (BuildEdition.isPro) 3 else 1, stack.size)
        assertEquals(BuildEdition.isPro, EditionAccess.allowsOfflinePage("water"))
        assertFalse(EditionAccess.allowsOfflinePage("runtracker"))
        assertTrue(EditionAccess.allowsOfflinePage("document"))
    }

    @Test fun editionIdentifiersRepositoryAndAppIdHaveOneSelectedPair() {
        if (BuildEdition.isPro) {
            assertEquals("pro", BuildEdition.id)
            assertEquals("better-zslf/pro-ci", BuildEdition.updateRepository)
            assertEquals("io.github.betterzslf.pro", BuildEdition.androidApplicationId)
            assertEquals("-pro", BuildEdition.versionNameSuffix)
        } else {
            assertEquals("standard", BuildEdition.id)
            assertEquals("better-zslf/better-zslf", BuildEdition.updateRepository)
            assertEquals("io.github.betterzslf", BuildEdition.androidApplicationId)
            assertEquals("", BuildEdition.versionNameSuffix)
        }
        assertEquals(BuildEdition.isPro, EditionAccess.waterEnabled)
        assertEquals(BuildEdition.isPro, EditionAccess.routeEditorsEnabled)
    }

    @Test fun updatesStayInSelectedRepositoryAndRejectTheOtherEdition() {
        val expectedRepo = if (BuildEdition.isPro) "better-zslf/pro-ci" else "better-zslf/better-zslf"
        val otherRepo = if (BuildEdition.isPro) "better-zslf/better-zslf" else "better-zslf/pro-ci"
        assertEquals("https://api.github.com/repos/$expectedRepo/releases/latest", GithubReleasePolicy.LATEST_API_URL)
        val version = "1.2.3${BuildEdition.versionNameSuffix}"
        val filename = "better-zslf-$version.apk"
        val url = "https://github.com/$expectedRepo/releases/download/v$version/$filename"
        val spec = ApkUpdateSpec(version, url, "a".repeat(64), 1024)
        assertTrue(GithubReleasePolicy.isInstallSpec(spec))
        assertFalse(GithubReleasePolicy.isInstallSpec(spec.copy(downloadUrl = url.replace(expectedRepo, otherRepo))))
        assertFalse(GithubReleasePolicy.isInstallSpec(spec.copy(downloadUrl = "$url?other=edition")))
    }

    @Test fun proSuffixIsEditionSpecificAndNumericMarketingVersionsStillCompare() {
        assertNotNull(StableVersion.parse("1.2.3"))
        val pro = StableVersion.parse("1.2.3-pro")
        if (BuildEdition.isPro) {
            assertEquals("1.2.3-pro", assertNotNull(pro).versionName)
            assertEquals(0, pro.compareTo(assertNotNull(StableVersion.parse("1.2.3"))))
            assertTrue(pro < assertNotNull(StableVersion.parse("1.2.4-pro")))
        } else assertNull(pro)
        for (invalid in listOf("1.2.3-beta", "1.2.3-pro-pro", "1.2.3+pro", "1.2.3-PRO")) assertNull(StableVersion.parse(invalid))
    }

    @Test fun selectedFacadeConstructionDoesNotStartNativeWork() = runTest {
        assertNotNull(createEditionFeatures(backgroundScope))
    }

    @Test fun newEditionCopyIsPureAndHasIndependentGoldenExpectations() {
        assertEquals("当前版本未包含此功能", AppText.Edition.FEATURE_UNAVAILABLE_TITLE)
        assertEquals("请返回，选择当前版本提供的功能。", AppText.Edition.REOPEN_AVAILABLE_ENTRY_HINT)
        assertEquals("无广告、无统计和崩溃上报。校园服务直连学校；真实 GPS 记录只保存在本机，当前版本未启用上传。", AppText.Edition.STANDARD_PRIVACY_HINT)
    }
}
