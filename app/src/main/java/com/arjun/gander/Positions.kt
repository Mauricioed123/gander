package com.arjun.gander

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import java.security.MessageDigest

/**
 * The page each PDF was last left open at, so that it opens there next time. Issue #25.
 *
 * Kept against what the file holds rather than where it came from. One document reaches
 * the viewer under a different URI from the picker, from a granted folder and from a file
 * manager's "Open with", and an attachment handed over by a mail or chat app often arrives
 * on a URI that will not exist tomorrow. A key read from the bytes is the same down every
 * one of those routes, survives the file being renamed or moved, and tells anyone reading
 * this store nothing about what the file is called or where it lives.
 *
 * Two copies of one file therefore share a page, which is the right answer, and a file
 * saved again with changes in it starts from the top, which is a defensible one.
 *
 * What is stored is the page and when it was left there, the second only so the oldest
 * entry can be dropped once there are [MAX] of them.
 */
object Positions {

    private const val PREFS = "positions"

    /** More documents than anybody has on the go at once, and a few kilobytes at most. */
    private const val MAX = 100

    /**
     * How much of the file the key is read from.
     *
     * Far enough past the header and the first few objects, which two documents out of the
     * same generator can share byte for byte, and small enough to cost nothing beside the
     * read the viewer is about to make of the same file. The length goes in too, so a
     * document that has grown is a different document.
     */
    private const val HEAD_BYTES = 64 * 1024

    /**
     * What the document at [uri] is remembered by, or null when it cannot be read.
     * [length] is its size as the provider reported it, or -1.
     */
    fun keyFor(resolver: ContentResolver, uri: Uri, length: Long): String? = runCatching {
        val head = ByteArray(HEAD_BYTES)
        var filled = 0
        val input = resolver.openInputStream(uri) ?: return null
        input.use {
            while (filled < head.size) {
                val n = it.read(head, filled, head.size - filled)
                if (n < 0) break
                filled += n
            }
        }
        if (filled == 0) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(head, 0, filled)
        digest.update(length.toString().toByteArray())
        digest.digest().take(16).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** The page to open the document under [key] at, or 0 for the top. */
    fun page(context: Context, key: String): Int =
        prefs(context).getString(key, null)?.substringBefore(' ')?.toIntOrNull() ?: 0

    /**
     * Remember that the document under [key] was left at [page] of [total].
     *
     * The first page is forgotten rather than stored, since it is where a document opens
     * anyway. So is the last, and that one is a decision rather than a saving: a document
     * read to the end that reopens on its final page looks broken, and somebody who has
     * finished it is more likely to be starting again than looking for the back cover.
     */
    fun save(context: Context, key: String, page: Int, total: Int) {
        val prefs = prefs(context)
        prefs.edit {
            if (page in 2 until total) putString(key, "$page ${System.currentTimeMillis()}")
            else remove(key)
        }
        val all = prefs.all
        if (all.size <= MAX) return
        val oldest = all.entries
            .sortedBy { (it.value as? String)?.substringAfter(' ')?.toLongOrNull() ?: 0L }
            .take(all.size - MAX)
        prefs.edit { oldest.forEach { remove(it.key) } }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
