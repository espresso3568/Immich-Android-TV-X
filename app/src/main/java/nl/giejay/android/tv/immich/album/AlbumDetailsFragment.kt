package nl.giejay.android.tv.immich.album

import androidx.navigation.fragment.findNavController
import arrow.core.Either
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.loadAllAlbumAssets
import nl.giejay.android.tv.immich.api.MAX_SEARCH_PAGE_SIZE
import nl.giejay.android.tv.immich.assets.GenericAssetFragment
import nl.giejay.android.tv.immich.card.Card
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.EnumByTitlePref
import nl.giejay.android.tv.immich.shared.prefs.FILTER_CONTENT_TYPE_FOR_SPECIFIC_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.PHOTOS_SORTING_FOR_SPECIFIC_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.PhotosOrder
import nl.giejay.android.tv.immich.shared.prefs.ALBUM_SHUFFLE
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager


class AlbumDetailsFragment : GenericAssetFragment() {
    lateinit var albumId: String
    lateinit var albumName: String

    override fun getFilterKey(): EnumByTitlePref<ContentType> {
        albumId = AlbumDetailsFragmentArgs.fromBundle(requireArguments()).albumId
        albumName = AlbumDetailsFragmentArgs.fromBundle(requireArguments()).albumName
        return FILTER_CONTENT_TYPE_FOR_SPECIFIC_ALBUM(albumId, albumName)
    }

    override fun getSortingKey(): EnumByTitlePref<PhotosOrder> {
        albumId = AlbumDetailsFragmentArgs.fromBundle(requireArguments()).albumId
        albumName = AlbumDetailsFragmentArgs.fromBundle(requireArguments()).albumName
        return PHOTOS_SORTING_FOR_SPECIFIC_ALBUM(albumId, albumName)
    }

    override suspend fun loadItems(apiClient: ApiClient, page: Int, pageCount: Int): Either<String, List<Asset>> {
        return apiClient.listAssets(
            page = page,
            pageCount = pageCount,
            order = if (currentSort == PhotosOrder.NEWEST_OLDEST) "desc" else "asc",
            contentType = currentFilter,
            albumIds = listOf(albumId)
        ).map { it.assets.map { a -> a.copy(albumName = albumName) } }
    }

    override fun onItemSelected(card: Card, indexOf: Int) {
        // no use case yet
    }

    override fun shouldShufflePlayback(): Boolean = PreferenceManager.get(ALBUM_SHUFFLE(albumId))

    override suspend fun loadPlaybackAssets(): Either<String, List<Asset>> =
        loadAllAlbumAssets(listOf(albumId)) { id, page ->
            apiClient.listAssets(
                page = page,
                pageCount = MAX_SEARCH_PAGE_SIZE,
                order = if (currentSort == PhotosOrder.NEWEST_OLDEST) "desc" else "asc",
                contentType = currentFilter,
                albumIds = listOf(id)
            ).map { response -> response.copy(assets = response.assets.map { it.copy(albumName = albumName) }) }
        }

    override fun openPopUpMenu() {
        findNavController().navigate(
            HomeFragmentDirections.actionGlobalToSettingsDialog("album_details", albumId, albumName)
        )
    }

    override fun setTitle(response: List<Asset>) {
        title = albumName
    }
}
