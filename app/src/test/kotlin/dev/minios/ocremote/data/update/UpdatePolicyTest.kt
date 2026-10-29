package dev.minios.ocremote.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    @Test
    fun `manifest transforms only a valid repository release`() {
        val release = UpdatePolicy.manifestToRelease(
            UpdateManifestDto(
                versionName = "1.7.0",
                versionCode = 23,
                releaseUrl = "https://github.com/dcon4/voxremote/releases/tag/v1.7.0",
            ),
        )

        assertEquals(AvailableUpdate("1.7.0", 23, "https://github.com/dcon4/voxremote/releases/tag/v1.7.0"), release)
        assertNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto("1.7.0", 23, "https://github.com/dcon4/voxremote/releases/tag/v1.6.9"),
            ),
        )
    }

    @Test
    fun `github fallback requires exact tag and release URL`() {
        assertEquals(
            AvailableUpdate("1.7.0", null, "https://github.com/dcon4/voxremote/releases/tag/v1.7.0"),
            UpdatePolicy.githubToRelease(
                GitHubReleaseDto("v1.7.0", "https://github.com/dcon4/voxremote/releases/tag/v1.7.0"),
            ),
        )
        assertNull(
            UpdatePolicy.githubToRelease(
                GitHubReleaseDto("v1.7.0", "https://github.com/other/repo/releases/tag/v1.7.0"),
            ),
        )
    }

    @Test
    fun `version code takes precedence and github falls back to semver`() {
        assertTrue(UpdatePolicy.isNewer(AvailableUpdate("1.0.0", 23, "https://github.com/dcon4/voxremote/releases/tag/v1.0.0"), 22, "9.0.0"))
        assertFalse(UpdatePolicy.isNewer(AvailableUpdate("9.0.0", 22, "https://github.com/dcon4/voxremote/releases/tag/v9.0.0"), 22, "1.0.0"))
        assertTrue(UpdatePolicy.isNewer(AvailableUpdate("1.7.0", null, "https://github.com/dcon4/voxremote/releases/tag/v1.7.0"), 22, "1.6.9"))
    }

    @Test
    fun `manifest requires a positive version code`() {
        assertNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto("1.7.0", 0, "https://github.com/dcon4/voxremote/releases/tag/v1.7.0"),
            ),
        )
    }

    @Test
    fun `rich manifest requires exact package URL and checksum`() {
        val release = UpdatePolicy.manifestToRelease(
            UpdateManifestDto(
                versionName = "1.7.0",
                versionCode = 23,
                releaseUrl = "https://github.com/dcon4/voxremote/releases/tag/v1.7.0",
                packageName = "com.dcon4.voxremote",
                apkUrl = "https://github.com/dcon4/voxremote/releases/download/v1.7.0/voxremote-1.7.0.apk",
                sha256 = "A".repeat(64),
            ),
        )

        assertEquals("a".repeat(64), release?.sha256)
        assertTrue(release?.let(UpdatePolicy::isInstallable) == true)
        assertNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto(
                    "1.7.0", 23,
                    "https://github.com/dcon4/voxremote/releases/tag/v1.7.0",
                    "com.dcon4.voxremote",
                    "https://github.com/dcon4/voxremote/releases/download/v1.7.1/voxremote-1.7.0.apk",
                    "a".repeat(64),
                ),
            ),
        )
    }

    @Test
    fun `manifest rejects partial or malformed rich metadata but accepts legacy`() {
        val legacy = UpdatePolicy.manifestToRelease(
            UpdateManifestDto("1.7.0", 23, "https://github.com/dcon4/voxremote/releases/tag/v1.7.0"),
        )
        assertTrue(legacy != null)
        assertFalse(legacy?.let(UpdatePolicy::isInstallable) ?: true)
        assertNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto(
                    "1.7.0", 23, "https://github.com/dcon4/voxremote/releases/tag/v1.7.0",
                    packageName = "com.dcon4.voxremote",
                ),
            ),
        )
        assertNull(
            UpdatePolicy.manifestToRelease(
                UpdateManifestDto(
                    "1.7.0", 23, "https://github.com/dcon4/voxremote/releases/tag/v1.7.0",
                    "com.dcon4.voxremote",
                    "https://github.com/dcon4/voxremote/releases/download/v1.7.0/voxremote-1.7.0.apk",
                    "not-a-sha",
                ),
            ),
        )
    }
}
