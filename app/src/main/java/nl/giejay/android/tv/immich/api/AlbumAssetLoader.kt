package nl.giejay.android.tv.immich.api

import arrow.core.Either
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetResponse

/** Collects a finite album union before playback. Requests are deliberately sequential. */
internal suspend fun loadAllAlbumAssets(
    albumIds: List<String>,
    fetchPage: suspend (albumId: String, page: Int) -> Either<String, AssetResponse>
): Either<String, List<Asset>> {
    val assetsById = linkedMapOf<String, Asset>()
    for (albumId in albumIds.distinct()) {
        var page = 1
        do {
            currentCoroutineContext().ensureActive()
            val result = fetchPage(albumId, page)
            // ApiClient may turn a cancelled network call into Left; preserve cancellation.
            currentCoroutineContext().ensureActive()
            val response = when (result) {
                is Either.Left -> return result
                is Either.Right -> result.value
            }
            response.assets.forEach { assetsById.putIfAbsent(it.id, it) }
            page += 1
        } while (response.canLoadMore)
    }
    return Either.Right(assetsById.values.toList())
}
