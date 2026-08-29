package com.arjun.gander

import java.io.File

/**
 * The fixture documents, reachable as real files.
 *
 * tests/fixtures/files is on the unit test classpath (wired in
 * app/build.gradle.kts), but a classpath resource is not a file and several
 * things under test want a file descriptor. So each one is copied out on first
 * use into a directory that lasts for the JVM.
 */
internal object Fixtures {

    private val dir: File by lazy {
        File.createTempFile("gander-fixtures", "").let { probe ->
            probe.delete()
            probe.apply { mkdirs() }
        }
    }

    /** The fixture named [name], extracted once and reused. */
    fun file(name: String): File {
        val out = File(dir, name)
        if (!out.exists()) {
            val bytes = requireNotNull(
                Fixtures::class.java.classLoader?.getResourceAsStream(name)
            ) { "No fixture named $name. Run tests/fixtures/make_fixtures.py." }
                .use { it.readBytes() }
            out.writeBytes(bytes)
        }
        return out
    }

    fun bytes(name: String): ByteArray = file(name).readBytes()

    /**
     * A file of [size] bytes made on the spot, for the cases too large to
     * commit: the 16 MB range threshold, and the text viewer's 5 MB page.
     */
    fun sized(name: String, size: Int, fill: Byte = 'a'.code.toByte()): File {
        val out = File(dir, "sized-$size-$name")
        if (out.length().toInt() != size) out.writeBytes(ByteArray(size) { fill })
        return out
    }
}
