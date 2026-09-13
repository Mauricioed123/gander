package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * The things that have to be true of a release, checked on every commit
 * instead of on the day.
 *
 * None of this is about the code. It is about the four files that describe a
 * version to somebody who is not reading the code: the store listing, the
 * changelog, and the two numbers in the build file.
 */
class ReleaseHygieneTest {

    private companion object {
        val REPO = File("..")
        val BUILD_FILE = File(REPO, "app/build.gradle.kts").readText()
        val CHANGELOG = File(REPO, "CHANGELOG.md").readText()

        val VERSION_NAME: String =
            Regex("""versionName\s*=\s*"([^"]+)"""").find(BUILD_FILE)!!.groupValues[1]
        val VERSION_CODE: Int =
            Regex("""versionCode\s*=\s*(\d+)""").find(BUILD_FILE)!!.groupValues[1].toInt()
    }

    /**
     * Two components, never three. Gander versions are 1.13, not 1.13.0, and a
     * patch component appearing means somebody reached for a habit rather than
     * the scheme.
     */
    @Test
    fun theVersionNameHasNoPatchComponent() {
        assertThat(VERSION_NAME).matches("""\d+\.\d+""")
    }

    @Test
    fun theVersionCodeIsPositive() {
        assertThat(VERSION_CODE).isGreaterThan(0)
    }

    /**
     * The two numbers move together or not at all.
     *
     * Every release adds a changelog file named after its version code, so the
     * highest one on disk is the last release described. If the build declares
     * a higher code than that, a version was bumped without notes; a lower one
     * means notes were written for a release that was never built. Both have
     * happened to other projects on the day of a release, which is the worst
     * possible time to find out.
     */
    @Test
    fun theVersionCodeMatchesTheLastDescribedRelease() {
        val dir = File(REPO, "fastlane/metadata/android/en-US/changelogs")
        val highest = dir.listFiles { f -> f.extension == "txt" }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .max()
        assertThat(VERSION_CODE).isEqualTo(highest)
    }

    /**
     * Fastlane publishes one changelog per version code, and a missing file is
     * a release that ships to F-Droid with no release notes at all.
     */
    @Test
    fun theCurrentVersionCodeHasAStoreChangelog() {
        val notes = File(REPO, "fastlane/metadata/android/en-US/changelogs/$VERSION_CODE.txt")
        assertThat("$VERSION_CODE.txt exists=${notes.exists()}")
            .isEqualTo("$VERSION_CODE.txt exists=true")
        assertThat(notes.readText().trim()).isNotEmpty()
    }

    /** And every code before it, so the listing has no gaps. */
    @Test
    fun everyVersionCodeUpToTheCurrentOneHasOne() {
        val dir = File(REPO, "fastlane/metadata/android/en-US/changelogs")
        val present = dir.listFiles { f -> f.extension == "txt" }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .toSet()
        val missing = (1..VERSION_CODE).filterNot { it in present }
        assertThat(missing).isEmpty()
    }

    /**
     * The changelog names the shipped version, or carries an Unreleased
     * section for work that has not gone out yet. One or the other is always
     * true; neither means a release went out undocumented.
     */
    @Test
    fun theChangelogAccountsForTheCurrentVersion() {
        val documented = CHANGELOG.contains("## $VERSION_NAME") ||
            CHANGELOG.contains("## Unreleased")
        assertThat("changelog covers $VERSION_NAME: $documented")
            .isEqualTo("changelog covers $VERSION_NAME: true")
    }

    /**
     * Zero permissions is the whole promise, and app/build.gradle.kts fails
     * the build if the merged manifest requests one. That gate is the thing
     * that must not quietly disappear, so its absence fails here too.
     */
    @Test
    fun thePermissionGateIsStillWiredToBothOutputs() {
        assertThat(BUILD_FILE).contains("checkPermissions")
        assertThat(BUILD_FILE).contains("assemble\$suffix")
        assertThat(BUILD_FILE).contains("bundle\$suffix")
    }

    /**
     * R8 shipped in 1.13 and the mapping file is the only way a pasted stack
     * trace can be read back. Line numbers survive it because of an explicit
     * keep rule; losing that turns every crash report into hex.
     */
    @Test
    fun releaseBuildsKeepTheirLineNumbers() {
        val rules = File(REPO, "app/proguard-rules.pro").readText()
        assertThat(rules).contains("-keepattributes SourceFile,LineNumberTable")
        assertThat(BUILD_FILE).contains("isMinifyEnabled = true")
    }
}
