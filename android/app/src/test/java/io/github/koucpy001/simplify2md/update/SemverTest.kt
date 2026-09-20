package io.github.koucpy001.simplify2md.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `Semver` parity with `versionGreaterThan`/`parseSemver`
 * (`mdview/app.go:694-726`, `mdview/app_update_test.go:23-59`).
 */
class SemverTest {

    @Test
    fun sameVersionIsNotGreater() {
        assertFalse(Semver.greaterThan("1.2.3", "1.2.3"))
    }

    @Test
    fun newerVersionIsGreater() {
        assertTrue(Semver.greaterThan("1.3.0", "1.2.3"))
    }

    @Test
    fun olderVersionIsNotGreater() {
        assertFalse(Semver.greaterThan("1.0.0", "1.2.3"))
    }

    @Test
    fun vPrefixIsStripped() {
        assertTrue(Semver.greaterThan("v1.2.3", "1.2.2"))
    }

    @Test
    fun missingPatchSegmentIsTreatedAsZero() {
        assertFalse(Semver.greaterThan("1.2", "1.2.0"))
        assertTrue(Semver.greaterThan("1.2", "1.1.9"))
    }

    @Test
    fun segmentsBeyondTheThirdAreIgnored() {
        assertFalse(Semver.greaterThan("1.2.3.4", "1.2.3"))
        assertTrue(Semver.greaterThan("1.2.4.0", "1.2.3"))
    }

    @Test
    fun zeroVersionParses() {
        assertFalse(Semver.greaterThan("v0.0.0", "0.0.0"))
    }

    @Test
    fun hugeVersionNumberIsAParseErrorNotAWrap() {
        try {
            Semver.greaterThan("99999999999999999999", "1.0.0")
            fail("overflowing segment must throw")
        } catch (_: SemverException) {
            // expected
        }
    }

    @Test
    fun malformedLatestThrows() {
        try {
            Semver.greaterThan("not-a-version", "1.2.3")
            fail("malformed latest must throw")
        } catch (_: SemverException) {
            // expected
        }
    }

    @Test
    fun prereleaseSuffixIsAParseError() {
        try {
            Semver.greaterThan("1.2.3-beta", "1.2.3")
            fail("prerelease segment must throw")
        } catch (_: SemverException) {
            // expected
        }
    }

    @Test
    fun emptyVersionThrows() {
        try {
            Semver.greaterThan("", "1.2.3")
            fail("empty version must throw")
        } catch (_: SemverException) {
            // expected
        }
    }
}
