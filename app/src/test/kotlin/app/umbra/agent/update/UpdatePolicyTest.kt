package app.umbra.agent.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatePolicyTest {
    private val sha = "b".repeat(64)
    private val site = """{"version":"0.2.1","versionCode":3,"file":"umbra-0.2.1.apk","bytes":669046125,"sha256":"$sha","url":"/download/umbra-0.2.1.apk","notes":"ignored"}"""

    @Test
    fun readsTheSitesUpdateJsonAndIgnoresUnknownFields() {
        val m = UpdatePolicy.parse(site)
        assertEquals(UpdateManifest("0.2.1", 3, "umbra-0.2.1.apk", 669046125, sha, "/download/umbra-0.2.1.apk"), m)
        assertNull(UpdatePolicy.problem(m))
    }

    @Test
    fun refusesBadHashSizeVersionOrFileName() {
        val m = UpdatePolicy.parse(site)
        assertNotNull(UpdatePolicy.problem(m.copy(sha256 = "B".repeat(64))))
        assertNotNull(UpdatePolicy.problem(m.copy(sha256 = "(see /download/umbra-0.2.1.apk.sha256)")))
        assertNotNull(UpdatePolicy.problem(m.copy(bytes = 0)))
        assertNotNull(UpdatePolicy.problem(m.copy(versionCode = 0)))
        assertNotNull(UpdatePolicy.problem(m.copy(file = "../umbra-0.2.1.apk")))
        assertNotNull(UpdatePolicy.problem(m.copy(file = "other.apk")))
    }

    @Test
    fun onlyAHigherVersionCodeIsAnUpdate() {
        val m = UpdatePolicy.parse(site)
        assertTrue(UpdatePolicy.isNewer(m, 2))
        assertFalse(UpdatePolicy.isNewer(m, 3))
        assertFalse(UpdatePolicy.isNewer(m, 4))
    }

    @Test
    fun apkAddressFollowsTheManifestAndMustBeHttps() {
        assertEquals("https://umbra-agent.pages.dev/download/umbra-0.2.1.apk", UpdatePolicy.apkUrl(UpdatePolicy.DEFAULT_URL, "/download/umbra-0.2.1.apk"))
        assertEquals("https://ota-test.umbra-agent.pages.dev/download/x.apk", UpdatePolicy.apkUrl("https://ota-test.umbra-agent.pages.dev/update.json", "download/x.apk"))
        assertFailsWith<IllegalArgumentException> { UpdatePolicy.apkUrl(UpdatePolicy.DEFAULT_URL, "http://example.com/umbra-0.2.1.apk") }
    }

    @Test
    fun downloadedFilesCarryTheirVersionCode() {
        assertEquals(3L, UpdatePolicy.versionCodeOf("umbra-3.apk"))
        assertEquals(12L, UpdatePolicy.versionCodeOf("umbra-12.apk.part"))
        assertNull(UpdatePolicy.versionCodeOf("umbra-0.2.1.apk"))
        assertNull(UpdatePolicy.versionCodeOf("notes.txt"))
    }
}
