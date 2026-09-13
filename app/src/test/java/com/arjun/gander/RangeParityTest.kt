package com.arjun.gander

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Kotlin range parser and the Python one that stands in for it agree.
 *
 * tests/viewer/server.py reimplements ViewerActivity's range handling so the
 * viewer pages can be served documents without an Android in the way. Two
 * implementations of one rule drift, and a harness that answers differently
 * from the app turns every test above it into a test of the wrong software.
 *
 * Both sides read tests/fixtures/range-cases.json. This checks the Kotlin
 * half; tests/viewer/test_range_parity.py checks the Python half. Neither
 * knows about the other, and the file is the whole of the agreement.
 *
 * Robolectric because org.json lives in the Android framework, and in a plain
 * JVM test the framework is a stub that throws.
 */
@RunWith(AndroidJUnit4::class)
class RangeParityTest {

    private val cases = JSONObject(
        File("../tests/fixtures/range-cases.json").readText()
    ).getJSONArray("cases")

    @Test
    fun everySharedCaseParsesTheWayTheTableSays() {
        (0 until cases.length()).forEach { i ->
            val case = cases.getJSONObject(i)
            val header = case.getString("header")
            val total = case.getLong("total")
            val expected = if (case.isNull("expected")) {
                null
            } else {
                val pair = case.getJSONArray("expected")
                pair.getLong(0) to pair.getLong(1)
            }
            assertThat("${header.ifEmpty { "<empty>" }} of $total -> ${parseRange(header, total)}")
                .isEqualTo("${header.ifEmpty { "<empty>" }} of $total -> $expected")
        }
    }

    /**
     * A table of nothing but nulls would agree with a parser that refused
     * every range, so both answers have to be represented in it.
     */
    @Test
    fun theTableCoversBothAnswers() {
        assertThat(cases.length()).isAtLeast(15)
        val nulls = (0 until cases.length()).count { cases.getJSONObject(it).isNull("expected") }
        assertThat(nulls).isGreaterThan(0)
        assertThat(nulls).isLessThan(cases.length())
    }
}
