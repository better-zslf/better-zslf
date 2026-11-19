package io.github.betterzslf.feature.accountrecovery

import io.github.betterzslf.core.storage.SessionStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class RecoveryVerifiedTargetTest {
    private fun identity(id: JsonElement): JsonElement = buildJsonObject { put("user", buildJsonObject { put("id", id) }) }

    @Test fun onlyRealUserIdIsAcceptedAndNoOwnerPhoneOrAliasFallbackExists() = runTest {
        val bad = listOf(JsonNull, JsonPrimitive(""), JsonPrimitive(" "), JsonPrimitive(true),
            buildJsonObject { put("id", "nested") }, JsonArray(emptyList()),
            JsonPrimitive(" leading"), JsonPrimitive("trailing "), JsonPrimitive("line\nbreak"))
        for (id in bad) {
            var writes = 0
            val fixture = RecoveryApiFixture(MockEngine { request ->
                if (request.url.encodedPath == "/app/appUser/resetNewPassWord") writes++
                respond(buildJsonObject { put("code", 200); put("data", identity(id)) }.toString())
            })
            try {
                val source = fixture.source()
                assertEquals(RecoverySmsOutcome.Accepted, source.sendCode("13800000000"))
                assertEquals(RecoveryVerifyOutcome.MissingTarget, source.verify("13800000000", "000000"))
                assertNull(recoveryVerifiedTarget(identity(id)))
                assertEquals(0, writes)
                assertNotNull(fixture.sessions.session.value)
            } finally { fixture.close() }
        }
        assertNull(recoveryVerifiedTarget(buildJsonObject { put("userId", "alias-id"); put("id", "another-alias") }))
        assertNull(recoveryVerifiedTarget(buildJsonObject { put("user", JsonPrimitive("wrong-container")) }))
        assertNull(recoveryVerifiedTarget(JsonNull))
        assertNull(recoveryVerifiedTarget(JsonArray(emptyList())))
    }

    @Test fun numericTargetIsPreservedAndNotReplacedByCurrentOwnerOrPhone() = runTest {
        val calls = mutableListOf<String>()
        val fixture = RecoveryApiFixture(MockEngine { request ->
            calls += request.url.encodedPath
            if (request.url.encodedPath == "/app/appUser/resetNewPassWord") assertEquals("17", request.url.parameters["userId"])
            respond("""{"code":200,"data":{"user":{"id":17}}}""")
        })
        try {
            val source = fixture.source()
            assertEquals(RecoverySmsOutcome.Accepted, source.sendCode("13800000000"))
            val target = assertIs<RecoveryVerifyOutcome.Verified>(source.verify("13800000000", "000000")).target
            assertEquals("17", target.id)
            assertNotEquals("fixture-owner", target.id)
            assertNotEquals("13800000000", target.id)
            assertEquals(RecoveryResetOutcome.Accepted(RecoveryLogout.CLEARED), source.reset(target, "Fixture#1"))
            assertEquals(listOf("/app/campusNetwork/checkPhone", "/app/appUser/checkPhone", "/app/appUser/resetNewPassWord"), calls)
            assertNull(fixture.sessions.session.value)
            assertNull(fixture.storage.get(SessionStore.STORAGE_KEY))
        } finally { fixture.close() }
    }

    @Test fun finiteNumericResponseKeepsItsExactSpellingAndNonFiniteNumbersAreRejected() {
        for (raw in listOf("0", "-17", "1.25", "1e3", "9007199254740993")) {
            val data = Json.parseToJsonElement("""{"user":{"id":$raw}}""")
            assertEquals(raw, assertNotNull(recoveryVerifiedTarget(data)).id)
        }
        assertNull(recoveryVerifiedTarget(Json.parseToJsonElement("""{"user":{"id":1e999}}""")))
        assertEquals("verified-target", assertNotNull(recoveryVerifiedTarget(identity(JsonPrimitive("verified-target")))).id)
    }
}
