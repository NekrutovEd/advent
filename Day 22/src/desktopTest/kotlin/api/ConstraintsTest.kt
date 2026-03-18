package api

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConstraintsTest {

    private fun applyConstraints(prompt: String, constraints: String): String {
        return if (constraints.isBlank()) prompt else "$prompt\n\n$constraints"
    }

    @Test
    fun `empty constraints returns original prompt`() {
        assertEquals("Hello", applyConstraints("Hello", ""))
    }

    @Test
    fun `blank constraints returns original prompt`() {
        assertEquals("Hello", applyConstraints("Hello", "   "))
    }

    @Test
    fun `constraints are appended with double newline`() {
        val result = applyConstraints("Write a poem", "Use only 4 lines")
        assertEquals("Write a poem\n\nUse only 4 lines", result)
    }

    @Test
    fun `multiple constraints appended correctly`() {
        val result = applyConstraints("Tell a joke", "Keep it short\nMake it funny")
        assertEquals("Tell a joke\n\nKeep it short\nMake it funny", result)
    }
}
