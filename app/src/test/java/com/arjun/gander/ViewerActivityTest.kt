package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * The viewer, driven by the intents that really reach it.
 *
 * The WebView is Robolectric's shadow, so nothing here renders a document.
 * What it can answer is everything decided before the renderer runs: which
 * surface a file gets, what URL the page is loaded from, and what the request
 * interceptor serves. The rendering itself is tested in tests/viewer and on a
 * device.
 */
@RunWith(AndroidJUnit4::class)
class ViewerActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
    }

    // ---------------------------------------------------------------
    // Launching
    // ---------------------------------------------------------------

    private fun view(uri: Uri, type: String? = null): ActivityController<ViewerActivity> {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setDataAndType(uri, type ?: context.contentResolver.getType(uri))
        return Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
    }

    private fun open(fixture: String): ActivityController<ViewerActivity> =
        view(FixtureProvider.uriFor(fixture))

    private fun ActivityController<ViewerActivity>.container(): FrameLayout =
        get().findViewById(R.id.container)

    private fun ActivityController<ViewerActivity>.webView(): WebView? =
        container().children().filterIsInstance<WebView>().firstOrNull()

    private fun FrameLayout.children(): List<android.view.View> =
        (0 until childCount).map { getChildAt(it) }

    private fun ActivityController<ViewerActivity>.loadedUrl(): String =
        shadowOf(webView()!!).lastLoadedUrl

    // ---------------------------------------------------------------
    // Which surface a file gets
    // ---------------------------------------------------------------

    @Test
    fun aPdfIsLoadedIntoThePdfPage() {
        val url = open("six-pages.pdf").loadedUrl()
        assertThat(url).startsWith("https://appassets.androidplatform.net/assets/viewer/pdf.html")
        assertThat(url).contains("ext=pdf")
    }

    @Test
    fun eachWebFormatGetsItsOwnPage() {
        mapOf(
            "report.docx" to "docx.html",
            "budget.xlsx" to "xlsx.html",
            "budget.csv" to "xlsx.html",
            "deck.pptx" to "pptx.html",
            "notes.md" to "md.html",
            "plain.txt" to "text.html",
            "icon.svg" to "imgweb.html",
            "anim.gif" to "imgweb.html",
            "unknown.xyz" to "unsupported.html",
        ).forEach { (fixture, page) ->
            val url = open(fixture).loadedUrl()
            assertThat("$fixture -> ${url.substringAfter("viewer/").substringBefore('?')}")
                .isEqualTo("$fixture -> $page")
        }
    }

    /** A photo gets the tiling view, not a WebView. */
    @Test
    fun aPhotoIsDrawnByTheTilingViewInstead() {
        val controller = open("exif-1.jpg")
        assertThat(controller.webView()).isNull()
        assertThat(controller.container().children().filterIsInstance<SubsamplingScaleImageView>())
            .hasSize(1)
    }

    @Test
    fun audioGetsThePlayerRatherThanAPage() {
        val controller = open("tone.wav")
        assertThat(controller.webView()).isNull()
        assertThat(controller.container().childCount).isAtLeast(1)
    }

    /** The file name reaches the page, so it can title itself. */
    @Test
    fun thePageIsToldTheFileName() {
        val uri = FixtureProvider.uriNamed("six-pages.pdf", "Alder Court.pdf")
        assertThat(view(uri).loadedUrl()).contains("name=Alder%20Court.pdf")
    }

    @Test
    fun theToolbarShowsTheDocumentName() {
        val uri = FixtureProvider.uriNamed("six-pages.pdf", "Alder Court.pdf")
        val toolbar = view(uri).get()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        assertThat(toolbar.title.toString()).isEqualTo("Alder Court.pdf")
    }

    // ---------------------------------------------------------------
    // Ranged loading
    // ---------------------------------------------------------------

    /** Below the threshold, the page is told to read the file whole. */
    @Test
    fun aSmallDocumentIsNotRanged() {
        assertThat(open("six-pages.pdf").loadedUrl()).contains("ranged=0")
    }

    /**
     * Above it, both sides switch together: the page uses the ranged loader
     * and the interceptor starts offering ranges. They are decided from the
     * one number so they cannot disagree.
     */
    @Test
    fun aLargeDocumentIsRanged() {
        val big = Fixtures.sized("big.pdf", (RANGE_THRESHOLD_BYTES + 1).toInt())
        val uri = FixtureProvider.install().add("big.pdf", big)
        assertThat(view(uri, "application/pdf").loadedUrl()).contains("ranged=1")
    }

    // ---------------------------------------------------------------
    // Serving the document
    // ---------------------------------------------------------------

    private class Request(
        private val url: Uri,
        private val headers: Map<String, String>
    ) : WebResourceRequest {
        override fun getUrl() = url
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders() = headers
    }

    private fun ActivityController<ViewerActivity>.serve(
        path: String,
        range: String? = null
    ): WebResourceResponse? {
        val web = webView()!!
        val headers = range?.let { mapOf("Range" to it) } ?: emptyMap()
        return web.webViewClient.shouldInterceptRequest(
            web, Request(Uri.parse("https://appassets.androidplatform.net$path"), headers)
        )
    }

    @Test
    fun theDocumentIsServedWholeWhenNoRangeIsAskedFor() {
        val response = open("six-pages.pdf").serve("/doc/file.pdf")!!
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.mimeType).isEqualTo("application/pdf")
        assertThat(response.responseHeaders["Content-Length"])
            .isEqualTo(Fixtures.file("six-pages.pdf").length().toString())
        assertThat(response.data.readBytes()).isEqualTo(Fixtures.bytes("six-pages.pdf"))
    }

    /** A small document never offers ranges, so a Range header is ignored. */
    @Test
    fun aRangeAskedOfASmallDocumentIsAnsweredWithTheWholeThing() {
        val response = open("six-pages.pdf").serve("/doc/file.pdf", "bytes=0-99")!!
        assertThat(response.statusCode).isEqualTo(200)
        assertThat(response.responseHeaders).doesNotContainKey("Accept-Ranges")
    }

    @Test
    fun aRangeableDocumentAnswersARangeWithExactlyThoseBytes() {
        val size = (RANGE_THRESHOLD_BYTES + 512).toInt()
        val big = Fixtures.sized("ranged.pdf", size)
        val uri = FixtureProvider.install().add("ranged.pdf", big)
        val controller = view(uri, "application/pdf")

        val response = controller.serve("/doc/file.pdf", "bytes=100-199")!!

        assertThat(response.statusCode).isEqualTo(206)
        assertThat(response.reasonPhrase).isEqualTo("Partial Content")
        assertThat(response.responseHeaders["Content-Range"])
            .isEqualTo("bytes 100-199/$size")
        assertThat(response.responseHeaders["Content-Length"]).isEqualTo("100")
        assertThat(response.data.readBytes()).hasLength(100)
    }

    @Test
    fun aRangeableDocumentAdvertisesThatItAcceptsRanges() {
        val big = Fixtures.sized("ranged2.pdf", (RANGE_THRESHOLD_BYTES + 1).toInt())
        val uri = FixtureProvider.install().add("ranged2.pdf", big)
        val response = view(uri, "application/pdf").serve("/doc/file.pdf")!!
        assertThat(response.responseHeaders["Accept-Ranges"]).isEqualTo("bytes")
    }

    @Test
    fun anUnsatisfiableRangeFallsBackToTheWholeDocument() {
        val big = Fixtures.sized("ranged3.pdf", (RANGE_THRESHOLD_BYTES + 1).toInt())
        val uri = FixtureProvider.install().add("ranged3.pdf", big)
        val response = view(uri, "application/pdf")
            .serve("/doc/file.pdf", "bytes=99999999999-")!!
        assertThat(response.statusCode).isEqualTo(200)
    }

    /**
     * A provider that dies mid-document gets a 404 rather than an exception
     * thrown inside the renderer, which pdf.js reports as a readable error.
     */
    @Test
    fun aProviderThatFailsIsAnsweredWithNotFound() {
        val controller = view(FixtureProvider.uriFor(FixtureProvider.BROKEN), "application/pdf")
        val response = controller.serve("/doc/file.pdf")!!
        assertThat(response.statusCode).isEqualTo(404)
    }

    /** Everything that is not the document comes from the bundled assets. */
    @Test
    fun assetRequestsAreServedFromTheApk() {
        val response = open("six-pages.pdf").serve("/assets/viewer/app.js")
        assertThat(response).isNotNull()
        assertThat(response!!.data.readBytes().decodeToString()).contains("vwDocUrl")
    }

    // ---------------------------------------------------------------
    // The boundary around the page
    // ---------------------------------------------------------------

    /**
     * A document is untrusted content, and this is the wall around it. The
     * page may load nothing but the assets host: no CDN, no tracking pixel,
     * no link a crafted PDF talks the renderer into following.
     */
    @Test
    fun thePageMayNotNavigateAnywhereButTheAssetHost() {
        val web = open("six-pages.pdf").webView()!!
        listOf(
            "https://example.com/",
            "http://appassets.androidplatform.net.example.com/",
            "file:///etc/hosts",
            "content://com.arjun.gander.debug.fileprovider/cache/x",
        ).forEach { url ->
            val blocked = web.webViewClient.shouldOverrideUrlLoading(
                web, Request(Uri.parse(url), emptyMap())
            )
            assertThat("$url blocked: $blocked").isEqualTo("$url blocked: true")
        }
    }

    @Test
    fun theAssetHostItselfIsAllowedThrough() {
        val web = open("six-pages.pdf").webView()!!
        val blocked = web.webViewClient.shouldOverrideUrlLoading(
            web, Request(Uri.parse("https://appassets.androidplatform.net/assets/viewer/text.html"), emptyMap())
        )
        assertThat(blocked).isFalse()
    }

    @Test
    fun theWebViewReachesNeitherTheFilesystemNorTheProviders() {
        val settings = open("six-pages.pdf").webView()!!.settings
        assertThat(settings.allowFileAccess).isFalse()
        assertThat(settings.allowContentAccess).isFalse()
    }

    // ---------------------------------------------------------------
    // How a file arrives
    // ---------------------------------------------------------------

    @Test
    fun sharedPlainTextIsWrittenOutAndShownAsText() {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Something copied out of another app")
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()

        assertThat(controller.loadedUrl()).contains("viewer/text.html")
        assertThat(File(context.cacheDir, "shared-text.txt").readText())
            .isEqualTo("Something copied out of another app")
    }

    @Test
    fun aSharedFileArrivesThroughExtraStream() {
        val intent = Intent(context, ViewerActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, FixtureProvider.uriFor("six-pages.pdf"))
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        assertThat(controller.loadedUrl()).contains("viewer/pdf.html")
    }

    /** The path extra, which only the bundled licence viewer uses. */
    @Test
    fun aPlainPathIsOpenedAsAFile() {
        val intent = Intent(context, ViewerActivity::class.java)
            .putExtra(ViewerActivity.EXTRA_PATH, Fixtures.file("notes.md").absolutePath)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        assertThat(controller.loadedUrl()).contains("viewer/md.html")
    }

    /** Nothing to show is not a blank screen; it is not a screen at all. */
    @Test
    fun anIntentCarryingNothingClosesTheViewer() {
        val intent = Intent(context, ViewerActivity::class.java)
        val controller = Robolectric.buildActivity(ViewerActivity::class.java, intent).setup()
        assertThat(controller.get().isFinishing).isTrue()
    }

    // ---------------------------------------------------------------
    // Recents, and state
    // ---------------------------------------------------------------

    @Test
    fun openingAPickedFileRemembersIt() {
        context.getSharedPreferences("recents", Context.MODE_PRIVATE).edit().clear().commit()
        val uri = FixtureProvider.uriNamed("six-pages.pdf", "Alder Court.pdf")
        view(uri)
        assertThat(Recents.all(context).map { it.name }).containsExactly("Alder Court.pdf")
    }

    /**
     * The save picker outlives the activity if Android reclaims the process
     * while it is open, and comes back with a destination but no source. The
     * source is kept in the bundle for exactly that.
     */
    @Test
    fun theSaveDestinationSurvivesTheProcessGoingAway() {
        val controller = open("six-pages.pdf")
        controller.get()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.performIdentifierAction(R.id.action_save_copy, 0)

        val state = android.os.Bundle()
        controller.saveInstanceState(state)

        assertThat(state.getString("copy_source")).contains("six-pages.pdf")
    }

    @Test
    fun leavingTheViewerTakesTheWebViewWithIt() {
        val controller = open("six-pages.pdf")
        val web = controller.webView()!!
        controller.pause().stop().destroy()
        assertThat(shadowOf(web).wasDestroyCalled()).isTrue()
    }
}
