package storage

class FileStorageManager(private val dir: String) : StorageManager {
    private val file get() = java.io.File(dir, "sessions.json")

    override fun save(data: String) {
        java.io.File(dir).mkdirs()
        file.writeText(data)
    }

    override fun load(): String? = try {
        if (file.exists()) file.readText() else null
    } catch (_: Exception) {
        null
    }

    override fun currentTimeMs(): Long = System.currentTimeMillis()
}
