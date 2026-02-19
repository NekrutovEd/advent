package com.remoteclaude.server.state

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class TabBuffer(private val maxChars: Int = 200_000) {
    private val lock = ReentrantReadWriteLock()
    private val buffer = StringBuilder()

    fun append(data: String) = lock.write {
        buffer.append(data)
        if (buffer.length > maxChars) {
            buffer.delete(0, buffer.length - maxChars)
        }
    }

    fun getSnapshot(): String = lock.read { buffer.toString() }

    fun clear() = lock.write { buffer.clear() }
}
