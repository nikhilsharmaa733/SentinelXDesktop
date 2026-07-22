package com.nikhil.sentinelx.desktop.core.audit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordGeneratorTest {

    @Test
    fun `honours the requested length`() {
        assertEquals(20, PasswordGenerator.generate().length)
        assertEquals(32, PasswordGenerator.generate(PasswordGenerator.Options(length = 32)).length)
        // Clamped rather than producing something trivially crackable.
        assertEquals(8, PasswordGenerator.generate(PasswordGenerator.Options(length = 2)).length)
    }

    @Test
    fun `always includes every requested character class`() {
        // Sampling uniformly can produce a 20-char password with no digit at all,
        // which then fails the very strength check that prompted enabling digits.
        repeat(200) {
            val pw = PasswordGenerator.generate(
                PasswordGenerator.Options(length = 12, upper = true, digits = true, symbols = true)
            )
            assertTrue(pw.any { it.isDigit() }, "no digit in $pw")
            assertTrue(pw.any { it.isUpperCase() }, "no uppercase in $pw")
            assertTrue(pw.any { !it.isLetterOrDigit() }, "no symbol in $pw")
        }
    }

    @Test
    fun `omits disabled classes entirely`() {
        repeat(100) {
            val pw = PasswordGenerator.generate(
                PasswordGenerator.Options(length = 16, upper = false, digits = false, symbols = false)
            )
            assertTrue(pw.all { it.isLowerCase() }, "unexpected character class in $pw")
        }
    }

    @Test
    fun `ambiguous characters are excluded by default`() {
        repeat(100) {
            val pw = PasswordGenerator.generate(PasswordGenerator.Options(length = 40))
            // l/1/I and 0/O are the pairs people misread when retyping by hand.
            assertTrue(pw.none { it in "l1IO0" }, "ambiguous character in $pw")
        }
    }

    @Test
    fun `generated passwords reach the top strength band`() {
        repeat(50) {
            assertEquals(Strength.FORTRESS, Strength.of(PasswordGenerator.generate()))
        }
    }

    @Test
    fun `output does not repeat`() {
        val seen = List(500) { PasswordGenerator.generate() }.toSet()
        assertEquals(500, seen.size, "generator produced a collision")
    }
}
