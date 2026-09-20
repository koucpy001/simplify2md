package io.github.koucpy001.simplify2md.update

/** Raised when a version string cannot be parsed; callers map it to "no update". */
class SemverException(message: String) : Exception(message)

/**
 * Semantic-version comparison, ported verbatim from `mdview/app.go:694-726`.
 *
 * Both sides have a single leading `v` stripped before comparison, missing
 * minor/patch fields are treated as 0, segments beyond the third are ignored
 * (so `1.2.3.4` compares as `1.2.3`), and a non-numeric segment on either side
 * is a parse error. Callers translate that error into "no update", never into a
 * user-visible failure, so a malformed upstream tag cannot nag the user.
 *
 * Segments are parsed as [Long] to match Go's 64-bit `int` (`strconv.Atoi`);
 * an overflowing segment is a parse error on both platforms.
 */
object Semver {

    /** Parses [version] into `[major, minor, patch]`; throws [SemverException]. */
    fun parse(version: String): LongArray {
        val nums = longArrayOf(0L, 0L, 0L)
        val parts = version.removePrefix("v").split(".")
        for (i in 0..2) {
            if (i >= parts.size) break
            val segment = parts[i].trim()
            nums[i] = segment.toLongOrNull()
                ?: throw SemverException("invalid version segment \"$segment\"")
        }
        return nums
    }

    /**
     * Reports whether [latest] is semantically greater than [current].
     * Throws [SemverException] when either side is unparseable.
     */
    fun greaterThan(latest: String, current: String): Boolean {
        val la = parse(latest)
        val ca = parse(current)
        for (i in 0..2) {
            if (la[i] != ca[i]) return la[i] > ca[i]
        }
        return false
    }
}
