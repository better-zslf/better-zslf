package io.github.betterzslf.core.log

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLogTest {
    @BeforeTest fun reset() { AppLog.setEnabled(false); AppLog.clear() }
    @AfterTest fun cleanup() { AppLog.setEnabled(false); AppLog.clear() }

    @Test
    fun disabledLogKeepsNothingAndExportsNothing() {
        AppLog.info("net", "GET /app/login -> 200 12ms")
        assertEquals(0, AppLog.snapshot().size)
        assertEquals("", AppLog.exportText())
        assertFalse(AppLog.isEnabled)
    }

    @Test
    fun enablingRecordsLevelsTagsAndExportText() {
        AppLog.setEnabled(true)
        AppLog.debug("ui", "opened settings")
        AppLog.warn("cache", "miss")
        AppLog.error("net", "timeout")
        val entries = AppLog.snapshot()
        assertEquals(3, entries.size)
        assertEquals(LogLevel.DEBUG, entries[0].level)
        assertEquals("ui", entries[0].tag)
        val text = AppLog.exportText()
        assertContains(text, "DEBUG")
        assertContains(text, "opened settings")
        assertContains(text, "WARN")
        assertContains(text, "ERROR")
    }

    @Test
    fun disabledAgainDropsRetainedRecords() {
        AppLog.setEnabled(true)
        AppLog.info("ui", "one")
        assertTrue(AppLog.snapshot().isNotEmpty())
        AppLog.setEnabled(false)
        assertEquals(0, AppLog.snapshot().size)
        assertEquals("", AppLog.exportText())
    }

    @Test
    fun bufferKeepsNewestEntriesOnly() {
        AppLog.setEnabled(true)
        repeat(AppLog.MAX_ENTRIES + 5) { index -> AppLog.info("bulk", "entry $index") }
        val entries = AppLog.snapshot()
        assertEquals(AppLog.MAX_ENTRIES, entries.size)
        assertEquals("entry 5", entries.first().message)
        assertEquals("entry ${AppLog.MAX_ENTRIES + 4}", entries.last().message)
    }

    @Test
    fun clearEmptiesTheBufferWithoutDisabling() {
        AppLog.setEnabled(true)
        AppLog.info("ui", "one")
        AppLog.clear()
        assertEquals(0, AppLog.snapshot().size)
        assertTrue(AppLog.isEnabled)
        AppLog.info("ui", "two")
        assertEquals(1, AppLog.snapshot().size)
    }

    @Test
    fun credentialsNeverReachTheBuffer() {
        AppLog.setEnabled(true)
        AppLog.info("net", "POST /app/login?username=2000000000000&password=secret123 -> 200")
        AppLog.info("net", "Authorization: Bearer abc.def.ghi")
        AppLog.info("net", "Cookie: session=zzz")
        AppLog.event("auth", "login", mapOf("accessToken" to "tok-1", "userId" to "2000000000000"))
        AppLog.info("net", """{"password":"p","accessToken":"t","userId":"u"}""")
        val text = AppLog.exportText()
        listOf("secret123", "abc.def.ghi", "zzz", "tok-1", "2000000000000", "\"p\"", "\"t\"", "\"u\"").forEach {
            assertFalse(text.contains(it), "leaked: $it")
        }
        assertContains(text, "***")
    }

    @Test
    fun httpSummaryDropsQueryAndBodyAndKeepsStatus() {
        AppLog.setEnabled(true)
        AppLog.http("GET", "/appUser/getUserInfo?token=secret&page=1", 200, 42, 1024)
        AppLog.http("POST", "/app/login", null, 8)
        val text = AppLog.exportText()
        assertContains(text, "GET /appUser/getUserInfo -> 200 42ms 1024B")
        assertContains(text, "POST /app/login -> failed 8ms")
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("page=1"))
    }

    @Test
    fun timestampFormatIsStableUtcWithMillis() {
        // 2026-10-27T16:02:01.234Z
        assertEquals("2026-10-27 16:02:01.234", formatLogTimestamp(1793116921234L))
        assertEquals("1970-01-01 00:00:00.000", formatLogTimestamp(0L))
    }

    @Test
    fun snapshotIsAnImmutableCopy() {
        AppLog.setEnabled(true)
        AppLog.info("ui", "one")
        val first = AppLog.snapshot()
        AppLog.info("ui", "two")
        assertEquals(1, first.size)
        assertEquals(2, AppLog.snapshot().size)
    }

    @Test
    fun sensitiveFieldsAreRedactedButPlainFieldsSurvive() {
        AppLog.setEnabled(true)
        AppLog.event("run", "start", mapOf("points" to "12", "duration" to "9s"))
        val text = AppLog.exportText()
        assertContains(text, "points=12")
        assertContains(text, "duration=9s")
    }
}
