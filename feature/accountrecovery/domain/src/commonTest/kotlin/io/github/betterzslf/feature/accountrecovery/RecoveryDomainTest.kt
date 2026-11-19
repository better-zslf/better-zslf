package io.github.betterzslf.feature.accountrecovery

import kotlin.test.*

class RecoveryDomainTest {
    @Test fun nominalTargetIsValidatedAndDiagnosticsNeverExposeItsValue() {
        for (raw in listOf("", " ", " leading", "trailing ", "line\nbreak", "control\u0000id", "tab\tid"))
            assertNull(RecoveryTarget.fromVerifiedId(raw))
        for (raw in listOf("verified-target", "17", "1e3", "目标标识")) {
            val target = assertNotNull(RecoveryTarget.fromVerifiedId(raw))
            assertEquals(raw, target.id)
            assertEquals("RecoveryTarget(redacted)", target.toString())
            assertNotSame(target, assertNotNull(RecoveryTarget.fromVerifiedId(raw)))
            assertFalse(RecoveryVerifyOutcome.Verified(target).toString().contains(raw))
        }
    }

    @Test fun phoneRequiresExactlyElevenAsciiDigits() {
        assertNull(recoveryPhoneIssue("13800000000"))
        for (raw in listOf("", "1380000000", "138000000000", " 13800000000", "13800000000 ", "１３８００００００００", "1380000000a"))
            assertEquals(RecoveryValidationIssue.PHONE_FORMAT, recoveryPhoneIssue(raw))
    }

    @Test fun verificationCodeRejectsBlankWhitespaceAndControlsWithoutInventingNumericRestrictions() {
        for (raw in listOf("", " ", "00 0000", "000000\n", "000\t000", "000\u000000"))
            assertEquals(RecoveryValidationIssue.SMS_CODE_REQUIRED, recoveryCodeIssue(raw))
        assertNull(recoveryCodeIssue("000000"))
        assertNull(recoveryCodeIssue("fixture-code"))
    }

    @Test fun existingSchoolPasswordPolicyAndConfirmationPrecedenceStayExact() {
        for (raw in listOf("Fixture#1", "ABCD!123", "a1#aaaaa")) {
            assertTrue(isValidRecoveryNewPassword(raw))
            assertNull(recoveryPasswordIssue(raw, raw))
            assertEquals(RecoveryValidationIssue.PASSWORD_MISMATCH, recoveryPasswordIssue(raw, "Other#123"))
        }
        for (raw in listOf("short", "onlyletters", "123456789!", "Letters123", "Ab1! abcdef", "Fixture#1\n", "中中中中中1!中")) {
            assertFalse(isValidRecoveryNewPassword(raw))
            assertEquals(RecoveryValidationIssue.PASSWORD_POLICY, recoveryPasswordIssue(raw, raw))
            assertEquals(RecoveryValidationIssue.PASSWORD_POLICY, recoveryPasswordIssue(raw, "Different#1"))
        }
    }

    @Test fun domainClassificationsAreFixedAndDoNotConflateSmsAcknowledgementWithResetLogout() {
        assertEquals(listOf("AUTHENTICATION", "BUSINESS", "VALIDATION", "TIMEOUT", "NETWORK", "HTTP", "DECODE", "STORAGE"),
            RecoveryIssue.entries.map { it.name })
        assertEquals(listOf("CLEARED", "CLEAR_FAILED", "NEW_SESSION_PRESERVED"), RecoveryLogout.entries.map { it.name })
        assertEquals(listOf("PHONE_FORMAT", "SMS_CODE_REQUIRED", "PASSWORD_POLICY", "PASSWORD_MISMATCH"),
            RecoveryValidationIssue.entries.map { it.name })
        assertEquals(RecoverySmsOutcome.Accepted, RecoverySmsOutcome.Accepted)
        assertNotEquals(RecoveryResetOutcome.Accepted(RecoveryLogout.CLEARED), RecoveryResetOutcome.Accepted(RecoveryLogout.CLEAR_FAILED))
    }
}
