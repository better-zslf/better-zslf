package io.github.betterzslf.ui

import io.github.betterzslf.core.text.AppText
import kotlin.test.*

/**
 * The campus-network status card against design §7.
 *
 * The colours and the connected-state wording are easy to get subtly wrong because nothing throws:
 * a wrong swatch still renders, and a differently prefixed duration still reads plausibly. These
 * assertions pin the parts the design specifies exactly.
 */
class CampusStatusCardSpecTest {

    @Test fun theNeutralDotIsTheExactGreyTheDesignSpecifies() {
        // §7 gives #8E8E93 for both neutral states. Using a theme colour instead shifts the swatch
        // with the palette, which is not what the drawing says.
        assertEquals(0xFF8E8E93.toInt(), AppUiTokens.NeutralDot.value.toLong().let {
            // Compose Color packs ARGB; compare through the 32-bit value.
            (AppUiTokens.NeutralDot.value shr 32).toInt()
        })
    }

    @Test fun theThreeSignalColoursMatchTheDesignExactly() {
        assertEquals(0xFF33C759.toInt(), (AppUiTokens.Positive.value shr 32).toInt())
        assertEquals(0xFFFF9400.toInt(), (AppUiTokens.Warning.value shr 32).toInt())
        assertEquals(0xFFFA3B3B.toInt(), (AppUiTokens.Negative.value shr 32).toInt())
    }

    @Test fun theConnectedLineReadsAsTheDesignDrawsIt() {
        // §7: `已用 2 小时 14 分 · 138****8000`
        assertEquals("已用 2 小时14 分 · 138****8000",
            AppText.CampusNetwork.statusOnlineHint("2 小时14 分", "138****8000"))
    }

    @Test fun withoutAPhoneTheLineKeepsTheSamePrefixRatherThanDangling() {
        // The separator must not survive with an empty right-hand side.
        assertEquals("已用 2 小时14 分", AppText.CampusNetwork.usedFor("2 小时14 分"))
        assertFalse(AppText.CampusNetwork.usedFor("2 小时14 分").contains('·'))
    }

    @Test fun theSixStatesAllHaveDistinctPrimaryCopy() {
        val titles = listOf(
            AppText.CampusNetwork.STATUS_NOT_SIGNED_IN,
            AppText.CampusNetwork.STATUS_OFFLINE,
            AppText.CampusNetwork.STATUS_CONNECTING,
            AppText.CampusNetwork.STATUS_ONLINE,
            AppText.CampusNetwork.STATUS_FAILED,
            AppText.CampusNetwork.STATUS_NOT_ON_CAMPUS,
        )
        assertEquals(6, titles.toSet().size, "two states share a title: $titles")
        assertTrue(titles.all { it.isNotBlank() })
    }

    @Test fun theDesignForbidsCertainAffordancesOnThisCard() {
        // §7: no saved password, no plaintext password display, no inline change-password form.
        val cardCopy = listOf(
            AppText.CampusNetwork.STATUS_NOT_SIGNED_IN,
            AppText.CampusNetwork.STATUS_OFFLINE,
            AppText.CampusNetwork.STATUS_CONNECTING,
            AppText.CampusNetwork.STATUS_ONLINE,
            AppText.CampusNetwork.STATUS_FAILED,
            AppText.CampusNetwork.STATUS_NOT_ON_CAMPUS,
            AppText.CampusNetwork.STATUS_NOT_SIGNED_IN_HINT,
            AppText.CampusNetwork.STATUS_OFFLINE_HINT,
            AppText.CampusNetwork.STATUS_CONNECTING_HINT,
            AppText.CampusNetwork.STATUS_NOT_ON_CAMPUS_HINT,
        ).joinToString(" ")
        for (forbidden in listOf("记住密码", "自动登录", "保存密码")) {
            assertFalse(cardCopy.contains(forbidden), "card copy mentions $forbidden")
        }
    }

    @Test fun theDurationLabelIsDerivedFromMillisNotFormattedByAPlatform() {
        // Two shapes only: minutes alone, or hours plus minutes. No locale formatting anywhere.
        assertEquals("0 分", io.github.betterzslf.ui.campusnet.campusDurationLabel(0))
        assertEquals("59 分", io.github.betterzslf.ui.campusnet.campusDurationLabel(59 * 60_000L))
        assertEquals("1 小时0 分", io.github.betterzslf.ui.campusnet.campusDurationLabel(60 * 60_000L))
        assertEquals("2 小时14 分",
            io.github.betterzslf.ui.campusnet.campusDurationLabel((2 * 60 + 14) * 60_000L))
    }
}
