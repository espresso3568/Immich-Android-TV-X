package nl.giejay.android.tv.immich.screensaver

import android.content.Context
import android.os.Handler
import android.view.View
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import nl.giejay.mediaslider.config.MediaSliderConfiguration
import nl.giejay.mediaslider.model.SliderItemType
import nl.giejay.mediaslider.model.SliderItemViewHolder
import nl.giejay.mediaslider.view.MediaSliderController
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Tests the actual controller paths used by photo timers, video end events and remote keys. */
class ShufflePlaybackControllerTest {
    private val rounds = mutableListOf<Boolean>()
    private val pager: ViewPager = mock()
    private val handler: Handler = mock()
    private val config: MediaSliderConfiguration = mock()

    private fun controller(
        shuffle: Boolean = true,
        count: Int = 3,
        position: Int = count - 1,
        type: SliderItemType = SliderItemType.IMAGE
    ): MediaSliderController {
        val adapter: PagerAdapter = mock()
        val item: SliderItemViewHolder = mock()
        whenever(item.type).thenReturn(type)
        whenever(config.items).thenReturn(List(count) { item })
        whenever(config.shuffleOnRepeat).thenReturn(shuffle)
        whenever(config.controllerPlugins).thenReturn(emptyList())
        whenever(config.interval).thenReturn(6)
        whenever(pager.adapter).thenReturn(adapter)
        whenever(adapter.count).thenReturn(count)
        whenever(pager.currentItem).thenReturn(position)
        return MediaSliderController(mock<Context>(), handler, pager, mock<View>(), mock<View>()) {
            rounds += it
        }.also { it.initialize(config) }
    }

    @Test
    fun `forward boundary starts one new round until the page becomes ready`() {
        val controller = controller()
        controller.goToNextAsset()
        controller.goToNextAsset()
        assertEquals(listOf(false), rounds)
        controller.onShufflePageReady()
        controller.goToNextAsset()
        assertEquals(listOf(false, false), rounds)
    }

    @Test
    fun `reverse boundary requests a round starting from the last position`() {
        controller(position = 0).goToPreviousAsset()
        assertEquals(listOf(true), rounds)
    }

    @Test
    fun `ordinary playback still wraps to zero without requesting a shuffle`() {
        controller(shuffle = false).goToNextAsset()
        verify(pager).setCurrentItem(0, false)
        assertEquals(emptyList<Boolean>(), rounds)
    }

    @Test
    fun `duplicate video ended callbacks cannot skip into a second round`() {
        val controller = controller(type = SliderItemType.VIDEO)
        val player: ExoPlayer = mock()
        whenever(player.playbackState).thenReturn(Player.STATE_ENDED)
        controller.setCurrentPlayer(player)
        controller.toggleSlideshow(false)
        controller.onPlaybackStateChanged(Player.STATE_ENDED)
        controller.onPlaybackStateChanged(Player.STATE_ENDED)
        assertEquals(listOf(false), rounds)
        verify(player).stop()
    }

    @Test
    fun `single page can start later rounds once replacement finishes`() {
        val controller = controller(count = 1)
        repeat(3) {
            controller.goToNextAsset()
            controller.onShufflePageReady()
        }
        assertEquals(listOf(false, false, false), rounds)
    }

    @Test
    fun `pause and resume do not request a new round`() {
        val controller = controller(position = 0)
        repeat(3) { controller.toggleSlideshow(false) }
        assertEquals(emptyList<Boolean>(), rounds)
    }

    @Test
    fun `exit while albums are still loading is safe before configuration exists`() {
        val controller = MediaSliderController(mock<Context>(), handler, pager, mock<View>(), mock<View>()) {}
        controller.onDestroy()
        assertEquals(emptyList<Boolean>(), rounds)
    }
}
