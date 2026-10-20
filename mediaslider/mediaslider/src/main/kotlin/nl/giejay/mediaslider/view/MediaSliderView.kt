// Modified for Immich TV Plus on 2026-10-20: shuffle pages, loading, cancellation and player cleanup.
// Original MediaSliderView code remains under Apache-2.0; see mediaslider/LICENSE.md.
package nl.giejay.mediaslider.view

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.children
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.viewpager.widget.ViewPager
import com.google.common.collect.Iterables
import com.google.common.collect.Lists
import com.zeuskartik.mediaslider.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.giejay.mediaslider.adapter.ScreenSlidePagerAdapter
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.plugin.MetadataViewPlugin
import nl.giejay.mediaslider.plugin.SliderViewPlugin
import nl.giejay.mediaslider.plugin.SliderViewPluginContext
import nl.giejay.mediaslider.util.FixedSpeedScroller
import nl.giejay.mediaslider.util.LoadMoreResult
import nl.giejay.mediaslider.util.shuffledRound
import timber.log.Timber
import java.lang.reflect.Field

/**
 * Pager + metadata shell for the media slider. Timeline/memories chrome is provided
 * via pluggable view/controller/key plugins.
 */
open class MediaSliderView(context: Context) : ConstraintLayout(context) {
    // view elements
    protected val mainHandler = Handler(Looper.getMainLooper())
    protected val mPager: ViewPager
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "android.media.VOLUME_CHANGED_ACTION" && isConfigReady) {
                if (currentItemType() == SliderItemType.VIDEO
                    && controller.currentPlayer?.isPlaying == true
                    && controller.currentPlayer?.volume == 0f
                ) {
                    Timber.i("Volume changed detected, unmuting video")
                    Toast.makeText(context, "Volume changed detected, unmuting video", Toast.LENGTH_SHORT).show()
                    controller.toggleMute()
                }
            }
        }
    }

    // config
    protected lateinit var config: MediaSliderConfiguration
        private set
    protected val isConfigReady: Boolean get() = this::config.isInitialized
    private var isVolumeReceiverRegistered = false

    // internal
    protected val controller: MediaSliderController
    private var defaultExoFactory = DefaultHttpDataSource.Factory()
    private var pagerAdapter: ScreenSlidePagerAdapter? = null
    private var loading = false
    private var changingShuffleRound = false
    private var presentedShufflePage: View? = null
    private var destroyed = false
    private var shuffleRoundGeneration = 0
    private val ioScope = CoroutineScope(Job() + Dispatchers.IO)
    private data class ViewPluginEntry(val plugin: SliderViewPlugin<Any?>, val state: Any?)
    private val viewPlugins = mutableListOf<ViewPluginEntry>()
    private val viewPluginContext by lazy {
        SliderViewPluginContext(
            context,
            findViewById<ConstraintLayout>(R.id.plugin_layer),
            controller,
            ioScope
        ) { currentItem() }
    }

    init {
        inflate(getContext(), R.layout.slider, this)

        val playButton: View = findViewById(R.id.playPause)
        mPager = findViewById(R.id.pager)
        controller = MediaSliderController(context, mainHandler, mPager, playButton, this, ::startNextShuffleRound)
        playButton.setOnClickListener { controller.toggleSlideshow(true) }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isVolumeReceiverRegistered) {
            val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            context.registerReceiver(volumeReceiver, filter)
            isVolumeReceiverRegistered = true
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (isVolumeReceiverRegistered) {
            try {
                context.unregisterReceiver(volumeReceiver)
            } catch (e: IllegalArgumentException) {
                Timber.e(e, "volumeReceiver not registered")
            }
            isVolumeReceiverRegistered = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (mPager.adapter == null) {
            return super.dispatchKeyEvent(event)
        }
        return controller.dispatchKeyEvent(event) { super.dispatchKeyEvent(event) }
    }

    open fun loadMediaSliderView(config: MediaSliderConfiguration) {
        this.config = config
        setLoading(false)

        config.viewPlugins.forEach { plugin ->
            @Suppress("UNCHECKED_CAST")
            val typedPlugin = plugin as SliderViewPlugin<Any?>
            viewPlugins.add(ViewPluginEntry(typedPlugin, typedPlugin.createState(viewPluginContext, config)))
        }

        val listener: ExoPlayerListener = object : ExoPlayerListener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                controller.onPlaybackStateChanged(playbackState)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                controller.onIsPlayingChanged(isPlaying)
            }

            override fun onPlayerError(error: PlaybackException) {
                controller.onPlayerError(error)
            }
        }
        controller.initialize(config)
        viewPlugins.forEach { it.plugin.attachView(viewPluginContext.rootView, it.state) }
        viewPlugins.forEach { it.plugin.onLoadConfig(viewPluginContext, config, it.state) }
        initViewsAndSetAdapter(listener)
    }

    private fun setStartPosition() {
        if (config.startPosition >= 0) {
            if (config.startPosition > config.items.size) {
                mPager.currentItem = (config.items.size - 1)
                return
            }
            mPager.currentItem = config.startPosition
        } else {
            mPager.currentItem = 0
        }
        mPager.offscreenPageLimit = 1
        controller.restorePagerFocus()
    }

    private fun initViewsAndSetAdapter(listener: ExoPlayerListener) {
        pagerAdapter = ScreenSlidePagerAdapter(
            context,
            config.items,
            config,
            { mPager.currentItem },
            listener
        )

        try {
            if (config.enableSlideAnimation && config.animationSpeedMillis > 0) {
                val mScroller: Field = ViewPager::class.java.getDeclaredField("mScroller")
                mScroller.isAccessible = true
                val scroller = FixedSpeedScroller(mPager.context, DecelerateInterpolator(0.75F), config.animationSpeedMillis)
                mScroller.set(mPager, scroller)
            }
        } catch (e: Exception) {
            Timber.e(e, "Could not set scroller")
        }

        mPager.setAdapter(pagerAdapter)
        mPager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(sliderItemIndex: Int, v: Float, i1: Int) {
                if (config.loadMore != null && mPager.currentItem > config.items.size - 40 && !loading) {
                    loading = true
                    ioScope.launch {
                        val loadMore = config.loadMore ?: return@launch
                        val result: LoadMoreResult = loadMore()
                        addItemsMain(result.items)
                        if (!result.canLoadMore) {
                            config.loadMore = null
                        }
                        loading = false
                    }
                }
                presentPage(sliderItemIndex)
            }

            override fun onPageSelected(i: Int) {
                if (changingShuffleRound || destroyed) return
                viewPlugins.forEach { it.plugin.onPageSelected(viewPluginContext, i, it.state) }
                if (!controller.isControllerVisible) {
                    controller.restorePagerFocus()
                }
            }

            override fun onPageScrollStateChanged(i: Int) {}
        })
        // Register the listener before selecting the start page so the first asset
        // still gets onPageSelected / onPageSettled (metadata, date overlay, etc.).
        setStartPosition()
        if (config.shuffleOnRepeat) {
            // Selecting the already-current index 0 need not dispatch a pager event.
            mPager.post { if (!destroyed) presentPage(mPager.currentItem) }
        }
    }

    private fun presentPage(sliderItemIndex: Int) {
        if (changingShuffleRound || destroyed) return
        if (!config.shuffleOnRepeat) controller.stopPlayer()
        if (sliderItemIndex != mPager.currentItem) return
        val sliderItem = config.items.getOrNull(sliderItemIndex) ?: return
        val pageView = mPager.findViewWithTag<View>("view$sliderItemIndex")
        if (config.shuffleOnRepeat) {
            if (pageView == null) return
            // Scroll callbacks may fire several times for the same page, including videos.
            if (presentedShufflePage === pageView) return
            controller.stopPlayer()
            presentedShufflePage = pageView
        }
        val mainItem = sliderItem.mainItem
        viewPlugins.forEach { entry ->
            entry.plugin.onPageSettled(viewPluginContext, config, sliderItem, sliderItemIndex, mainHandler, entry.state)
        }
        config.onAssetSelected(sliderItem)

        if (sliderItem.type == SliderItemType.VIDEO) {
            val viewTag = pageView as? ExoPlayerView ?: return
            if (!viewTag.isReady()) {
                presentedShufflePage = null
                Timber.e("Player is not initialized properly, cannot play video.")
                Toast.makeText(context, "Player is not initialized properly, cannot play video.", Toast.LENGTH_LONG).show()
                return
            }
            val player = viewTag.getPlayer()!!
            controller.setCurrentPlayer(player)
            player.seekTo(0, 0)
            if (player.playbackState == Player.STATE_IDLE && sliderItem.url != null) {
                prepareMedia(sliderItem.url!!, player, defaultExoFactory)
            }
            if (!config.isVideoSoundEnable) player.volume = 0f
            player.playWhenReady = true
            controller.configureController(sliderItem, sliderItemIndex)
        } else {
            controller.setCurrentPlayer(null)
            controller.configureController(sliderItem, sliderItemIndex)
            if (controller.slideShowPlaying) {
                controller.startTimerNextAsset()
                val touchImageView = (pageView as? ViewGroup)?.children?.firstOrNull() as? TouchImageView
                if (touchImageView != null && config.zoomAndScrollPanorama && config.interval >= 10 && mainItem.isPanorama) {
                    touchImageView.zoomAndScrollPanorama(config, sliderItem)
                } else if (touchImageView != null && config.zoomAndScrollPanorama && !mainItem.isPanorama) {
                    touchImageView.zoomAndPanEffect(config, sliderItem)
                }
            }
        }
        controller.onShufflePageReady()
    }

    private fun startNextShuffleRound(reverse: Boolean) {
        if (changingShuffleRound || destroyed) return
        changingShuffleRound = true
        val generation = ++shuffleRoundGeneration
        Timber.i("Starting shuffled playback round %d (%d pages)", generation + 1, config.items.size)
        val previousItem = currentItem()
        controller.cancelNextAssetTimer()
        controller.stopPlayer()
        controller.setCurrentPlayer(null)
        presentedShufflePage = null
        // Detach first so old video pages are released before the new list is exposed.
        // Keep the adapter and the one registered page listener; do not reload plugins.
        mPager.adapter = null
        config.items = shuffledRound(config.items, previousItem, reverse)
        pagerAdapter!!.setItems(config.items)
        mPager.adapter = pagerAdapter
        mPager.setCurrentItem(if (reverse) config.items.lastIndex else 0, false)
        mPager.post {
            if (destroyed || generation != shuffleRoundGeneration) return@post
            changingShuffleRound = false
            viewPlugins.forEach { it.plugin.onPageSelected(viewPluginContext, mPager.currentItem, it.state) }
            presentPage(mPager.currentItem)
        }
    }

    fun onDestroy() {
        destroyed = true
        ioScope.cancel()
        viewPlugins.forEach { it.plugin.onDestroy(viewPluginContext, it.state) }
        controller.onDestroy()
    }

    fun setDefaultExoFactory(defaultExoFactory: DefaultHttpDataSource.Factory) {
        this.defaultExoFactory = defaultExoFactory
    }

    fun setLoading(loading: Boolean) {
        findViewById<View>(R.id.slider_loading).visibility = if (loading) View.VISIBLE else View.GONE
    }

    suspend fun addItemsMain(items: List<SliderItemViewHolder>) = withContext(Dispatchers.Main) {
        addItems(items)
    }

    private fun addItems(items: List<SliderItemViewHolder>) {
        setItems(Lists.newArrayList(Iterables.concat(config.items, items)).distinct())
    }

    fun setItems(items: List<SliderItemViewHolder>) {
        if (items.isEmpty()) {
            Toast.makeText(context, "No items received to show in slideshow", Toast.LENGTH_SHORT).show()
            return
        }
        if (controller.slideShowPlaying) {
            // Cancel any pending auto-advance to avoid timing races when new items arrive.
            controller.cancelNextAssetTimer()
        }
        config.items = items
        pagerAdapter!!.setItems(items)
        if (controller.slideShowPlaying && currentItemType() == SliderItemType.IMAGE) {
            controller.startTimerNextAsset()
        }
    }

    protected fun currentItem(): SliderItemViewHolder = config.items[mPager.currentItem]
    protected fun currentItemType(): SliderItemType = currentItem().type

    fun isControllerVisible(): Boolean = controller.isControllerVisible

    /** Delegates to [MediaSliderController.toggleSlideshow]. Part of the public API for external callers. */
    open fun toggleSlideshow(showPlayIndicator: Boolean) = controller.toggleSlideshow(showPlayIndicator)


    companion object {
        @SuppressLint("UnsafeOptInUsageError")
        fun prepareMedia(mediaUrl: String, player: ExoPlayer, factory: DefaultHttpDataSource.Factory) {
            val mediaUri = Uri.parse(mediaUrl)
            val mediaItem = MediaItem.fromUri(mediaUri)
            val mediaSource = ProgressiveMediaSource.Factory(factory).createMediaSource(mediaItem)
            player.setMediaSource(mediaSource, 0L)
            player.prepare()
        }
    }
}
