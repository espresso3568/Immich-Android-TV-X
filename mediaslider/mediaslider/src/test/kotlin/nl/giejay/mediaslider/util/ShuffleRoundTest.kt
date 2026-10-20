package nl.giejay.mediaslider.util

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShuffleRoundTest {
    @Test
    fun `every round contains each item once and leaves its input unchanged`() {
        val source = (1..1001).toList()
        val random = Random(42)
        var previous: Int? = null
        repeat(5) {
            val round = shuffledRound(source, previous, random = random)
            assertEquals(source.size, round.size)
            assertEquals(source.toSet(), round.toSet())
            if (previous != null) assertNotEquals(previous, round.first())
            previous = round.last()
        }
        assertEquals((1..1001).toList(), source)
    }

    @Test
    fun `reverse round avoids repeating at its last position`() {
        val random = Random(12)
        var previous = 1
        repeat(20) {
            val round = shuffledRound(listOf(1, 2, 3), previous, reverse = true, random = random)
            assertEquals(setOf(1, 2, 3), round.toSet())
            assertNotEquals(previous, round.last())
            previous = round.first()
        }
    }

    @Test
    fun `empty single and two item rounds terminate safely`() {
        assertTrue(shuffledRound(emptyList<Int>()).isEmpty())
        assertEquals(listOf(1), shuffledRound(listOf(1), previousItem = 1))
        repeat(20) { seed ->
            assertEquals(listOf(2, 1), shuffledRound(listOf(1, 2), previousItem = 1, random = Random(seed)))
        }
    }

    @Test
    fun `same seed reproduces a round without depending on earlier sessions`() {
        val source = (1..100).toList()
        assertEquals(shuffledRound(source, random = Random(5)), shuffledRound(source, random = Random(5)))
    }

    @Test
    fun `album playback starts at the selected photo and includes the remaining pages once`() {
        val source = (1..101).toList()
        repeat(10) { seed ->
            val round = shuffledStartingAt(source, 51, Random(seed))
            assertEquals(51, round.first())
            assertEquals(source.size, round.size)
            assertEquals(source.toSet(), round.toSet())
        }
        assertEquals((1..101).toList(), source)
    }

    @Test
    fun `missing selected page and empty albums still produce a complete round`() {
        assertEquals(setOf(1, 2), shuffledStartingAt(listOf(1, 2), 99, Random(2)).toSet())
        assertTrue(shuffledStartingAt(emptyList<Int>(), null).isEmpty())
    }

    @Test
    fun `ten thousand pages keep constant list size across rounds`() {
        val source = (1..10000).toList()
        var round = source
        repeat(3) { seed ->
            round = shuffledRound(round, round.last(), random = Random(seed))
            assertEquals(10000, round.size)
            assertEquals(source.toSet(), round.toSet())
        }
    }
}
