package io.github.betterzslf.feature.schedule

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Explicit opt-in, read-only integration probe. Ordinary CI skips it; no credential is a fixture.
 * Invoke only with per-process SCHEDULE_TEST_ACCOUNT/PASSWORD and SCHEDULE_LIVE_PROBE=1.
 * Never print identity, course content, cookies, raw responses or exception messages from engines.
 */
class AcademicLiveProbeTest {
    @Test fun verifyProductionAcademicSourceReadOnly() = runBlocking {
        val account = System.getenv("SCHEDULE_TEST_ACCOUNT")
        val password = System.getenv("SCHEDULE_TEST_PASSWORD")
        assumeTrue(System.getenv("SCHEDULE_LIVE_PROBE") == "1" && !account.isNullOrBlank() && !password.isNullOrEmpty())
        val source = createAcademicSource(createRedactedAcademicProbeClient(account!!, password!!))
        try {
            val identity = source.authenticate(account!!, password!!)
            assertTrue(identity.account == account, "Server identity did not match")
            val terms = source.terms()
            assertTrue(terms.isNotEmpty(), "Missing server terms")
            val selected = assertNotNull(terms.singleOrNull { it.isDefault } ?: terms.singleOrNull(), "Ambiguous default semester")
            val snapshot = source.fetch(selected)
            assertEquals(selected.id, snapshot.term.id, "Semester response mismatch")
            assertTrue(snapshot.complete, "Partial timetable parse")
            assertTrue(validateSchedulePeriods(defaultSchedulePeriods()))
            println("academic_live_probe: identity_verified=true; terms=${terms.size}; courses=${snapshot.courses.size}; calendar_weeks=${snapshot.weekMondays.size}; warnings=${snapshot.warnings.size}; complete=${snapshot.complete}")
        } finally {
            source.close()
        }
    }
}
