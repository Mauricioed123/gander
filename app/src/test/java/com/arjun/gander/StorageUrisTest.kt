package com.arjun.gander

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Turning a document URI back into the folder it came out of.
 *
 * Robolectric rather than plain JUnit because DocumentsContract and Uri are
 * framework classes, and the document id formats these assert are exactly what
 * the real providers use.
 */
@RunWith(AndroidJUnit4::class)
class StorageUrisTest {

    private companion object {
        const val PRIMARY = "/storage/emulated/0"

        /** No MediaStore in most of these, so the lookup is not offered one. */
        val NO_MEDIA: (Uri) -> String? = { null }

        fun externalDoc(docId: String): Uri =
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, docId)

        fun downloadsDoc(docId: String): Uri =
            DocumentsContract.buildDocumentUri(DOWNLOADS_AUTHORITY, docId)
    }

    private fun parentOf(uri: Uri, media: (Uri) -> String? = NO_MEDIA): Uri? =
        parentDocUri(uri, PRIMARY, media)

    private fun docIdOf(uri: Uri?): String? =
        uri?.let { DocumentsContract.getDocumentId(it) }

    // ---------------------------------------------------------------
    // folderDocId: a path to a provider document id
    // ---------------------------------------------------------------

    @Test
    fun pathsUnderPrimaryStorageBecomePrimaryIds() {
        assertThat(folderDocId("$PRIMARY/Documents", PRIMARY)).isEqualTo("primary:Documents")
        assertThat(folderDocId("$PRIMARY/Documents/Leases", PRIMARY))
            .isEqualTo("primary:Documents/Leases")
    }

    /** The root of primary storage is "primary:" with nothing after it. */
    @Test
    fun theRootOfPrimaryStorageHasAnEmptyPath() {
        assertThat(folderDocId(PRIMARY, PRIMARY)).isEqualTo("primary:")
        assertThat(folderDocId("$PRIMARY/", PRIMARY)).isEqualTo("primary:")
    }

    /** An SD card or a USB stick is named by its volume id instead. */
    @Test
    fun aRemovableVolumeKeepsItsOwnName() {
        assertThat(folderDocId("/storage/1234-5678/Docs", PRIMARY))
            .isEqualTo("1234-5678:Docs")
        assertThat(folderDocId("/storage/1234-5678", PRIMARY)).isEqualTo("1234-5678:")
    }

    @Test
    fun aPathUnderNeitherHasNoDocumentId() {
        assertThat(folderDocId("/data/data/com.example/files", PRIMARY)).isNull()
        assertThat(folderDocId("/system/etc", PRIMARY)).isNull()
        assertThat(folderDocId("", PRIMARY)).isNull()
    }

    // ---------------------------------------------------------------
    // parentDocId: dropping the last segment of an id
    // ---------------------------------------------------------------

    @Test
    fun theParentOfADocumentIdDropsTheFileSegment() {
        assertThat(parentDocId("primary:Documents/lease.pdf")).isEqualTo("primary:Documents")
        assertThat(parentDocId("primary:a/b/c.pdf")).isEqualTo("primary:a/b")
        assertThat(parentDocId("1234-5678:Docs/x.pdf")).isEqualTo("1234-5678:Docs")
    }

    /** A file at the volume root has the root as its parent. */
    @Test
    fun aFileAtTheVolumeRootReportsTheRoot() {
        assertThat(parentDocId("primary:lease.pdf")).isEqualTo("primary:")
    }

    @Test
    fun anIdMissingEitherHalfHasNoParent() {
        assertThat(parentDocId("primary:")).isNull()
        assertThat(parentDocId(":Documents")).isNull()
        assertThat(parentDocId("nocolon")).isNull()
        assertThat(parentDocId("")).isNull()
    }

    // ---------------------------------------------------------------
    // parentDocUri: the four shapes a document arrives in
    // ---------------------------------------------------------------

    @Test
    fun aFileUriResolvesThroughItsPath() {
        val uri = Uri.fromFile(java.io.File("$PRIMARY/Documents/lease.pdf"))
        assertThat(docIdOf(parentOf(uri))).isEqualTo("primary:Documents")
    }

    @Test
    fun aFileUriOutsideSharedStorageHasNoFolder() {
        val uri = Uri.fromFile(java.io.File("/data/data/com.example/files/x.pdf"))
        assertThat(parentOf(uri)).isNull()
    }

    @Test
    fun anExternalStorageDocumentDropsItsFileSegment() {
        assertThat(docIdOf(parentOf(externalDoc("primary:Documents/lease.pdf"))))
            .isEqualTo("primary:Documents")
        assertThat(docIdOf(parentOf(externalDoc("1234-5678:Scans/page.pdf"))))
            .isEqualTo("1234-5678:Scans")
    }

    /** Every folder answered is one the external storage provider owns. */
    @Test
    fun theFolderIsAlwaysAnExternalStorageDocument() {
        listOf(
            externalDoc("primary:Documents/lease.pdf"),
            downloadsDoc("raw:$PRIMARY/Download/lease.pdf"),
            downloadsDoc("msf:42"),
        ).forEach { uri ->
            assertThat(parentOf(uri)?.authority).isEqualTo(EXTERNAL_STORAGE_AUTHORITY)
        }
    }

    /** Downloads sometimes hands out a real path, and then it can be followed. */
    @Test
    fun aRawDownloadsIdResolvesThroughItsPath() {
        val uri = downloadsDoc("raw:$PRIMARY/Download/lease.pdf")
        assertThat(docIdOf(parentOf(uri))).isEqualTo("primary:Download")
    }

    /**
     * More often it hands out an opaque id that says nothing about where the
     * file is. Everything this provider gives out is at least under Download,
     * so that is the answer rather than nothing.
     */
    @Test
    fun anOpaqueDownloadsIdFallsBackToTheDownloadFolder() {
        assertThat(docIdOf(parentOf(downloadsDoc("msf:42")))).isEqualTo("primary:Download")
        assertThat(docIdOf(parentOf(downloadsDoc("1000000042")))).isEqualTo("primary:Download")
    }

    @Test
    fun aMediaStoreUriResolvesThroughTheBackingPath() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val found = parentOf(uri) { "$PRIMARY/Pictures/holiday.jpg" }
        assertThat(docIdOf(found)).isEqualTo("primary:Pictures")
    }

    @Test
    fun aMediaStoreUriWithNoBackingPathHasNoFolder() {
        assertThat(parentOf(Uri.parse("content://media/external/images/media/42"))).isNull()
    }

    @Test
    fun anUnknownProviderHasNoFolder() {
        assertThat(parentOf(Uri.parse("content://com.example.cloud/documents/42"))).isNull()
    }

    /**
     * A provider that throws, which a cloud one in a bad state does, must not
     * take the viewer down: the action is simply not offered.
     */
    @Test
    fun aProviderThatThrowsIsAnsweredWithNothing() {
        val uri = Uri.parse("content://media/external/images/media/42")
        val found = parentOf(uri) { error("provider is having a bad day") }
        assertThat(found).isNull()
    }

    /** An external storage document at the volume root has no folder above it. */
    @Test
    fun aDocumentAtTheVolumeRootHasNoFolder() {
        assertThat(parentOf(externalDoc("primary:"))).isNull()
    }
}
