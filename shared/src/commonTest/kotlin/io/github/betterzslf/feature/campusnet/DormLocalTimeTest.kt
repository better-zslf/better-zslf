package io.github.betterzslf.feature.campusnet

import kotlin.test.*

/**
 * The `local-time` field.
 *
 * Hand-rolled date conversion is worth testing properly: a wrong timestamp does not fail loudly, it
 * produces a well-formed document the gateway rejects the same way it rejects a bad password.
 */
class DormLocalTimeTest {

    private fun utc(epochMillis: Long) = formatDormLocalTime(epochMillis, offsetMinutes = 0)
    private fun plus8(epochMillis: Long) = formatDormLocalTime(epochMillis, offsetMinutes = 480)

    @Test fun theEpochFormatsAsTheExpectedCalendarDate() {
        assertEquals("1970-01-01 00:00:00", utc(0))
        assertEquals("1970-01-01 00:00:01", utc(1000))
        assertEquals("1970-01-01 00:01:00", utc(60_000))
        assertEquals("1970-01-01 01:00:00", utc(3_600_000))
    }

    @Test fun knownDatesFromThePastAndFuture() {
        // 2001-09-09T01:46:40Z = 1e9 seconds
        assertEquals("2001-09-09 01:46:40", utc(1_000_000_000_000))
        // 2033-05-18T03:33:20Z = 2e9 seconds
        assertEquals("2033-05-18 03:33:20", utc(2_000_000_000_000))
        // 2024-02-29: a leap day, which is where naive day arithmetic usually breaks.
        assertEquals("2024-02-29 12:00:00", utc(1_709_208_000_000))
    }

    @Test fun leapYearRulesAreFollowed() {
        // 2000 was a leap year (divisible by 400); 1900 was not. 2100 will not be.
        assertEquals("2000-02-29 00:00:00", utc(951_782_400_000))
        // 2024-03-01 must follow 2024-02-29 directly.
        assertEquals("2024-03-01 00:00:00", utc(1_709_251_200_000))
    }

    @Test fun monthAndDayBoundariesRollOver() {
        // 2023-12-31 23:59:59 then one second later.
        assertEquals("2023-12-31 23:59:59", utc(1_704_067_199_000))
        assertEquals("2024-01-01 00:00:00", utc(1_704_067_200_000))
    }

    @Test fun theLocalOffsetIsAppliedInTheRightDirection() {
        // +08:00 is ahead of UTC, so the same instant reads eight hours later.
        assertEquals("1970-01-01 08:00:00", plus8(0))
        assertEquals("1970-01-01 00:00:00", utc(0))
        // A negative offset goes the other way and must still produce a valid date, not day zero.
        assertEquals("1969-12-31 19:00:00", formatDormLocalTime(0, offsetMinutes = -300))
    }

    @Test fun theFormatIsWhatTheGatewayParses() {
        // %Y-%m-%d %H:%M:%S: fixed width, zero padded, space separated.
        val text = utc(1_709_208_000_000)
        assertEquals(19, text.length, text)
        assertEquals('-', text[4])
        assertEquals('-', text[7])
        assertEquals(' ', text[10])
        assertEquals(':', text[13])
        assertEquals(':', text[16])
        assertTrue(text.all { it.isDigit() || it in "- :" }, text)
    }

    @Test fun everyFieldIsZeroPadded() {
        // 2001-01-01 01:01:01 — all single-digit components must still be two characters wide.
        assertEquals("2001-01-01 01:01:01", utc(978_310_861_000))
    }
}
