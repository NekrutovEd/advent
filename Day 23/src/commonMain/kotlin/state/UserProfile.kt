package state

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

class UserProfile(
    val id: String,
    name: String,
    items: List<String> = emptyList(),
    isNameCustom: Boolean = false
) {
    var name by mutableStateOf(name)
    val items = mutableStateListOf<String>().also { it.addAll(items) }
    var isNameCustom by mutableStateOf(isNameCustom)

    fun toText(): String = items.joinToString("\n") { "- $it" }

    companion object {
        fun create(index: Int = 0): UserProfile = UserProfile(
            id = generateId(),
            name = generateAutoName(emptyList(), index)
        )

        fun generateAutoName(items: List<String>, index: Int): String {
            return "Profile ${index + 1}"
        }

        private fun generateId(): String = Random.Default.nextBytes(4).joinToString("") {
            val v = it.toInt() and 0xFF
            val h = v.toString(16)
            if (h.length == 1) "0$h" else h
        }
    }
}
