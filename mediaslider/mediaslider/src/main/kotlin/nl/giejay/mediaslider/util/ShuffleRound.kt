package nl.giejay.mediaslider.util

import kotlin.random.Random

/** A permutation, not repeated sampling. Neither the input nor playback history is persisted. */
fun <T> shuffledRound(
    items: List<T>,
    previousItem: T? = null,
    reverse: Boolean = false,
    random: Random = Random.Default
): List<T> {
    val result = items.toMutableList()
    result.shuffle(random)
    if (result.size > 1 && previousItem != null) {
        val start = if (reverse) result.lastIndex else 0
        if (result[start] == previousItem) {
            val replacement = result.indices.firstOrNull { result[it] != previousItem }
            if (replacement != null) {
                val first = result[start]
                result[start] = result[replacement]
                result[replacement] = first
            }
        }
    }
    return result
}

/** Keep the photo the user opened as the first page, with all other pages randomized. */
fun <T> shuffledStartingAt(items: List<T>, firstItem: T?, random: Random = Random.Default): List<T> {
    val result = shuffledRound(items, random = random).toMutableList()
    val index = result.indexOf(firstItem)
    if (index > 0) {
        val first = result[0]
        result[0] = result[index]
        result[index] = first
    }
    return result
}
