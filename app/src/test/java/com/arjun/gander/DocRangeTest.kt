package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import org.junit.Test

/**
 * The HTTP range server behind /doc/.
 *
 * pdf.js asks for hundreds of small pieces of a large document, and every one
 * of them goes through [parseRange] and comes back through [LimitedInputStream].
 * Both are pure, so both are tested here rather than through a WebView.
 */
class DocRangeTest {

    // ---------------------------------------------------------------
    // useRanges
    // ---------------------------------------------------------------

    @Test
    fun rangesStartAtSixteenMegabytes() {
        assertThat(useRanges(RANGE_THRESHOLD_BYTES - 1)).isFalse()
        assertThat(useRanges(RANGE_THRESHOLD_BYTES)).isTrue()
        assertThat(useRanges(RANGE_THRESHOLD_BYTES + 1)).isTrue()
    }

    /** A provider that would not say how long the file is cannot be ranged. */
    @Test
    fun anUnknownLengthIsNotRangeable() {
        assertThat(useRanges(-1L)).isFalse()
        assertThat(useRanges(0L)).isFalse()
    }

    @Test
    fun theThresholdIsSixteenMebibytes() {
        assertThat(RANGE_THRESHOLD_BYTES).isEqualTo(16L * 1024 * 1024)
    }

    // ---------------------------------------------------------------
    // parseRange
    // ---------------------------------------------------------------

    @Test
    fun aClosedRangeIsTakenAsWritten() {
        assertThat(parseRange("bytes=0-99", 1000)).isEqualTo(0L to 99L)
        assertThat(parseRange("bytes=100-199", 1000)).isEqualTo(100L to 199L)
        assertThat(parseRange("bytes=999-999", 1000)).isEqualTo(999L to 999L)
    }

    /** "give me the rest", which is what pdf.js opens a document with. */
    @Test
    fun anOpenEndedRangeRunsToTheLastByte() {
        assertThat(parseRange("bytes=100-", 1000)).isEqualTo(100L to 999L)
        assertThat(parseRange("bytes=0-", 1000)).isEqualTo(0L to 999L)
    }

    @Test
    fun anEndPastTheDocumentIsClampedToIt() {
        assertThat(parseRange("bytes=0-5000", 1000)).isEqualTo(0L to 999L)
    }

    /** Only the first range of a set. pdf.js never sends more than one. */
    @Test
    fun onlyTheFirstOfSeveralRangesIsServed() {
        assertThat(parseRange("bytes=0-99,200-299", 1000)).isEqualTo(0L to 99L)
    }

    @Test
    fun whitespaceAroundTheNumbersIsTolerated() {
        assertThat(parseRange("bytes= 10 - 20 ", 1000)).isEqualTo(10L to 20L)
    }

    @Test
    fun anUnsatisfiableRangeServesTheWholeDocument() {
        // start beyond the end of the file
        assertThat(parseRange("bytes=1000-1099", 1000)).isNull()
        assertThat(parseRange("bytes=5000-", 1000)).isNull()
        // start after end
        assertThat(parseRange("bytes=500-100", 1000)).isNull()
    }

    @Test
    fun anUnparseableHeaderServesTheWholeDocument() {
        assertThat(parseRange("", 1000)).isNull()
        assertThat(parseRange("bytes=", 1000)).isNull()
        assertThat(parseRange("bytes=abc-def", 1000)).isNull()
        assertThat(parseRange("items=0-99", 1000)).isNull()
        assertThat(parseRange("0-99", 1000)).isNull()
    }

    /**
     * A suffix range, "the last 500 bytes", is not supported and falls back to
     * serving the whole document. Pinned rather than fixed: pdf.js never sends
     * one, and a 200 with the entire file is a correct answer to any range
     * request, just a wasteful one.
     */
    @Test
    fun aSuffixRangeIsNotUnderstoodAndServesTheWholeDocument() {
        assertThat(parseRange("bytes=-500", 1000)).isNull()
    }

    @Test
    fun nothingIsRangeableWithoutAKnownLength() {
        assertThat(parseRange("bytes=0-99", 0)).isNull()
        assertThat(parseRange("bytes=0-99", -1)).isNull()
    }

    // ---------------------------------------------------------------
    // LimitedInputStream
    // ---------------------------------------------------------------

    private class Spy : Closeable {
        var closed = 0
        override fun close() { closed++ }
    }

    private fun limited(bytes: ByteArray, limit: Long, spy: Closeable = Spy()) =
        LimitedInputStream(ByteArrayInputStream(bytes), limit, spy)

    @Test
    fun bulkReadsStopAtTheLimit() {
        val stream = limited(ByteArray(100) { it.toByte() }, 10)
        val buf = ByteArray(100)
        assertThat(stream.read(buf, 0, 100)).isEqualTo(10)
        assertThat(stream.read(buf, 0, 100)).isEqualTo(-1)
    }

    @Test
    fun singleByteReadsStopAtTheLimit() {
        val stream = limited(byteArrayOf(1, 2, 3, 4, 5), 3)
        assertThat(stream.read()).isEqualTo(1)
        assertThat(stream.read()).isEqualTo(2)
        assertThat(stream.read()).isEqualTo(3)
        assertThat(stream.read()).isEqualTo(-1)
        // and stays finished
        assertThat(stream.read()).isEqualTo(-1)
    }

    @Test
    fun theBytesDeliveredAreTheOnesAskedFor() {
        val source = ByteArray(50) { it.toByte() }
        val stream = limited(source, 5)
        assertThat(stream.readBytes()).isEqualTo(source.copyOfRange(0, 5))
    }

    /** A short source ends the stream before the limit does. */
    @Test
    fun aSourceShorterThanTheLimitSimplyEnds() {
        val stream = limited(byteArrayOf(1, 2, 3), 100)
        assertThat(stream.readBytes()).hasLength(3)
        assertThat(stream.read()).isEqualTo(-1)
    }

    @Test
    fun aZeroLimitDeliversNothing() {
        val stream = limited(ByteArray(10), 0)
        assertThat(stream.read()).isEqualTo(-1)
        assertThat(stream.read(ByteArray(10), 0, 10)).isEqualTo(-1)
        assertThat(stream.available()).isEqualTo(0)
    }

    @Test
    fun availableNeverPromisesMoreThanTheLimit() {
        val stream = limited(ByteArray(100), 7)
        assertThat(stream.available()).isEqualTo(7)
        stream.read(ByteArray(3), 0, 3)
        assertThat(stream.available()).isEqualTo(4)
    }

    /**
     * The descriptor has to be closed with the stream, or every ranged read of
     * a large document leaks one and the process runs out.
     */
    @Test
    fun closingAlsoClosesTheDescriptor() {
        val spy = Spy()
        limited(ByteArray(10), 10, spy).close()
        assertThat(spy.closed).isEqualTo(1)
    }

    /**
     * And it is closed even when closing the stream throws, which is the case
     * the two separate runCatching blocks exist for.
     */
    @Test
    fun theDescriptorIsClosedEvenWhenTheStreamRefuses() {
        val spy = Spy()
        val refuses = object : InputStream() {
            override fun read(): Int = -1
            override fun close() = throw IOException("no")
        }
        LimitedInputStream(refuses, 10, spy).close()
        assertThat(spy.closed).isEqualTo(1)
    }
}
