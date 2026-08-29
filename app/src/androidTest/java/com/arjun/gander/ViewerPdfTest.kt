package com.arjun.gander

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.accessibility.AccessibilityChecks
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.DriverAtoms.webClick
import androidx.test.espresso.web.webdriver.DriverAtoms.webKeys
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.hamcrest.Matchers.containsString
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PDFs, rendered by the real WebView on a real Android.
 *
 * tests/viewer covers what the page does in a browser, which is most of it and
 * far faster. What only a device can answer is whether the WebView Android
 * actually ships behaves the same way: it is a different Chromium build, in a
 * separate process, inside an app with no network permission.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ViewerPdfTest {

    @get:Rule
    val retry = RetryRule()

    companion object {
        @BeforeClass
        @JvmStatic
        fun enableAccessibilityChecks() {
            // The same scan Play's pre-launch report runs, applied to every
            // Espresso action. It sees the native views; WebView contents are
            // covered by the page's own tests.
            AccessibilityChecks.enable().setRunChecksFromRootView(true)
        }
    }

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    private fun open(fixture: String) =
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent(fixture))

    @Test
    fun aDocumentRendersOnTheDevicesOwnWebView() {
        open("six-pages.pdf").use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#pages .pg"))
        }
    }

    @Test
    fun theTextLayerCarriesTheWordsOnThePage() {
        open("six-pages.pdf").use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#pages .pg .textLayer"))
                .check(webMatches(getText(), containsString("Alder Court")))
        }
    }

    /**
     * The CJK regression, on a device. The Mac harness proves the CMap tables
     * work; this proves they are inside the APK and reachable through the
     * asset loader, which is a different question.
     */
    @Test
    fun chineseTextRendersFromTheBundledCmapTables() {
        open("cjk.pdf").use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#pages .pg .textLayer"))
                .check(webMatches(getText(), containsString("你好世界")))
        }
    }

    @Test
    fun anEncryptedDocumentUnlocksThroughTheInPageForm() {
        open("encrypted.pdf").use {
            onWebView()
                .withElement(findElement(Locator.ID, "vw-pw"))
                .perform(webKeys("gander"))
                .withElement(findElement(Locator.CSS_SELECTOR, ".vw-ask-btn"))
                .perform(webClick())
                .withElement(findElement(Locator.CSS_SELECTOR, "#pages .pg canvas"))
        }
    }

    @Test
    fun aFileThatIsNotAPdfSaysSoRatherThanShowingNothing() {
        open("not-a-pdf.pdf").use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, ".vw-error-title"))
                .check(webMatches(getText(), containsString("not a PDF")))
        }
    }
}
