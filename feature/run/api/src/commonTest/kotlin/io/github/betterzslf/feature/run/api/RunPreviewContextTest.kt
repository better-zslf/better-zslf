package io.github.betterzslf.feature.run.api

import io.github.betterzslf.feature.run.GeoPoint
import io.github.betterzslf.feature.run.RunCheckpoint
import kotlin.test.*

class RunPreviewContextTest {
    private val point = GeoPoint(113.4, 22.5)

    @Test fun taskProjectionOwnsItsCollections() {
        val path = mutableListOf(point)
        val checkpoints = mutableListOf(RunCheckpoint("checkpoint", point))
        val source = mutableListOf<List<GeoPoint>>(path)
        val context = RunPreviewContext("Task", checkpoints, source, 100.0, emptyList())
        path.clear(); source.clear(); checkpoints.clear()
        assertEquals(listOf(point), context.walkPaths.single())
        assertEquals(1, context.checkpoints.size)
        assertFailsWith<Exception> { (context.walkPaths as MutableList<List<GeoPoint>>).clear() }
        assertFailsWith<Exception> { (context.walkPaths.single() as MutableList<GeoPoint>).clear() }
    }

    @Test fun invalidLimitsFailLocally() {
        assertFailsWith<IllegalArgumentException> { RunPreviewContext("Task", emptyList(), emptyList(), Double.NaN, emptyList()) }
        assertFailsWith<IllegalArgumentException> { RunPreviewSelection("", "schedule") }
        assertFailsWith<IllegalArgumentException> { RunPreviewSelection("task", "invalid\n") }
    }
}
