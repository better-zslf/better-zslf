package io.github.betterzslf.feature.run

import io.github.betterzslf.core.text.AppText

sealed interface DetourResult {
    data class Success(
        val points: List<GeoPoint>,
        val distanceMeters: Double,
        val remainingMeters: Double,
        val turnaroundPoint: GeoPoint?,
        val visitedCheckpointIds: List<String>,
    ) : DetourResult
    data class Failure(val reason: String) : DetourResult
}

/** Offline, bounded road-graph detours. No external planner or straight-line teleport fallback. */
object RunDetourPlanner {
    const val MAX_JOIN_METERS = 45.0
    const val END_RESERVE_METERS = 50.0
    const val MIN_LEG_METERS = 20.0
    const val FALLBACK_MIN_BACK_METERS = 100.0
    const val PROBE_LIMIT = 6
    const val MAX_ROUNDS = 12
    private const val MAX_NODES = 20_000

    /**
     * The base path must already be dense (use RunRouteNormalizer explicitly after import).
     * Each walkPaths segment represents a continuous road. Only joins <=45m are connected.
     * Success may leave a residual <40m if another legal 20m outward/return leg will not fit;
     * callers must inspect remainingMeters and re-run their minimum-distance validation.
     */
    fun extend(
        points: List<GeoPoint>,
        walkPaths: List<List<GeoPoint>>,
        checkpoints: List<RunCheckpoint>,
        targetDistanceMeters: Double,
    ): DetourResult {
        if (!targetDistanceMeters.isFinite() || targetDistanceMeters <= 0) return DetourResult.Failure(AppText.Run.TARGET_DISTANCE_MUST_BE_POSITIVE)
        if (points.size < 2 || points.size > RunRouteParser.MAX_POINTS) return DetourResult.Failure(AppText.Run.INVALID_BASE_ROUTE_POINT_COUNT)
        if (points.zipWithNext().any { (a, b) -> RunGeo.distanceMeters(a, b) > MAX_JOIN_METERS + 1e-6 }) return DetourResult.Failure(AppText.Run.BASE_ROUTE_JUMP_EXCEEDS_LIMIT_INSPECT_AND_INTERPOLATE)
        val cumulative = RunGeo.cumulative(points)
        val originalLength = cumulative.last()
        if (originalLength > targetDistanceMeters + 1e-6) return DetourResult.Failure(AppText.Run.TARGET_SHORTER_THAN_BASE_ROUTE_NO_EXTENSION_ROOM)
        if (targetDistanceMeters - originalLength < 1e-6) return DetourResult.Success(points.toList(), originalLength, 0.0, null, emptyList())
        if (originalLength <= END_RESERVE_METERS + MIN_LEG_METERS) return DetourResult.Failure(AppText.Run.BASE_ROUTE_TOO_SHORT_FOR_END_RESERVED_TURNAROUND)
        if (walkPaths.isEmpty()) return DetourResult.Failure(AppText.Run.TASK_RECOMMENDED_PATH_NOT_PROVIDED)
        return try {
            val graph = RoadGraph(walkPaths)
            val pivotDistance = originalLength - END_RESERVE_METERS
            val pivot = RunGeo.pointAt(points, cumulative, pivotDistance)
            val source = graph.attach(pivot) ?: return DetourResult.Failure(AppText.Run.TURNAROUND_RECOMMENDED_PATH_JOIN_GAP_EXCEEDED)
            val candidates = checkpoints.dropLast(1).take(256).mapNotNull { checkpoint ->
                val vertex = graph.attach(checkpoint.point)
                if (vertex == null) null else checkpoint.id to vertex
            }
            val shortest = graph.shortest(source)
            val targets = candidates.mapNotNull { (id, node) ->
                val length = shortest.distances[node]
                if (!length.isFinite() || length < MIN_LEG_METERS) null else Target(id, graph.path(shortest, node), length)
            }.sortedByDescending { it.length }
            val fallbackNode = shortest.distances.indices.filter { shortest.distances[it].isFinite() }.maxByOrNull { shortest.distances[it] }
            val fallback = fallbackNode?.let { Target(null, graph.path(shortest, it), shortest.distances[it]) }
            val prefix = points.indices.filter { cumulative[it] < pivotDistance - 1e-7 }.map { points[it] }.toMutableList()
            append(prefix, listOf(pivot))
            val suffix = mutableListOf(pivot).apply { addAll(points.indices.filter { cumulative[it] > pivotDistance + 1e-7 }.map { points[it] }) }
            var budget = targetDistanceMeters - originalLength
            var lastId: String? = null
            val visited = mutableListOf<String>()
            for (round in 0 until MAX_ROUNDS) {
                if (budget < 2 * MIN_LEG_METERS - 1e-6) break
                val fitting = targets.asSequence().filter { it.id != lastId }.take(PROBE_LIMIT)
                    .firstOrNull { 2 * it.length <= budget + 1e-7 }
                val leg = if (fitting != null) fitting else {
                    // Prefer a partial path toward a different checkpoint, then a sufficiently long road backtrack.
                    val toward = targets.firstOrNull { it.id != lastId && it.length >= budget / 2 }
                    val available = toward ?: fallback?.takeIf { it.length >= FALLBACK_MIN_BACK_METERS }
                    if (available == null) break
                    val distance = minOf(available.length, budget / 2)
                    if (distance < MIN_LEG_METERS) break
                    Target(null, trim(available.points, distance), distance)
                }
                if (leg.points.size < 2 || leg.length < MIN_LEG_METERS) break
                append(prefix, leg.points)
                append(prefix, leg.points.asReversed())
                budget = (budget - 2 * leg.length).coerceAtLeast(0.0)
                if (leg.id != null) {
                    visited += leg.id
                    lastId = leg.id
                }
                if (prefix.size + suffix.size > RunRouteParser.MAX_POINTS) return DetourResult.Failure(AppText.Run.EXTENDED_ROUTE_POINT_PROCESSING_LIMIT_EXCEEDED)
            }
            if (budget >= targetDistanceMeters - originalLength - 1e-6) return DetourResult.Failure(AppText.Run.NO_REACHABLE_ROAD_MEETS_MINIMUM_RETURN_DISTANCE)
            append(prefix, suffix)
            val length = RunGeo.pathLengthMeters(prefix)
            if (length > targetDistanceMeters + 1e-5) return DetourResult.Failure(AppText.Run.EXTENSION_RESULT_EXCEEDS_TARGET_DISTANCE)
            if (prefix.zipWithNext().any { (a, b) -> RunGeo.distanceMeters(a, b) > MAX_JOIN_METERS + 1e-6 }) return DetourResult.Failure(AppText.Run.EXTENSION_RESULT_JOIN_GAP_LIMIT_EXCEEDED)
            DetourResult.Success(prefix, length, (targetDistanceMeters - length).coerceAtLeast(0.0), pivot, visited)
        } catch (error: IllegalArgumentException) {
            DetourResult.Failure(error.message ?: AppText.Run.INVALID_RECOMMENDED_PATH_DATA)
        }
    }

    /** Ordered rerouting via the same recommended-road graph; no invented road is introduced. */
    fun throughCheckpoints(start: GeoPoint, end: GeoPoint, walkPaths: List<List<GeoPoint>>, checkpoints: List<RunCheckpoint>): DetourResult {
        return try {
            val graph = RoadGraph(walkPaths)
            val waypoints = listOf(start) + checkpoints.map { it.point } + end
            val nodes = waypoints.map { graph.attach(it) ?: return DetourResult.Failure(AppText.Run.CHECKPOINT_RECOMMENDED_PATH_GAP_LIMIT_EXCEEDED) }
            val result = mutableListOf(start)
            for (i in 1 until nodes.size) {
                val shortest = graph.shortest(nodes[i - 1])
                if (!shortest.distances[nodes[i]].isFinite()) return DetourResult.Failure(AppText.Run.RECOMMENDED_PATH_SEGMENTS_DISCONNECTED)
                append(result, graph.path(shortest, nodes[i]))
                if (result.size > RunRouteParser.MAX_POINTS) return DetourResult.Failure(AppText.Run.REROUTED_POINT_COUNT_LIMIT_EXCEEDED)
            }
            DetourResult.Success(result, RunGeo.pathLengthMeters(result), 0.0, null, checkpoints.map { it.id })
        } catch (error: IllegalArgumentException) {
            DetourResult.Failure(error.message ?: AppText.Run.INVALID_RECOMMENDED_PATH_DATA)
        }
    }

    private data class Target(val id: String?, val points: List<GeoPoint>, val length: Double)
    private fun trim(points: List<GeoPoint>, distance: Double): List<GeoPoint> {
        val cumulative = RunGeo.cumulative(points)
        return points.indices.filter { cumulative[it] < distance - 1e-7 }.map { points[it] } + RunGeo.pointAt(points, cumulative, distance)
    }
    private fun append(destination: MutableList<GeoPoint>, source: List<GeoPoint>) {
        for (point in source) if (destination.isEmpty() || RunGeo.distanceMeters(destination.last(), point) > 1e-7) destination += point
    }

    private class RoadGraph(paths: List<List<GeoPoint>>) {
        val points = mutableListOf<GeoPoint>()
        private val links = mutableListOf<MutableMap<Int, Double>>()
        private val known = mutableMapOf<Pair<Double, Double>, Int>()
        private val roadEdges = mutableListOf<Pair<Int, Int>>()

        init {
            require(paths.size <= 256) { AppText.Run.TOO_MANY_RECOMMENDED_PATH_SEGMENTS }
            val endpoints = mutableListOf<Int>()
            for (path in paths.filter { it.isNotEmpty() }) {
                val dense = RunRouteNormalizer.normalize(path)
                val ids = dense.map { node(it) }
                endpoints += ids.first()
                endpoints += ids.last()
                for (i in 1 until ids.size) {
                    if (ids[i - 1] != ids[i]) {
                        edge(ids[i - 1], ids[i])
                        roadEdges += ids[i - 1] to ids[i]
                    }
                }
            }
            require(roadEdges.isNotEmpty()) { AppText.Run.RECOMMENDED_PATH_HAS_NO_WALKABLE_SEGMENT }
            for (i in endpoints.indices) for (j in 0 until i) {
                if (endpoints[i] != endpoints[j] && RunGeo.distanceMeters(points[endpoints[i]], points[endpoints[j]]) <= MAX_JOIN_METERS) {
                    edge(endpoints[i], endpoints[j])
                    roadEdges += endpoints[i] to endpoints[j]
                }
            }
        }

        private fun node(point: GeoPoint): Int {
            val key = point.longitude to point.latitude
            return known.getOrPut(key) {
                require(points.size < MAX_NODES) { AppText.Run.RECOMMENDED_PATH_GRAPH_PROCESSING_LIMIT_EXCEEDED }
                points.add(point)
                links.add(mutableMapOf())
                points.lastIndex
            }
        }
        private fun edge(a: Int, b: Int) {
            if (a == b) return
            val distance = RunGeo.distanceMeters(points[a], points[b])
            require(distance <= MAX_JOIN_METERS + 1e-6) { AppText.Run.RECOMMENDED_PATH_JUMP_LIMIT_EXCEEDED }
            links[a][b] = distance
            links[b][a] = distance
        }

        fun attach(point: GeoPoint): Int? {
            var best: Pair<Int, Int>? = null
            var projection: GeoPoint? = null
            var gap = Double.POSITIVE_INFINITY
            for ((a, b) in roadEdges) {
                val f = RunGeo.projectionFraction(point, points[a], points[b])
                val onRoad = RunGeo.interpolate(points[a], points[b], f)
                val distance = RunGeo.distanceMeters(point, onRoad)
                if (distance < gap) { gap = distance; best = a to b; projection = onRoad }
            }
            if (gap > MAX_JOIN_METERS + 1e-7 || best == null || projection == null) return null
            val onRoadId = node(projection)
            edge(onRoadId, best.first)
            edge(onRoadId, best.second)
            // Splitting edges is important: later attachments on this same road must have direct connectivity.
            if (onRoadId != best.first && onRoadId != best.second) {
                links[best.first].remove(best.second)
                links[best.second].remove(best.first)
                roadEdges.remove(best)
                roadEdges += best.first to onRoadId
                roadEdges += onRoadId to best.second
            }
            val id = node(point)
            edge(id, onRoadId)
            return id
        }

        data class Shortest(val distances: DoubleArray, val parents: IntArray, val source: Int)
        fun shortest(source: Int): Shortest {
            val distances = DoubleArray(points.size) { Double.POSITIVE_INFINITY }
            val parents = IntArray(points.size) { -1 }
            val heap = MinHeap()
            distances[source] = 0.0
            heap.add(source, 0.0)
            while (heap.isNotEmpty()) {
                val (node, distance) = heap.remove()
                if (distance > distances[node]) continue
                for ((next, length) in links[node]) {
                    val candidate = distance + length
                    if (candidate + 1e-9 < distances[next]) {
                        distances[next] = candidate
                        parents[next] = node
                        heap.add(next, candidate)
                    }
                }
            }
            return Shortest(distances, parents, source)
        }
        fun path(shortest: Shortest, target: Int): List<GeoPoint> {
            require(shortest.distances[target].isFinite())
            val result = mutableListOf<GeoPoint>()
            var cursor = target
            while (cursor >= 0) {
                result += points[cursor]
                if (cursor == shortest.source) break
                cursor = shortest.parents[cursor]
            }
            return result.asReversed()
        }
    }

    private class MinHeap {
        private val values = mutableListOf<Pair<Int, Double>>()
        fun isNotEmpty(): Boolean = values.isNotEmpty()
        fun add(node: Int, distance: Double) {
            values += node to distance
            var i = values.lastIndex
            while (i > 0) {
                val parent = (i - 1) / 2
                if (values[parent].second <= values[i].second) break
                val old = values[parent]; values[parent] = values[i]; values[i] = old
                i = parent
            }
        }
        fun remove(): Pair<Int, Double> {
            val result = values.first()
            val last = values.removeAt(values.lastIndex)
            if (values.isEmpty()) return result
            values[0] = last
            var i = 0
            while (2 * i + 1 < values.size) {
                var child = 2 * i + 1
                if (child + 1 < values.size && values[child + 1].second < values[child].second) child++
                if (values[i].second <= values[child].second) break
                val old = values[i]; values[i] = values[child]; values[child] = old
                i = child
            }
            return result
        }
    }
}
