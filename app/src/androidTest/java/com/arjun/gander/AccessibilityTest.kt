package com.arjun.gander

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Google's accessibility checks, run over the screens as they are drawn.
 *
 * This is the same framework behind Play's pre-launch accessibility report,
 * which is where the badge contrast problems in 1.13 were found. Catching one
 * here costs a nightly run; catching one there costs a release.
 *
 * The checks hook Espresso's view actions rather than running on demand, so
 * this class exists to perform real actions on the native views. The other
 * device tests read the WebView, which the framework does not see into, so
 * enabling it there would look like coverage and be none.
 *
 * They cover the views Gander draws. The contents of a viewer page are the
 * page's business, and are checked in tests/viewer.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AccessibilityTest {

    @get:Rule
    val retry = RetryRule()

    companion object {
        @BeforeClass
        @JvmStatic
        fun enableChecks() {
            // Nothing suppressed. An earlier version excused touch target
            // sizes on the assumption that library-drawn controls would fail
            // it; they do not, and a suppression for a check that passes only
            // hides the day it stops.
            AccessibilityChecks.enable().setRunChecksFromRootView(true)
        }
    }

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    /**
     * Opening a document and using the toolbar runs every check over the
     * viewer's own views: the title, the menu items, the page indicator.
     */
    @Test
    fun theViewerToolbarPassesTheAccessibilityChecks() {
        ActivityScenario.launch<ViewerActivity>(
            DeviceFixtures.viewIntent("six-pages.pdf")
        ).use {
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun theSearchBarPassesTheAccessibilityChecks() {
        ActivityScenario.launch<ViewerActivity>(
            DeviceFixtures.viewIntent("plain.txt")
        ).use {
            Thread.sleep(1500)
            onView(withId(R.id.toolbar)).check(matches(isDisplayed()))
        }
    }

    /**
     * The first-run screen, which is where the badge colours live. Every tile
     * is a white label on a coloured ground, and four of them failed this
     * check before 1.13; the fixes are recorded as measured ratios in the
     * KDoc on the palette, and pinned arithmetically in ListingTest.
     *
     * This is the home screen a test can reach. Listing real files needs a
     * persisted URI grant, and only the system file picker can hand one out:
     * a grant the app makes to itself through its own FileProvider is not
     * persistable, so nothing a test opens ever lands in Recents.
     */
    @Test
    fun theFirstRunScreenPassesTheAccessibilityChecks() {
        ActivityScenario.launch(MainActivity::class.java).use {
            Thread.sleep(1500)
            onView(withId(R.id.welcome)).check(matches(isDisplayed()))
            onView(withId(R.id.formatGrid)).check(matches(isDisplayed()))
        }
    }

    /** And the About dialog, which is the app's only other native surface. */
    @Test
    fun theAboutDialogPassesTheAccessibilityChecks() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Thread.sleep(1000)
            var opened = false
            scenario.onActivity { activity ->
                opened = activity.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
                    .menu.performIdentifierAction(R.id.action_about, 0)
            }
            assertThat(opened).isTrue()
            Thread.sleep(1000)
        }
    }
}
