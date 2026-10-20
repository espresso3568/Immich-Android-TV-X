package nl.giejay.android.tv.immich.screensaver

import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import nl.giejay.android.tv.immich.api.loadAllAlbumAssets
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumAssetLoaderTest {
    @Test
    fun `reads short and empty pages when server still has a next page`() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val result = loadAllAlbumAssets(listOf("a", "b", "a")) { album, page ->
            calls += album to page
            Either.Right(when (album to page) {
                "a" to 1 -> AssetResponse(listOf(asset("shared")), true)
                "a" to 2 -> AssetResponse(emptyList(), true)
                "a" to 3 -> AssetResponse(listOf(asset("last")), false)
                "b" to 1 -> AssetResponse(listOf(asset("shared").copy(originalFileName = "changed")), false)
                else -> error("Unexpected page")
            })
        }
        assertEquals(listOf("a" to 1, "a" to 2, "a" to 3, "b" to 1), calls)
        assertEquals(listOf("shared", "last"), result.getOrNull()!!.map { it.id })
    }

    @Test
    fun `loads more than one thousand assets without truncating at the first page`() = runTest {
        val result = loadAllAlbumAssets(listOf("a")) { _, page ->
            Either.Right(if (page == 1) {
                AssetResponse((1..1000).map { asset(it.toString()) }, true)
            } else {
                assertEquals(2, page)
                AssetResponse(listOf(asset("1001")), false)
            })
        }
        assertEquals(1001, result.getOrNull()!!.size)
    }

    @Test
    fun `page failure returns error instead of treating a partial album as complete`() = runTest {
        val calls = mutableListOf<Pair<String, Int>>()
        val result = loadAllAlbumAssets(listOf("a", "b")) { album, page ->
            calls += album to page
            if (page == 1) Either.Right(AssetResponse(listOf(asset("first")), true))
            else Either.Left("offline")
        }
        assertEquals(Either.Left("offline"), result)
        assertEquals(listOf("a" to 1, "a" to 2), calls)
    }

    @Test
    fun `empty albums and empty selection finish without retries`() = runTest {
        val emptySelection = loadAllAlbumAssets(emptyList()) { _, _ -> error("Must not fetch") }
        val emptyAlbum = loadAllAlbumAssets(listOf("a")) { _, page ->
            assertEquals(1, page)
            Either.Right(AssetResponse(emptyList(), false))
        }
        assertTrue(emptySelection.getOrNull()!!.isEmpty())
        assertTrue(emptyAlbum.getOrNull()!!.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is propagated`() = runTest {
        loadAllAlbumAssets(listOf("a")) { _, _ -> throw CancellationException("left screensaver") }
    }

    @Test
    fun `a later start enumerates the source again from page one`() = runTest {
        val calls = mutableListOf<Int>()
        repeat(2) {
            val result = loadAllAlbumAssets(listOf("a")) { _, page ->
                calls += page
                Either.Right(AssetResponse(listOf(asset("photo")), false))
            }
            assertEquals(listOf("photo"), result.getOrNull()!!.map { it.id })
        }
        assertEquals(listOf(1, 1), calls)
    }

    private fun asset(id: String) = Asset(
        id = id, type = "IMAGE", deviceAssetId = null, exifInfo = null,
        fileModifiedAt = null, albumName = null, people = null, tags = null,
        originalPath = null, originalFileName = null
    )
}
