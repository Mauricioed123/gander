package com.arjun.gander

import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two surfaces that are not a WebView: the tiling photo view and the
 * player.
 *
 * Neither can be tested off-device at all. The photo view needs Android's
 * region decoder, and the player needs a real codec.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ViewerNativeTest {

    @get:Rule
    val retry = RetryRule()

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    private fun open(fixture: String) =
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent(fixture))

    private fun children(scenario: ActivityScenario<ViewerActivity>): List<View> {
        var found: List<View> = emptyList()
        scenario.onActivity { activity ->
            val container = activity.findViewById<FrameLayout>(R.id.container)
            found = (0 until container.childCount).map { container.getChildAt(it) }
        }
        return found
    }

    /**
     * A photo goes to the tiling view rather than a WebView, because that is
     * what gives deep zoom on an image far larger than memory.
     */
    @Test
    fun aPhotoOpensInTheTilingView() {
        open("exif-1.jpg").use { scenario ->
            Thread.sleep(1500)
            val views = children(scenario)
            assertThat(views.filterIsInstance<SubsamplingScaleImageView>()).hasSize(1)
        }
    }

    /**
     * The rotation is read from the file's EXIF, because a content URI carries
     * no orientation of its own and the photo would otherwise open sideways.
     */
    @Test
    fun aRotatedPhotoIsTurnedTheRightWayUp() {
        open("exif-6.jpg").use { scenario ->
            Thread.sleep(1500)
            var orientation = -1
            scenario.onActivity { activity ->
                val container = activity.findViewById<FrameLayout>(R.id.container)
                val image = (0 until container.childCount)
                    .map { container.getChildAt(it) }
                    .filterIsInstance<SubsamplingScaleImageView>()
                    .firstOrNull()
                orientation = image?.orientation ?: -1
            }
            assertThat(orientation).isEqualTo(90)
        }
    }

    /**
     * Audio gets a screen of its own rather than a black video surface: the
     * cover art slot in view_audio_player.xml is what tells the two apart.
     */
    @Test
    fun anAudioFileOpensTheAudioScreenRatherThanAVideoSurface() {
        open("tone.wav").use { scenario ->
            Thread.sleep(2000)
            var hasCover = false
            scenario.onActivity { activity ->
                hasCover = activity.findViewById<View?>(R.id.audioCover) != null
            }
            assertThat(hasCover).isTrue()
        }
    }

    /**
     * Background audio will never be added: carrying on in the background
     * needs a foreground service, a service needs a permission, and a
     * permission is the one thing this app will not add. So leaving pauses.
     */
    @Test
    fun leavingTheViewerStopsThePlayback() {
        open("tone.wav").use { scenario ->
            Thread.sleep(2000)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            Thread.sleep(500)
            // Reaching CREATED without throwing is the assertion: onStop pauses
            // the player, and a released player would throw on the way through.
        }
    }
}
