package app.umbra.agent.skills

import app.umbra.core.model.UmbraJson
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Every request is answered in an interceptor; these tests never open a network connection. */
class SkillHubHttpTest {
    private fun response(request: Request, body: String, code: Int = 200, headers: Map<String, String> = emptyMap()) =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .body(body.toResponseBody("application/json".toMediaType())).apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    private val repo = """{"full_name":"owner/repo","description":"Tools","stargazers_count":10,"updated_at":"2026-10-01T00:00:00Z","license":{"spdx_id":"MIT"},"html_url":"https://github.com/owner/repo","default_branch":"release/v2"}"""

    @Test fun resultsAreCachedForTenMinutesWithoutSendingRepoCredentialsToSearch(): Unit = runBlocking {
        val requests = mutableListOf<Request>()
        var now = 1000L
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request()
            response(chain.request(), """{"items":[$repo]}""")
        }.build()
        val hub = SkillHub(http, token = { name -> if (name == null) null else "repository-token" }, nowMs = { now })
        val first = hub.search("android")
        assertEquals(listOf("owner/repo"), first.map { it.fullName })
        assertEquals("MIT", first.single().license)
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.header("Authorization") == null && it.url.queryParameter("sort") == "stars" })
        assertEquals(first, hub.search(" android "))
        assertEquals(2, requests.size)
        now += 10 * 60 * 1000
        hub.search("android")
        assertEquals(4, requests.size)
    }

    @Test fun rateLimitsCarryResetAndRetryTimeAndAreNotCached(): Unit = runBlocking {
        var requests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            requests++
            response(chain.request(), "{}", 403, mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "2000", "Retry-After" to "30"))
        }.build()
        val hub = SkillHub(http)
        val error = assertFailsWith<SkillRateLimitException> { hub.search("") }
        assertEquals(2_000_000L, error.resetAtMs)
        assertEquals(30_000L, error.retryAfterMs)
        assertFailsWith<SkillRateLimitException> { hub.search("") }
        assertEquals(2, requests)
        val limited = SkillHub(OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), "{}", 429) }.build())
        assertNull(assertFailsWith<SkillRateLimitException> { limited.search("") }.resetAtMs)
    }

    @Test fun oneRecursiveTreeAndAtMostThirtyMarkdownFetchesUseTheDefaultBranch(): Unit = runBlocking {
        val markdown = "---\nname: android\ndescription: Build Android apps\n---\n"
        val bytes = markdown.toByteArray()
        val nodes = (1..31).map { SkillTreeNode("skills/s$it/SKILL.md", "blob", "100644", SkillHub.blobSha(bytes), bytes.size.toLong()) } +
            SkillTreeNode("vendor/SKILL.md", "blob", "100644", SkillHub.blobSha(bytes), bytes.size.toLong())
        val tree = "{\"tree\":" + UmbraJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(SkillTreeNode.serializer()), nodes) + ",\"truncated\":false}"
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            assertEquals("Bearer repository-token", request.header("Authorization"))
            response(request, when {
                request.url.host == "raw.githubusercontent.com" -> markdown
                "trees" in request.url.pathSegments -> tree
                else -> repo
            })
        }.build()
        val hub = SkillHub(http, token = { "repository-token" })
        val entries = hub.skills("owner/repo")
        assertEquals(30, entries.size)
        assertEquals(1, requests.count { "trees" in it.url.pathSegments })
        assertEquals(30, requests.count { it.url.host == "raw.githubusercontent.com" })
        val treeRequest = requests.single { "trees" in it.url.pathSegments }
        assertEquals("release/v2", treeRequest.url.pathSegments.last())
        assertEquals("1", treeRequest.url.queryParameter("recursive"))
        assertEquals(1, entries.first().fileCount)
        assertEquals(bytes.size.toLong(), entries.first().totalSize)
        assertEquals("MIT", entries.first().license)
        assertTrue(entries.all { it.ref == "release/v2" })
    }

    @Test fun truncatedTreesAndChangedFilesAreRefused(): Unit = runBlocking {
        val hub = SkillHub(OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), """{"tree":[],"truncated":true}""")
        }.build())
        assertFailsWith<IllegalArgumentException> { hub.files("owner/repo", "main", "skills/a") }
        val changed = SkillHub(OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), "new") }.build())
        val file = RemoteFile("SKILL.md", SkillHub.rawUrl("owner/repo", "main", "SKILL.md"), 3, SkillHub.blobSha("old".toByteArray()))
        assertFailsWith<IllegalArgumentException> { changed.download("owner/repo", file) }
        assertFailsWith<IllegalArgumentException> { changed.download("owner/repo", file.copy(size = 2)) }
        assertFailsWith<IllegalArgumentException> { changed.download("owner/repo", file.copy(url = "https://example.com/file")) }
        val failed = SkillHub(OkHttpClient.Builder().addInterceptor { chain -> response(chain.request(), "no", 404) }.build())
        assertFailsWith<IOException> { failed.download("owner/repo", file) }
    }
}
