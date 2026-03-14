package state

import kotlin.random.Random

data class MemoryItem(
    val id: String,
    val content: String,
    val source: MemorySource,
    val timestamp: Long
)

enum class MemorySource { AUTO_EXTRACTED, MANUAL, PROMOTED }

data class InvariantItem(
    val id: String,
    val content: String,
    val timestamp: Long
)

fun buildMemoryId(): String = Random.Default.nextBytes(4).joinToString("") {
    val v = it.toInt() and 0xFF
    val h = v.toString(16)
    if (h.length == 1) "0$h" else h
}
