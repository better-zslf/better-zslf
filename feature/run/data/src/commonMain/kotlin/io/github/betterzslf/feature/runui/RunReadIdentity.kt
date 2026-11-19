package io.github.betterzslf.feature.runui

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Exact canonical equality stays private to one captured source, never a JSON-shaped public key. */
internal class RunTaskIdentityRegistry {
    private val mutex = Mutex()
    private val keys = linkedMapOf<String, String>()
    private var next = 0L
    private val recordKeys = linkedMapOf<String, String>()
    private var nextRecord = 0L

    suspend fun project(rows: List<RawRunTaskItem>, current: () -> Boolean): List<RunTaskItem>? {
        currentCoroutineContext().ensureActive()
        if (!current()) return null
        val projected = rows.map { it.toPublic("") }
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock null
            val additions = linkedMapOf<String, String>()
            var candidate = next
            val result = rows.mapIndexed { index, row ->
                val key = keys[row.identityKey] ?: additions.getOrPut(row.identityKey) { "task-row:${++candidate}" }
                projected[index].copy(identityKey = key)
            }
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock null
            keys.putAll(additions)
            next = candidate
            result
        }
    }

    suspend fun projectRecords(rows: List<RawRunRecordItem>, current: () -> Boolean): List<RunRecordItem>? {
        currentCoroutineContext().ensureActive()
        if (!current()) return null
        val identities = rows.map(::recordIdentity)
        val projected = rows.map { it.toPublic("") }
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock null
            val additions = linkedMapOf<String, String>()
            var candidate = nextRecord
            val result = projected.mapIndexed { index, item ->
                val key = recordKeys[identities[index]] ?: additions.getOrPut(identities[index]) { "record-row:${++candidate}" }
                item.copy(identityKey = key)
            }
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock null
            recordKeys.putAll(additions)
            nextRecord = candidate
            result
        }
    }
}

/** Original record-controller fingerprint, moved without changing tokens, traversal or arithmetic. */
internal fun recordIdentity(item: RawRunRecordItem): String = item.trid?.let { "trid:$it" } ?: rawKey(item.raw)

private sealed interface KeyPart {
    data class Value(val element: JsonElement) : KeyPart
    data class Field(val name: String) : KeyPart
}

/**
 * Retain only two fixed-size hashes, never an unbounded raw JSON string or truncated prefix.
 * All scalar characters participate; object field order is canonical and array order matters.
 * Iterative traversal also handles deeply nested raw extras without recursive hashing.
 * This local fingerprint is deliberately never assigned to RunRecordItem.trid or sent to a source.
 */
private fun rawKey(raw: JsonObject): String {
    var first = -3750763034362895579L
    var second = -7046029254386353131L
    fun feed(character: Char) {
        first = (first xor character.code.toLong()) * 1099511628211L
        second = (second xor character.code.toLong()) * 6364136223846793005L + 1442695040888963407L
    }
    fun token(text: String) {
        text.length.toString().forEach(::feed)
        feed(':')
        text.forEach(::feed)
    }
    val pending = ArrayDeque<KeyPart>()
    pending.addLast(KeyPart.Value(raw))
    while (pending.isNotEmpty()) {
        when (val part = pending.removeLast()) {
            is KeyPart.Field -> { token("key"); token(part.name) }
            is KeyPart.Value -> when (val value = part.element) {
                is JsonObject -> {
                    token("object"); token(value.size.toString())
                    value.keys.sortedDescending().forEach { key ->
                        pending.addLast(KeyPart.Value(value.getValue(key)))
                        pending.addLast(KeyPart.Field(key))
                    }
                }
                is JsonArray -> {
                    token("array"); token(value.size.toString())
                    for (index in value.lastIndex downTo 0) pending.addLast(KeyPart.Value(value[index]))
                }
                is JsonPrimitive -> { token(if (value.isString) "string" else "literal"); token(value.content) }
            }
        }
    }
    return "raw:${first.toULong().toString(16).padStart(16, '0')}:${second.toULong().toString(16).padStart(16, '0')}"
}
