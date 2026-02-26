package storage

interface StorageManager {
    fun save(data: String)
    fun load(): String?
    fun currentTimeMs(): Long = 0L
}

object NoOpStorage : StorageManager {
    override fun save(data: String) = Unit
    override fun load(): String? = null
}
