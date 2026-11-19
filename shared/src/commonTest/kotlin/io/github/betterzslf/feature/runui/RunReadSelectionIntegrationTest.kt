package io.github.betterzslf.feature.runui

import io.github.betterzslf.core.net.ApiTestFixture
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The original root-selection assertion exercises the real typed data boundary, not a second decoder. */
class RunReadSelectionIntegrationTest {
    @Test fun taskListPlanIdCannotContradictAnExplicitPlanField() = runTest {
        val responses = ArrayDeque(listOf(
            """{"errandList":[{"errandId":"e","id":"plan-a","errandScheduleId":"plan-b"}]}""",
            """{"errandList":[{"errandId":"e","id":"plan-a","errandScheduleId":"plan-a"}]}""",
        ))
        var requests = 0
        val fixture = ApiTestFixture(MockEngine {
            requests++
            respond("""{"code":200,"data":${responses.removeFirst()}}""")
        })
        try {
            val source = assertNotNull(ApiRunReadSource.capture(fixture.api))
            val conflicting = assertIs<RunReadOutcome.Success<RunRows<RunTaskItem>>>(
                source.tasks(source.boundOwnerId)).data.items.single()
            assertNull(conflicting.trid); assertNull(conflicting.scheduleId)
            assertTrue(conflicting.warnings.any { "冲突" in it }, conflicting.warnings.toString())
            assertNull(RunTaskSelection.from(conflicting))
            val matching = assertIs<RunReadOutcome.Success<RunRows<RunTaskItem>>>(
                source.tasks(source.boundOwnerId)).data.items.single()
            assertEquals("plan-a", matching.scheduleId)
            val selection = assertNotNull(RunTaskSelection.from(matching))
            assertEquals("e", selection.errandId)
            assertEquals("plan-a", selection.errandScheduleId)
            assertEquals(2, requests)
        } finally { fixture.close() }
    }
}
