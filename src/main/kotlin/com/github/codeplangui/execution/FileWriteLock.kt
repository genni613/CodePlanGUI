package com.github.codeplangui.execution

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * File-level write lock for serializing concurrent writes to the same file.
 * Prevents data races when multiple tool calls target the same path.
 */
class FileWriteLock {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withFileLock(path: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(path) { Mutex() }
        return mutex.withLock {
            block()
        }
        // Do NOT remove mutex from map after release — another coroutine
        // may be waiting, and removal + computeIfAbsent creates a new Mutex,
        // breaking serialization semantics.
    }

    fun clear() {
        locks.clear()
    }
}
