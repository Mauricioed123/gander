package com.arjun.gander

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * Thumbnails: which files get one, and which way up.
 *
 * Native graphics, so the EXIF fixtures are decoded rather than stubbed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
        File(context.cacheDir, "thumbs").deleteRecursively()
    }

    // ---------------------------------------------------------------
    // Which kinds get a preview at all
    // ---------------------------------------------------------------

    @Test
    fun thingsWithAPictureInThemGetAThumbnail() {
        assertThat(Thumbs.supported(FileKind.IMAGE, "jpg")).isTrue()
        assertThat(Thumbs.supported(FileKind.IMAGE_WEB, "gif")).isTrue()
        assertThat(Thumbs.supported(FileKind.PDF, "pdf")).isTrue()
        assertThat(Thumbs.supported(FileKind.PLAYER, "mp4")).isTrue()
    }

    /**
     * Audio is the exception inside PLAYER: there is no frame to grab, and a
     * blank grey square says less than the AUD badge it would replace.
     */
    @Test
    fun audioKeepsItsBadgeInsteadOfABlankFrame() {
        listOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "amr", "aac", "oga")
            .forEach { assertThat(Thumbs.supported(FileKind.PLAYER, it)).isFalse() }
    }

    @Test
    fun documentsAndTextKeepTheirBadges() {
        listOf(
            FileKind.DOCX, FileKind.XLSX, FileKind.PPTX,
            FileKind.MD, FileKind.TEXT, FileKind.UNSUPPORTED,
        ).forEach { assertThat(Thumbs.supported(it, "")).isFalse() }
    }

    /** Every kind is decided one way or the other; none of them throws. */
    @Test
    fun everyKindHasAnAnswer() {
        FileKind.entries.forEach { Thumbs.supported(it, "bin") }
    }

    // ---------------------------------------------------------------
    // EXIF rotation
    // ---------------------------------------------------------------

    private fun rotationOf(fixture: String): Int {
        val uri = FixtureProvider.uriFor(fixture)
        return Thumbs.exifRotation(context.contentResolver, uri)
    }

    @Test
    fun anUnrotatedPhotoIsLeftAlone() {
        assertThat(rotationOf("exif-1.jpg")).isEqualTo(0)
    }

    @Test
    fun theThreeQuarterTurnsAreRead() {
        assertThat(rotationOf("exif-6.jpg")).isEqualTo(90)
        assertThat(rotationOf("exif-3.jpg")).isEqualTo(180)
        assertThat(rotationOf("exif-8.jpg")).isEqualTo(270)
    }

    /**
     * Transpose and transverse are a rotation plus a mirror. Gander drops the
     * mirror and keeps the rotation, which puts the photo the right way up
     * even though it is not strictly what the tag asked for. A mirrored
     * portrait is a far smaller wrong than a sideways one.
     */
    @Test
    fun theMirroredOrientationsKeepTheirRotation() {
        assertThat(rotationOf("exif-5.jpg")).isEqualTo(90)
        assertThat(rotationOf("exif-7.jpg")).isEqualTo(270)
    }

    @Test
    fun aPhotoWithNoExifIsNotRotated() {
        assertThat(rotationOf("tiny.png")).isEqualTo(0)
    }

    /** Asked about something that is not an image at all. */
    @Test
    fun aNonImageIsNotRotated() {
        assertThat(rotationOf("plain.txt")).isEqualTo(0)
        assertThat(rotationOf("six-pages.pdf")).isEqualTo(0)
    }

    /**
     * A provider that throws must give an upright photo, not a crash. This is
     * called while binding a row, so it runs for every file on screen.
     */
    @Test
    fun aProviderThatThrowsGivesNoRotation() {
        val uri = FixtureProvider.uriFor(FixtureProvider.BROKEN)
        assertThat(Thumbs.exifRotation(context.contentResolver, uri)).isEqualTo(0)
    }

    // ---------------------------------------------------------------
    // The disk cache
    // ---------------------------------------------------------------

    /** The same key derivation Thumbs uses, so the file can be found. */
    private fun cacheFileFor(uriString: String): File {
        val key = MessageDigest.getInstance("MD5")
            .digest(uriString.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "thumbs"), "$key.png")
    }

    @Test
    fun evictingAFileRemovesItsCachedThumbnail() {
        val uri = FixtureProvider.uriFor("exif-1.jpg").toString()
        val cached = cacheFileFor(uri).apply {
            parentFile?.mkdirs()
            writeBytes(Fixtures.bytes("tiny.png"))
        }
        assertThat(cached.exists()).isTrue()

        Thumbs.evict(context, uri)

        assertThat(cached.exists()).isFalse()
    }

    @Test
    fun evictingSomethingNeverCachedIsHarmless() {
        Thumbs.evict(context, "content://elsewhere/never-seen")
    }

    /**
     * Thumbnails live in the cache directory, which Android is free to empty
     * at any time. Nothing here may be the only copy of anything.
     */
    @Test
    fun thumbnailsAreKeptOnlyInTheCacheDirectory() {
        val uri = FixtureProvider.uriFor("exif-1.jpg").toString()
        assertThat(cacheFileFor(uri).canonicalPath)
            .startsWith(context.cacheDir.canonicalPath)
    }
}
