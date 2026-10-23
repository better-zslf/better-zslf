package io.github.betterzslf.data

import io.github.betterzslf.core.net.ApiTestFixture
import io.github.betterzslf.core.result.ApiError
import io.github.betterzslf.core.result.ApiResult
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlin.test.*

class ContentReadLedgerTest {
    @Test
    fun oneReadIncrementPerOwnerArticleAcrossConcurrentPageInstances() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; respond("") })
        try {
            val first = async { fixture.repositories.content.increaseHitsOnce("owner-a", "article") }
            val second = async { fixture.repositories.content.increaseHitsOnce("owner-a", "article") }
            assertEquals(setOf(true, false), setOf(assertIs<ApiResult.Success<Boolean>>(first.await()).data, assertIs<ApiResult.Success<Boolean>>(second.await()).data))
            assertEquals(1, calls)
            assertEquals(true, assertIs<ApiResult.Success<Boolean>>(fixture.repositories.content.increaseHitsOnce("owner-b", "article")).data)
            assertEquals(2, calls)
        } finally { fixture.close() }
    }

    @Test
    fun uncertainIncrementIsNotAutomaticallyReplayed() = runTest {
        var calls = 0
        val fixture = ApiTestFixture(MockEngine { calls++; throw IOException("fixture network interruption") })
        try {
            repeat(2) {
                assertIs<ApiError.Network>(assertIs<ApiResult.Failure>(fixture.repositories.content.increaseHitsOnce("owner", "article")).error)
            }
            assertEquals(1, calls)
        } finally { fixture.close() }
    }
}
