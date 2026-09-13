package com.arjun.gander

import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads a viewer page by running JavaScript in the WebView showing it.
 *
 * Espresso-Web is the obvious tool and works for every other page, but its
 * bridge returns nothing at all against pdf.html: that page is the only one
 * loaded as an ES module, and the only one holding a live MessageChannel to
 * the activity. Rather than fight it, this asks the WebView directly, which
 * is both more reliable and closer to what the page actually is.
 */
object WebViewProbe {

    private fun webViewIn(activity: ViewerActivity): WebView? {
        val container = activity.findViewById<FrameLayout>(R.id.container)
        return (0 until container.childCount)
            .map { container.getChildAt(it) }
            .filterIsInstance<WebView>()
            .firstOrNull()
    }

    /** Evaluates [js] and answers the JSON it produced, or null. */
    fun eval(
        scenario: ActivityScenario<ViewerActivity>,
        js: String,
        timeoutMs: Long = 15_000
    ): String? {
        var answer: String? = null
        val done = CountDownLatch(1)
        scenario.onActivity { activity ->
            val web = webViewIn(activity)
            if (web == null) {
                done.countDown()
            } else {
                web.evaluateJavascript(js) { value ->
                    answer = value
                    done.countDown()
                }
            }
        }
        done.await(timeoutMs, TimeUnit.MILLISECONDS)
        return answer
    }

    /** The string [js] evaluates to, with the JSON quoting taken off. */
    fun text(scenario: ActivityScenario<ViewerActivity>, js: String): String =
        eval(scenario, js).orEmpty()
            .removeSurrounding("\"")
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .let { unescapeUnicode(it) }

    /** WebView returns non-ASCII as \\uXXXX inside its JSON string. */
    private fun unescapeUnicode(s: String): String =
        Regex("""\\u([0-9a-fA-F]{4})""").replace(s) {
            it.groupValues[1].toInt(16).toChar().toString()
        }

    /**
     * Waits for [condition] to hold, polling. Documents take seconds to open
     * on an emulator and there is no event to wait on from out here.
     */
    fun await(
        scenario: ActivityScenario<ViewerActivity>,
        condition: String,
        what: String,
        timeoutMs: Long = 60_000
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: String? = null
        while (System.currentTimeMillis() < deadline) {
            last = eval(scenario, "(function () { try { return ($condition) ? 1 : 0; }" +
                " catch (e) { return 'threw: ' + e; } })()")
            if (last == "1") return
            Thread.sleep(250)
        }
        throw AssertionError("Timed out waiting for $what. Last answer: $last")
    }
}
