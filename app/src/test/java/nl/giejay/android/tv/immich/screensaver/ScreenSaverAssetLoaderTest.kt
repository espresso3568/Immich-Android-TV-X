package nl.giejay.android.tv.immich.screensaver

import android.content.Context
import android.content.SharedPreferences
import arrow.core.Either
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.MAX_SEARCH_PAGE_SIZE
import nl.giejay.android.tv.immich.api.model.Asset
import nl.giejay.android.tv.immich.api.model.AssetResponse
import nl.giejay.android.tv.immich.api.model.AssetExifInfo
import nl.giejay.android.tv.immich.api.model.Tag
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.ContentType
import nl.giejay.android.tv.immich.shared.prefs.EXCLUDE_ASSETS_IN_ALBUM
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.Pref
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ALBUMS
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_TYPE
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_INCLUDE_VIDEOS
import nl.giejay.android.tv.immich.shared.prefs.SLIDER_MERGE_PORTRAIT_PHOTOS
import nl.giejay.android.tv.immich.slider.FavoriteService
import nl.giejay.android.tv.immich.shared.util.toSliderItems
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.plugin.MediaRemoteControlsKeyEventPlugin
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenSaverAssetLoaderTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val preferenceValues = mutableMapOf<String, Any?>()

    private lateinit var appContext: Context
    private lateinit var sharedPreferences: SharedPreferences

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)

        appContext = mock()
        whenever(appContext.getString(any())).thenAnswer { "res-${it.getArgument<Int>(0)}" }
        ImmichApplication.appContext = appContext

        sharedPreferences = mock()
        whenever(sharedPreferences.getString(any(), anyOrNull())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] as? String ?: it.getArgument(1)
        }
        whenever(sharedPreferences.getBoolean(any(), any())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] as? Boolean ?: it.getArgument(1)
        }
        whenever(sharedPreferences.getInt(any(), any())).thenAnswer {
            preferenceValues[it.getArgument<String>(0)] as? Int ?: it.getArgument(1)
        }
        whenever(sharedPreferences.getStringSet(any(), anyOrNull())).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (preferenceValues[it.getArgument<String>(0)] as? Set<String> ?: it.getArgument(1)) as Set<String>?
        }
        whenever(sharedPreferences.contains(any())).thenAnswer {
            preferenceValues.containsKey(it.getArgument<String>(0))
        }
        whenever(sharedPreferences.all).thenAnswer {
            preferenceValues.filterValues { it != null }
        }

        val sharedPreferenceField = PreferenceManager.javaClass.getDeclaredField("sharedPreference")
        sharedPreferenceField.isAccessible = true
        sharedPreferenceField.set(PreferenceManager, sharedPreferences)

        @Suppress("UNCHECKED_CAST")
        val liveContext = PreferenceManager.javaClass.getDeclaredField("liveContext").apply {
            isAccessible = true
        }.get(PreferenceManager) as MutableMap<String, Any?>
        liveContext.clear()
        preferenceValues.clear()
    }

    @After
    fun tearDown() {
        @Suppress("UNCHECKED_CAST")
        val liveContext = PreferenceManager.javaClass.getDeclaredField("liveContext").apply {
            isAccessible = true
        }.get(PreferenceManager) as MutableMap<String, Any?>
        liveContext.clear()
        preferenceValues.clear()
        ImmichApplication.appContext = null
        Dispatchers.resetMain()
    }

    @Test
    fun `album screensaver loads all pages and overlapping albums once without random sampling`() = runLoaderTest(
        type = ScreenSaverType.ALBUMS,
        albums = linkedSetOf("shared-album", "second-album"),
        setupMocks = { apiClient ->
            setPreference(SLIDER_MERGE_PORTRAIT_PHOTOS, false)
            whenever(
                apiClient.listAssets(
                    page = any(),
                    pageCount = eq(MAX_SEARCH_PAGE_SIZE),
                    random = eq(false),
                    order = any(),
                    personIds = any(),
                    fromDate = anyOrNull(),
                    endDate = anyOrNull(),
                    contentType = eq(ContentType.IMAGE),
                    albumIds = any()
                )
            ).thenAnswer { call ->
                when (call.getArgument<List<String>>(8).single() to call.getArgument<Int>(0)) {
                    "shared-album" to 1 -> Either.Right(AssetResponse(listOf(asset("a"), asset("b")), true))
                    "shared-album" to 2 -> Either.Right(AssetResponse(listOf(asset("c")), false))
                    "second-album" to 1 -> Either.Right(AssetResponse(listOf(asset("b"), asset("d")), false))
                    else -> error("Unexpected album page")
                }
            }
        }
    ) { actual, apiClient ->
        assertThat(actual.items.flatMap { it.ids() }).containsExactly("a", "b", "c", "d")
        assertThat(actual.shuffleOnRepeat).isTrue()
        assertThat(actual.loadMore).isNull()
        verify(apiClient, never()).listAssets(
            any(), any(), eq(true), any(), any(), anyOrNull(), anyOrNull(), any(), any()
        )
    }

    @Test
    fun `album screensaver keeps enabled videos and filters excluded ids and tags`() = runLoaderTest(
        type = ScreenSaverType.ALBUMS,
        albums = setOf("mixed-album"),
        excludedAlbums = setOf("excluded-album"),
        setupMocks = { apiClient ->
            setPreference(SCREENSAVER_INCLUDE_VIDEOS, true)
            val tagged = asset("tagged").copy(tags = listOf(Tag(null, java.util.Date(), "exclude_immich_tv", "")))
            whenever(apiClient.listAssetsFromAlbum(any(), any(), any())).thenReturn(
                Either.Right(AssetResponse(listOf(asset("excluded")), false))
            )
            whenever(apiClient.listAssets(
                any(), any(), eq(false), any(), any(), anyOrNull(), anyOrNull(), eq(ContentType.ALL), any()
            )).thenReturn(Either.Right(AssetResponse(
                listOf(asset("image"), asset("video").copy(type = "VIDEO"), asset("excluded"), tagged), false
            )))
        }
    ) { actual, _ ->
        assertThat(actual.items.flatMap { it.ids() }).containsExactly("image", "video")
        assertThat(actual.shuffleOnRepeat).isTrue()
        assertThat(actual.loadMore).isNull()
    }

    @Test
    fun `portrait pairing keeps every unique asset exactly once`() = runLoaderTest(
        type = ScreenSaverType.ALBUMS,
        albums = setOf("portrait-album"),
        setupMocks = { apiClient ->
            setPreference(SLIDER_MERGE_PORTRAIT_PHOTOS, true)
            val portrait = AssetExifInfo(null, 6, 100, 200, null, null, null, null, null)
            val photos = listOf(
                asset("a").copy(exifInfo = portrait), asset("b").copy(exifInfo = portrait),
                asset("c").copy(exifInfo = portrait), asset("wide")
            )
            // Also exercise the linear, order-preserving pairing path used outside screensavers.
            val ordered = photos.toSliderItems(keepOrder = true, mergePortrait = true)
            assertThat(ordered.flatMap { it.ids() }).containsExactly("a", "b", "c", "wide").inOrder()
            assertThat(ordered.first().ids()).containsExactly("a", "b").inOrder()
            whenever(apiClient.listAssets(
                any(), any(), eq(false), any(), any(), anyOrNull(), anyOrNull(), any(), any()
            )).thenReturn(Either.Right(AssetResponse(photos + photos.first(), false)))
        }
    ) { actual, _ ->
        assertThat(actual.items.flatMap { it.ids() }).containsExactly("a", "b", "c", "wide")
    }

    @Test
    fun `album page failure exits instead of playing a partial selection`() = runLoaderTest(
        type = ScreenSaverType.ALBUMS,
        albums = setOf("album"),
        expectedMessage = R.string.could_not_load_assets,
        expectExit = true,
        setupMocks = { apiClient ->
            whenever(apiClient.listAssets(
                any(), any(), eq(false), any(), any(), anyOrNull(), anyOrNull(), any(), any()
            )).thenAnswer { call ->
                if (call.getArgument<Int>(0) == 1) Either.Right(AssetResponse(listOf(asset("first")), true))
                else Either.Left("offline")
            }
        }
    )

    @Test
    fun `fully excluded album reports no assets without retrying forever`() = runLoaderTest(
        type = ScreenSaverType.ALBUMS,
        albums = setOf("album"),
        expectedMessage = R.string.no_assets_for_screensaver,
        expectedLongDuration = true,
        setupMocks = { apiClient ->
            val tagged = asset("excluded").copy(tags = listOf(Tag(null, java.util.Date(), "exclude_immich_tv", "")))
            whenever(apiClient.listAssets(
                any(), any(), eq(false), any(), any(), anyOrNull(), anyOrNull(), any(), any()
            )).thenReturn(Either.Right(AssetResponse(listOf(tagged), false)))
        }
    )

    @Test
    fun `missing album selection reports configuration error`() = runLoaderTest(
        type = ScreenSaverType.ALBUMS,
        expectedMessage = R.string.set_albums_screensaver_error,
        expectExit = true,
        setupMocks = {}
    )

    @Test
    fun `loads random assets when type is RANDOM`() = runLoaderTest(
        type = ScreenSaverType.RANDOM,
        setupMocks = { apiClient ->
            whenever(
                apiClient.listAssets(
                    page = any(),
                    pageCount = any(),
                    random = any(),
                    order = any(),
                    personIds = any(),
                    fromDate = anyOrNull(),
                    endDate = anyOrNull(),
                    contentType = any(),
                    albumIds = any()
                )
            ).thenReturn(Either.Right(AssetResponse(listOf(asset("random-asset")), true)))
        }
    ) { actual, _ ->
        assertThat(actual.items.single().ids()).containsExactly("random-asset")
        assertThat(actual.shuffleOnRepeat).isFalse()
        assertThat(actual.loadMore).isNotNull()
    }

    @Test
    fun `loads recent assets when type is RECENT`() = runLoaderTest(
        type = ScreenSaverType.RECENT,
        setupMocks = { apiClient ->
            whenever(
                apiClient.recentAssets(
                    page = any(),
                    pageCount = any(),
                    contentType = any()
                )
            ).thenReturn(Either.Right(AssetResponse(listOf(asset("recent-asset")), true)))
        }
    ) { actual, _ ->
        assertThat(actual.items.single().ids()).containsExactly("recent-asset")
    }

    @Test
    fun `loads similar assets when type is SIMILAR_TIME_PERIOD`() = runLoaderTest(
        type = ScreenSaverType.SIMILAR_TIME_PERIOD,
        setupMocks = { apiClient ->
            whenever(
                apiClient.similarAssets(
                    page = any(),
                    pageCount = any(),
                    contentType = any()
                )
            ).thenReturn(Either.Right(AssetResponse(listOf(asset("similar-asset")), true)))
        }
    ) { actual, _ ->
        assertThat(actual.items.single().ids()).containsExactly("similar-asset")
    }

    @Test
    fun `filters out assets from excluded albums`() = runLoaderTest(
        type = ScreenSaverType.RANDOM,
        excludedAlbums = setOf("excluded-album"),
        setupMocks = { apiClient ->
            whenever(
                apiClient.listAssetsFromAlbum(
                    albumIds = eq(listOf("excluded-album")),
                    contentType = any(),
                    pageCount = any()
                )
            ).thenReturn(Either.Right(AssetResponse(listOf(asset("excluded-asset")), false)))

            whenever(
                apiClient.listAssets(
                    page = any(),
                    pageCount = any(),
                    random = any(),
                    order = any(),
                    personIds = any(),
                    fromDate = anyOrNull(),
                    endDate = anyOrNull(),
                    contentType = any(),
                    albumIds = any()
                )
            ).thenReturn(Either.Right(AssetResponse(listOf(asset("random-asset"), asset("excluded-asset")), true)))
        }
    ) { actual, apiClient ->
        // Immich rejects search page sizes above 1000; a larger value silently disabled album exclusion
        val pageCountCaptor = argumentCaptor<Int>()
        verify(apiClient).listAssetsFromAlbum(eq(listOf("excluded-album")), any(), pageCountCaptor.capture())
        assertThat(pageCountCaptor.firstValue).isAtMost(MAX_SEARCH_PAGE_SIZE)
        assertThat(actual.items.single().ids()).containsExactly("random-asset")
    }

    @Test
    fun `filters out assets with exclude tag`() = runLoaderTest(
        type = ScreenSaverType.RANDOM,
        setupMocks = { apiClient ->
            val taggedAsset = asset("tagged-asset").copy(tags = listOf(Tag(null, java.util.Date(), "exclude_immich_tv", "")))
            whenever(
                apiClient.listAssets(
                    page = any(),
                    pageCount = any(),
                    random = any(),
                    order = any(),
                    personIds = any(),
                    fromDate = anyOrNull(),
                    endDate = anyOrNull(),
                    contentType = any(),
                    albumIds = any()
                )
            ).thenReturn(Either.Right(AssetResponse(listOf(asset("normal-asset"), taggedAsset), true)))
        }
    ) { actual, _ ->
        assertThat(actual.items.single().ids()).containsExactly("normal-asset")
    }

    private fun runLoaderTest(
        type: ScreenSaverType = ScreenSaverType.RANDOM,
        albums: Set<String> = emptySet(),
        excludedAlbums: Set<String> = emptySet(),
        expectedMessage: Int? = null,
        expectedLongDuration: Boolean = false,
        expectExit: Boolean = false,
        setupMocks: suspend (ApiClient) -> Unit,
        verifyResult: suspend (MediaSliderConfiguration, ApiClient) -> Unit = { _, _ -> }
    ) = runTest {
        setPreference(HOST_NAME, "https://example.com")
        setPreference(API_KEY, "api-key")
        setPreference(SCREENSAVER_TYPE, type)
        setPreference(SCREENSAVER_ALBUMS, albums)
        setPreference(EXCLUDE_ASSETS_IN_ALBUM, excludedAlbums)

        val apiClient = mock<ApiClient>()
        setupMocks(apiClient)

        val host = mock<ScreenSaverAssetLoader.Host>()
        val favoriteService = mock<FavoriteService>()
        val loader = ScreenSaverAssetLoader(
            scope = this,
            apiClient = apiClient,
            host = host,
            favoriteService = favoriteService
        )

        mockConstruction(MediaRemoteControlsKeyEventPlugin::class.java).use {
            loader.start().join()

            if (expectedMessage != null) {
                verify(host).showScreenSaverMessage(expectedMessage, expectedLongDuration)
                verify(host, never()).onScreenSaverConfigurationReady(any())
                if (expectExit) verify(host).exitScreenSaver()
                else verify(host, never()).exitScreenSaver()
                return@use
            }
            val configurationCaptor = argumentCaptor<MediaSliderConfiguration>()
            verify(host).onScreenSaverConfigurationReady(configurationCaptor.capture())
            verify(host, never()).showScreenSaverMessage(any(), any())
            verify(host, never()).exitScreenSaver()

            verifyResult(configurationCaptor.firstValue, apiClient)
        }
    }

    private fun <T> setPreference(pref: Pref<T, *, *>, value: T) {
        @Suppress("UNCHECKED_CAST")
        val liveContext = PreferenceManager.javaClass.getDeclaredField("liveContext").apply {
            isAccessible = true
        }.get(PreferenceManager) as MutableMap<String, Any?>
        liveContext[pref.key()] = value
        preferenceValues[pref.key()] = value
    }

    private fun asset(id: String) = Asset(
        id = id,
        type = "IMAGE",
        deviceAssetId = null,
        exifInfo = null,
        fileModifiedAt = null,
        albumName = null,
        people = null,
        tags = null,
        originalPath = null,
        originalFileName = null
    )
}
