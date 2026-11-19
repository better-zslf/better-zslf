package io.github.betterzslf.ui.auth

import androidx.compose.runtime.Composable
import kotlin.test.*
import top.yukonga.miuix.kmp.basic.Text

class AuthLoginUiTest {
    @Test
    fun chooserDisplayDerivesEnterVisibilityFromEitherBoundFlag() {
        assertFalse(AuthChooserDisplay().canEnter)
        assertTrue(AuthChooserDisplay(campusBound = true).canEnter)
        assertTrue(AuthChooserDisplay(academicBound = true).canEnter)
        assertTrue(AuthChooserDisplay(campusBound = true, academicBound = true).canEnter)
    }

    @Test
    fun chooserDisplayDefaultsKeepTheOriginalEntrySetVisible() {
        val display = AuthChooserDisplay()
        assertTrue(display.showDormLink)
        assertFalse(display.campusBound)
        assertFalse(display.academicBound)
    }

    @Test
    fun chooserDisplayCarriesOnlyTheThreeSafeBooleans() {
        val bound = AuthChooserDisplay(showDormLink = false, campusBound = true, academicBound = false)
        assertFalse(bound.showDormLink)
        assertTrue(bound.campusBound)
        assertFalse(bound.academicBound)
    }

    @Test
    fun campusDisplayDefaultsRenderAnIdleUnnoticedForm() {
        val display = CampusLoginDisplay()
        assertFalse(display.busy)
        assertNull(display.notice)
        assertNull(display.extraLabel)
        assertEquals("", display.extraKey)
    }

    @Test
    fun campusNoticeCarriesOnlyFixedResolvedCopy() {
        val notice = CampusLoginNotice(title = "fixture-title", message = "fixture-message")
        assertEquals("fixture-title", notice.title)
        assertEquals("fixture-message", notice.message)
    }

    @Test
    fun controlsDefaultsDisableSubmitAndRecoveryLikeTheOriginalGates() {
        val controls = CampusLoginControls()
        assertFalse(controls.submitEnabled)
        assertFalse(controls.recoveryAvailable)
    }

    @Test
    fun nineIntentsAreExactlyTheCollectedOriginalSurface() {
        assertEquals(
            listOf(
                "CHOOSE_CAMPUS", "CHOOSE_ACADEMIC", "CHOOSE_DORM", "ENTER_APP",
                "BACK", "SUBMIT", "RECOVER_PASSWORD", "USER_AGREEMENT", "PRIVACY_POLICY",
            ),
            AuthLoginUiEvent.entries.map { it.name },
        )
    }
}

/** Compile fixture: both surfaces compose from fake display snapshots and root-owned slots only. */
@Composable
internal fun AuthLoginFakeContentFixture() {
    AuthChooserContent(
        display = AuthChooserDisplay(showDormLink = true, campusBound = true, academicBound = false),
        onEvent = {},
    )
    CampusLoginContent(
        display = CampusLoginDisplay(
            busy = false,
            notice = CampusLoginNotice(title = "fixture-title", message = "fixture-message"),
            extraLabel = "fixture-extra",
            extraKey = "fixture-opaque-key",
        ),
        controls = CampusLoginControls(submitEnabled = true, recoveryAvailable = true),
        onEvent = {},
        onExtraEntry = {},
        accountField = { Text("Fake account slot") },
        passwordField = { Text("Fake password slot") },
    )
}
