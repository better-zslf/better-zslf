package io.github.betterzslf.feature.accountrecovery

import kotlin.test.*

class AccountRecoveryBackPolicyTest {
    @Test fun onlySmsSendingAndResettingHoldBack() {
        for (phase in AccountRecoveryPhase.entries) {
            assertEquals(phase == AccountRecoveryPhase.RESETTING,
                recoveryWriteInFlight(AccountRecoveryState(phase = phase)))
            assertTrue(recoveryWriteInFlight(AccountRecoveryState(phase = phase, sending = true)))
        }
    }

    @Test fun verificationCanStillNavigateBackAlthoughItIsBusy() {
        val state = AccountRecoveryState(verifying = true)
        var navigated = 0
        var notices = 0
        assertTrue(state.busy)
        recoveryGuardedBack({ state }, { navigated++ }, { notices++ })
        assertEquals(1, navigated)
        assertEquals(0, notices)
        assertTrue(state.verifying)
    }

    @Test fun arrowAndSystemCommitUseTheSameWriteGateWithoutCancelling() {
        val state = AccountRecoveryState(sending = true)
        var navigated = 0
        var notices = 0
        val back: () -> Unit = { recoveryGuardedBack({ state }, { navigated++ }, { notices++ }) }
        back() // Screen arrow callback.
        back() // Predictive/discrete system commit callback.
        assertEquals(0, navigated)
        assertEquals(2, notices)
        assertTrue(state.sending)
        assertEquals(AccountRecoveryPhase.EDITING, state.phase)
    }

    @Test fun gestureStartingIdleButCommittingDuringResetReadsTheNewestState() {
        var state = AccountRecoveryState()
        var navigated = 0
        var notices = 0
        val commit: () -> Unit = { recoveryGuardedBack({ state }, { navigated++ }, { notices++ }) }
        state = state.copy(phase = AccountRecoveryPhase.RESETTING)
        commit()
        assertEquals(0, navigated)
        assertEquals(1, notices)
        assertEquals(AccountRecoveryPhase.RESETTING, state.phase)
    }

    @Test fun completionBeforeCommitPreservesUncertainOutcomeAndThenAllowsBack() {
        var state = AccountRecoveryState(phase = AccountRecoveryPhase.RESETTING)
        var navigated = 0
        var notices = 0
        val commit: () -> Unit = { recoveryGuardedBack({ state }, { navigated++ }, { notices++ }) }
        state = AccountRecoveryState(phase = AccountRecoveryPhase.UNCERTAIN, message = "fixture unknown result")
        val result = state
        commit()
        assertEquals(1, navigated)
        assertEquals(0, notices)
        assertSame(result, state)
        assertEquals(AccountRecoveryPhase.UNCERTAIN, state.phase)
    }

    @Test fun logoutSessionReplacementAndOtherStoppedStatesDoNotBecomeTrapped() {
        for (phase in listOf(AccountRecoveryPhase.GUEST, AccountRecoveryPhase.COMPLETED,
            AccountRecoveryPhase.CLEANUP_WARNING, AccountRecoveryPhase.FAILED,
            AccountRecoveryPhase.SESSION_CHANGED, AccountRecoveryPhase.CLOSED)) {
            val state = AccountRecoveryState(phase = phase)
            var navigated = 0
            recoveryGuardedBack({ state }, { navigated++ }, { fail("Stopped state should preserve ordinary navigation") })
            assertEquals(1, navigated)
            assertEquals(phase, state.phase)
        }
    }

    @Test fun blockedBackOnlyShowsFixedNoticeAndNeverRewritesTheResetResult() {
        val state = AccountRecoveryState(phase = AccountRecoveryPhase.RESETTING,
            preview = AccountRecoveryPreview(1, "138••••00"), message = "fixture awaiting server")
        var observed: AccountRecoveryState? = null
        var notice: String? = null
        recoveryGuardedBack({ observed = state; state }, { fail("Pending reset must stay on page") }, {
            notice = RECOVERY_WRITE_BACK_NOTICE
        })
        assertSame(state, observed)
        assertEquals(AccountRecoveryPhase.RESETTING, state.phase)
        assertEquals("请求可能已受理，请等待结果。返回页面不会撤销请求。", notice)
    }
}
