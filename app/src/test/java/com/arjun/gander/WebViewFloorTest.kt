package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Reading the engine version, and deciding what the PDF page is told about it.
 *
 * Every case here is a device that actually behaves this way; the reasoning
 * is in the KDoc beside each constant in WebViewFloor.kt.
 */
class WebViewFloorTest {

    private companion object {
        /** A current Android System WebView on a Pixel. */
        const val UA_MODERN =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, " +
            "like Gecko) Version/4.0 Chrome/138.0.7204.179 Mobile Safari/537.36"

        /** An engine below the pdf.js floor, but plainly a Chromium one. */
        const val UA_OLD =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/110.0.5481.65 Mobile Safari/537.36"

        /** A vendor engine whose user agent carries no Chrome/ token at all. */
        const val UA_NO_TOKEN =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Mobile Safari/537.36"
    }

    // ---------------------------------------------------------------
    // chromiumMajor
    // ---------------------------------------------------------------

    @Test
    fun theUserAgentIsReadFirst() {
        assertThat(chromiumMajor(UA_MODERN, null)).isEqualTo(138)
        assertThat(chromiumMajor(UA_OLD, null)).isEqualTo(110)
    }

    /**
     * The whole reason the user agent comes first. Huawei numbers its WebView
     * package 15.0.4.326, which parses to 15 and would be dismissed as
     * unreadable, while the engine inside it says what it really is.
     */
    @Test
    fun theUserAgentWinsOverAVendorPackageNumber() {
        assertThat(chromiumMajor(UA_MODERN, "15.0.4.326")).isEqualTo(138)
        assertThat(chromiumMajor(UA_OLD, "15.0.4.326")).isEqualTo(110)
    }

    /** The package is the fallback when the user agent names no engine. */
    @Test
    fun thePackageVersionAnswersWhenTheUserAgentDoesNot() {
        assertThat(chromiumMajor(UA_NO_TOKEN, "138.0.7204.179")).isEqualTo(138)
        assertThat(chromiumMajor(null, "125.0.6422.165")).isEqualTo(125)
    }

    /**
     * A number below the floor is a vendor scheme, not an engine that predates
     * updatable WebView. Treated as unreadable so the caller can decide, which
     * for an unlocked provider means letting the document through.
     */
    @Test
    fun aNumberTooLowToBeChromiumIsNotAVersion() {
        assertThat(chromiumMajor(null, "15.0.4.326")).isNull()
        assertThat(chromiumMajor(null, "1.0")).isNull()
        assertThat(chromiumMajor("Mozilla/5.0 Chrome/15.0.874.106", null)).isNull()
        // and exactly at the floor it is
        assertThat(chromiumMajor(null, "30.0.0.0")).isEqualTo(30)
    }

    @Test
    fun nothingReadableGivesNull() {
        assertThat(chromiumMajor(null, null)).isNull()
        assertThat(chromiumMajor(UA_NO_TOKEN, null)).isNull()
        assertThat(chromiumMajor("", "")).isNull()
        assertThat(chromiumMajor(UA_NO_TOKEN, "not-a-version")).isNull()
    }

    // ---------------------------------------------------------------
    // pdfjsFloorParams
    // ---------------------------------------------------------------

    /**
     * Only pdf.html is an ES module. Every other page is a classic script that
     * any engine can parse, so none of them is ever blocked.
     */
    @Test
    fun noOtherFormatIsEverBlocked() {
        FileKind.entries.filter { it != FileKind.PDF }.forEach { kind ->
            assertThat(pdfjsFloorParams(kind, 60, locked = true)).isEmpty()
            assertThat(pdfjsFloorParams(kind, null, locked = true)).isEmpty()
        }
    }

    @Test
    fun anEngineAtOrAboveTheFloorAddsNothing() {
        assertThat(pdfjsFloorParams(FileKind.PDF, PDFJS_MIN_CHROMIUM_MAJOR, false)).isEmpty()
        assertThat(pdfjsFloorParams(FileKind.PDF, 138, false)).isEmpty()
    }

    @Test
    fun anOldEngineIsNamedAlongsideTheFloorItMisses() {
        assertThat(pdfjsFloorParams(FileKind.PDF, 110, locked = false))
            .isEqualTo("&webview=110&needs=125")
    }

    /** On a locked provider the page drops the advice to go and update. */
    @Test
    fun aLockedProviderIsFlagged() {
        assertThat(pdfjsFloorParams(FileKind.PDF, 110, locked = true))
            .isEqualTo("&webview=110&needs=125&locked=1")
    }

    /**
     * The Huawei case the constants exist for: the version would not parse, and
     * because the provider cannot be replaced that means old rather than
     * unknown. The flag goes out with no major beside it, which is why the page
     * gates on either parameter.
     */
    @Test
    fun anUnreadableVersionOnALockedProviderStillBlocks() {
        assertThat(pdfjsFloorParams(FileKind.PDF, null, locked = true))
            .isEqualTo("&needs=125&locked=1")
    }

    /**
     * And the opposite: unreadable on a provider the reader could replace is
     * waved through. Refusing PDFs on a WebView that works is the worse
     * mistake, and pdf.html's nomodule fallback still catches a true ancient.
     */
    @Test
    fun anUnreadableVersionOnAnOrdinaryProviderIsWavedThrough() {
        assertThat(pdfjsFloorParams(FileKind.PDF, null, locked = false)).isEmpty()
    }

    // ---------------------------------------------------------------

    /**
     * The floor is repeated in docs/VENDORED.md and read by pdf.html out of the
     * query string. If it moves, the vendored pdf.js moved with it, and the
     * Chromium 138 ceiling on Android 8 and 9 is the thing to check first.
     */
    @Test
    fun theFloorIsStillOneTwentyFive() {
        assertThat(PDFJS_MIN_CHROMIUM_MAJOR).isEqualTo(125)
    }

    @Test
    fun huaweiIsTheOneLockedProvider() {
        assertThat(LOCKED_WEBVIEW_PACKAGES).containsExactly("com.huawei.webview")
    }
}
