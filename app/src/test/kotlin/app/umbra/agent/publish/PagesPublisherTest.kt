package app.umbra.agent.publish

import java.io.IOException
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.Request
import okio.Buffer
import org.junit.jupiter.api.io.TempDir

class PagesPublisherTest {
    @TempDir lateinit var temporary: Path
    private fun root() = temporary.toRealPath().toFile()
    private val account = "a".repeat(32)
    private val token = "private-api-token"

    private class MemorySecrets : PagesPublisher.Secrets {
        val values = mutableMapOf<String, String>()
        override fun get(name: String) = values[name]
        override fun put(name: String, value: String) { values[name] = value }
        override fun remove(name: String) { values.remove(name) }
    }

    private data class Captured(val request: Request, val body: String) {
        val path get() = request.url.encodedPath.removePrefix("/client/v4")
        val json get() = Json.parseToJsonElement(body)
        val authorization get() = request.header("Authorization")
    }

    private class FakeTransport(private val respond: suspend (Captured, Int) -> PagesResponse) : PagesTransport {
        val calls = mutableListOf<Captured>()
        override suspend fun execute(request: Request): PagesResponse {
            val buffer = Buffer()
            request.body?.writeTo(buffer)
            val captured = Captured(request, buffer.readUtf8())
            calls += captured
            assertEquals("https", request.url.scheme)
            assertEquals("api.cloudflare.com", request.url.host)
            return respond(captured, calls.lastIndex)
        }
    }

    private fun ok(result: JsonElement = JsonObject(emptyMap())) = PagesResponse(200, buildJsonObject {
        put("success", true); put("result", result)
    }.toString())

    private fun error(status: Int, message: String, code: Int = 1000) = PagesResponse(status, buildJsonObject {
        put("success", false)
        putJsonArray("errors") { add(buildJsonObject { put("code", code); put("message", message) }) }
    }.toString())

    private fun credentials() = MemorySecrets().apply {
        put(PagesPublisher.ACCOUNT_SECRET, account)
        put(PagesPublisher.TOKEN_SECRET, token)
    }

    private fun deployment() = ok(buildJsonObject {
        put("id", "deployment-1"); put("url", "https://deployment-1.pages.dev")
        putJsonArray("aliases") { add("https://main.preview.pages.dev") }
    })

    @Test fun connectVerifiesBothEndpointsBeforeSavingAndDisconnectClearsOnlyItsSecrets(): Unit = runBlocking {
        val secrets = MemorySecrets().apply { put("OTHER_SECRET", "keep") }
        val fake = FakeTransport { call, _ ->
            assertEquals("GET", call.request.method)
            assertEquals("Bearer $token", call.authorization)
            assertFalse(secrets.values.containsKey(PagesPublisher.TOKEN_SECRET))
            when (call.path) {
                "/user/tokens/verify" -> ok(buildJsonObject { put("status", "active") })
                "/accounts/$account" -> ok(buildJsonObject { put("name", "My Account") })
                else -> kotlin.error("Unexpected path ${call.path}")
            }
        }
        val publisher = PagesPublisher(Publications(root()), secrets, fake)
        assertFalse(publisher.connected())
        assertEquals("My Account", publisher.connect(" $account ", " $token ").getOrThrow())
        assertEquals(2, fake.calls.size)
        assertTrue(publisher.connected())
        assertEquals(account, secrets.values[PagesPublisher.ACCOUNT_SECRET])
        publisher.disconnect()
        assertFalse(publisher.connected())
        assertEquals(mapOf("OTHER_SECRET" to "keep"), secrets.values)
    }

    @Test fun failedVerificationPreservesExistingConnectionAndCancellationPropagates(): Unit = runBlocking {
        val secrets = credentials()
        val original = secrets.values.toMap()
        val fake = FakeTransport { _, _ -> error(403, "No account access", 9109) }
        val publisher = PagesPublisher(Publications(root()), secrets, fake)
        val result = publisher.connect(account, "new-token")
        assertTrue(result.isFailure)
        val failure = result.exceptionOrNull() as PagesException
        assertEquals(403, failure.status)
        assertEquals("9109", failure.code)
        assertEquals("No account access", failure.message)
        assertEquals(original, secrets.values)
        assertTrue(publisher.connect("bad/id", "key").isFailure)
        assertEquals(1, fake.calls.size)
        val cancelled = PagesPublisher(Publications(root()), secrets, FakeTransport { _, _ -> throw CancellationException() })
        assertFailsWith<CancellationException> { cancelled.connect(account, token) }
    }

    @Test fun inactiveTokenAndAccountFailureDoNotPersistCredentials(): Unit = runBlocking {
        val secrets = MemorySecrets()
        var active = false
        val fake = FakeTransport { call, _ ->
            if (call.path == "/user/tokens/verify") ok(buildJsonObject { put("status", if (active) "active" else "expired") })
            else error(403, "Account access denied")
        }
        val publisher = PagesPublisher(Publications(root()), secrets, fake)
        assertTrue(publisher.connect(account, token).isFailure)
        assertEquals(1, fake.calls.size)
        active = true
        assertTrue(publisher.connect(account, token).isFailure)
        assertEquals(3, fake.calls.size)
        assertTrue(secrets.values.isEmpty())
    }

    @Test fun aSecretWriteFailureRestoresThePreviousConnection(): Unit = runBlocking {
        val memory = credentials()
        val original = memory.values.toMap()
        val secrets = object : PagesPublisher.Secrets {
            override fun get(name: String) = memory.get(name)
            override fun remove(name: String) = memory.remove(name)
            override fun put(name: String, value: String) {
                if (name == PagesPublisher.TOKEN_SECRET && value == "new-token") throw IOException("Cannot save credential")
                memory.put(name, value)
            }
        }
        val fake = FakeTransport { call, _ ->
            if (call.path.endsWith("/verify")) ok(buildJsonObject { put("status", "active") })
            else ok(buildJsonObject { put("name", "New Account") })
        }
        val publisher = PagesPublisher(Publications(root()), secrets, fake)
        assertTrue(publisher.connect("b".repeat(32), "new-token").isFailure)
        assertEquals(original, memory.values)
        assertTrue(publisher.connected())
    }

    @Test fun directUploadBodiesAuthManifestAndRepublishUseTheSameProject(): Unit = runBlocking {
        val root = root()
        val artifact = root.resolve("report.html").apply { writeText("<link href='style.css'><h1>Hello</h1>") }
        root.resolve("style.css").writeText("h1 { color: red }")
        val files = PagesPublisher.selectFiles(artifact)
        val expectedHashes = files.associate { it.path to PagesPublisher.assetKey(it.read(), it.extension) }
        var projectExists = false
        var missing = true
        val fake = FakeTransport { call, _ ->
            val assets = call.path.startsWith("/pages/assets/")
            assertEquals(if (assets) "Bearer upload-jwt" else "Bearer $token", call.authorization)
            when {
                call.path == "/accounts/$account/pages/projects" -> {
                    assertEquals("POST", call.request.method)
                    assertEquals("main", call.json.jsonObject["production_branch"]?.jsonPrimitive?.content)
                    projectExists = true
                    ok()
                }
                call.path.endsWith("/upload-token") -> ok(buildJsonObject { put("jwt", "upload-jwt") })
                call.path == "/pages/assets/check-missing" -> {
                    assertEquals(expectedHashes.values.toSet(), call.json.jsonObject.getValue("hashes").jsonArray.map { it.jsonPrimitive.content }.toSet())
                    ok(JsonArray(if (missing) expectedHashes.values.map { JsonPrimitive(it) } else emptyList()))
                }
                call.path == "/pages/assets/upload" -> {
                    val entries = call.json.jsonArray
                    assertEquals(2, entries.size)
                    for (entry in entries) {
                        val obj = entry.jsonObject
                        val key = obj.getValue("key").jsonPrimitive.content
                        val file = files.single { expectedHashes[it.path] == key }
                        assertEquals(Base64.getEncoder().encodeToString(file.read()), obj.getValue("value").jsonPrimitive.content)
                        assertEquals(true, obj.getValue("base64").jsonPrimitive.boolean)
                        assertEquals(file.contentType, obj.getValue("metadata").jsonObject.getValue("contentType").jsonPrimitive.content)
                    }
                    ok()
                }
                call.path == "/pages/assets/upsert-hashes" -> {
                    assertEquals(expectedHashes.values.toSet(), call.json.jsonObject.getValue("hashes").jsonArray.map { it.jsonPrimitive.content }.toSet())
                    ok()
                }
                call.path.endsWith("/deployments") -> {
                    assertTrue(call.request.body!!.contentType().toString().startsWith("multipart/form-data;"))
                    assertTrue("name=\"branch\"\r\n\r\nmain\r\n" in call.body)
                    val manifest = call.body.substringAfter("name=\"manifest\"\r\n\r\n").substringBefore("\r\n--")
                    assertEquals(expectedHashes, Json.parseToJsonElement(manifest).jsonObject.mapValues { it.value.jsonPrimitive.content })
                    deployment()
                }
                else -> if (projectExists) ok() else error(404, "No project")
            }
        }
        val publisher = PagesPublisher(Publications(root), credentials(), fake, now = { 1234 })
        val progress = mutableListOf<Progress>()
        val first = publisher.publish(artifact) { progress += it }
        assertEquals("https://${first.projectName}.pages.dev", first.publicUrl)
        assertEquals("https://deployment-1.pages.dev", first.lastDeploymentUrl)
        assertEquals("deployment-1", first.lastDeploymentId)
        assertEquals(1234L, first.publishedAt)
        assertEquals(2, first.fileCount)
        assertEquals(files.sumOf { it.size }, first.bytes)
        assertEquals(first, publisher.publicationFor(artifact))
        assertEquals(Progress.Stage.SCANNING, progress.first().stage)
        assertEquals(Progress.Stage.COMPLETE, progress.last().stage)
        missing = false
        val second = publisher.publish(artifact) {}
        assertEquals(first.projectName, second.projectName)
        assertEquals(1, fake.calls.count { it.path == "/accounts/$account/pages/projects" })
        assertEquals(1, fake.calls.count { it.path == "/pages/assets/upload" })
        assertEquals(2, fake.calls.count { it.path.endsWith("/deployments") })
        assertTrue(fake.calls.filter { it.path.contains("/projects/") }.all { it.path.startsWith("/accounts/$account/pages/projects/${first.projectName}") })
    }

    @Test fun expiredJwtIsRefreshedForEveryAssetEndpoint(): Unit = runBlocking {
        for (expiredPath in listOf("check-missing", "upload", "upsert-hashes")) {
            val root = root()
            val artifact = root.resolve("$expiredPath.html").apply { writeText("hello") }
            var jwtRequests = 0
            var expired = false
            val fake = FakeTransport { call, _ ->
                when {
                    call.path.endsWith("/upload-token") -> ok(buildJsonObject { put("jwt", "jwt-${++jwtRequests}") })
                    call.path.endsWith("/$expiredPath") && !expired -> {
                        expired = true
                        error(401, "Expired JWT")
                    }
                    call.path.endsWith("/check-missing") -> ok(call.json.jsonObject.getValue("hashes"))
                    call.path.endsWith("/deployments") -> deployment()
                    else -> ok()
                }
            }
            val publisher = PagesPublisher(Publications(root), credentials(), fake, backoff = {})
            publisher.publish(artifact) {}
            assertEquals(2, jwtRequests)
            val retried = fake.calls.filter { it.path.endsWith("/$expiredPath") }
            assertEquals(2, retried.size)
            assertEquals("Bearer jwt-1", retried[0].authorization)
            assertEquals("Bearer jwt-2", retried[1].authorization)
            assertEquals(retried[0].body, retried[1].body)
        }
    }

    @Test fun bucketsRetryThreeTimesWithBackoffAndNeverDeployOnFailure(): Unit = runBlocking {
        val root = root()
        val artifact = root.resolve("report.html").apply { writeText("hello") }
        val waits = mutableListOf<Long>()
        var failUploads = true
        val fake = FakeTransport { call, _ ->
            when {
                call.path.endsWith("/upload-token") -> ok(buildJsonObject { put("jwt", "private-jwt") })
                call.path.endsWith("/check-missing") -> ok(call.json.jsonObject.getValue("hashes"))
                call.path.endsWith("/upload") && failUploads -> error(503, "Try later")
                call.path.endsWith("/deployments") -> deployment()
                else -> ok()
            }
        }
        val publisher = PagesPublisher(Publications(root), credentials(), fake, backoff = { waits += it })
        val failure = assertFailsWith<PagesException> { publisher.publish(artifact) {} }
        assertEquals(503, failure.status)
        assertEquals(listOf(500L, 1000L), waits)
        assertEquals(3, fake.calls.count { it.path.endsWith("/upload") })
        assertTrue(fake.calls.none { it.path.endsWith("/deployments") || it.path.endsWith("/upsert-hashes") })
        val firstProjectPath = fake.calls.first().path
        failUploads = false
        val publication = publisher.publish(artifact) {}
        assertEquals("/accounts/$account/pages/projects/${publication.projectName}", firstProjectPath)
    }

    @Test fun transientUploadFailuresCanRecoverOnTheLastAttempt(): Unit = runBlocking {
        val root = root()
        val artifact = root.resolve("retry.html").apply { writeText("hello") }
        var uploads = 0
        val waits = mutableListOf<Long>()
        val fake = FakeTransport { call, _ ->
            when {
                call.path.endsWith("/upload-token") -> ok(buildJsonObject { put("jwt", "jwt") })
                call.path.endsWith("/check-missing") -> ok(call.json.jsonObject.getValue("hashes"))
                call.path.endsWith("/upload") -> if (++uploads < 3) error(503, "Retry") else ok()
                call.path.endsWith("/deployments") -> deployment()
                else -> ok()
            }
        }
        val publisher = PagesPublisher(Publications(root), credentials(), fake, backoff = { waits += it })
        assertEquals("deployment-1", publisher.publish(artifact) {}.lastDeploymentId)
        assertEquals(3, uploads)
        assertEquals(listOf(500L, 1000L), waits)
    }

    @Test fun identicalAssetsUploadOnceButKeepEveryManifestPath(): Unit = runBlocking {
        val root = root()
        val artifact = root.resolve("duplicates.html").apply { writeText("<link href='a.css'><link href='b.css'>") }
        root.resolve("a.css").writeText("identical")
        root.resolve("b.css").writeText("identical")
        val fake = FakeTransport { call, _ ->
            when {
                call.path.endsWith("/upload-token") -> ok(buildJsonObject { put("jwt", "jwt") })
                call.path.endsWith("/check-missing") -> {
                    assertEquals(2, call.json.jsonObject.getValue("hashes").jsonArray.size)
                    ok(call.json.jsonObject.getValue("hashes"))
                }
                call.path.endsWith("/upload") -> { assertEquals(2, call.json.jsonArray.size); ok() }
                call.path.endsWith("/deployments") -> {
                    val manifest = call.body.substringAfter("name=\"manifest\"\r\n\r\n").substringBefore("\r\n--")
                    val entries = Json.parseToJsonElement(manifest).jsonObject
                    assertEquals(setOf("/a.css", "/b.css", "/index.html"), entries.keys)
                    assertEquals(entries["/a.css"], entries["/b.css"])
                    deployment()
                }
                else -> ok()
            }
        }
        val publisher = PagesPublisher(Publications(root), credentials(), fake)
        assertEquals(3, publisher.publish(artifact) {}.fileCount)
    }

    @Test fun changedFilesAreNotUploadedUnderAnOldHash(): Unit = runBlocking {
        val root = root()
        val artifact = root.resolve("changed.html").apply { writeText("hello") }
        val fake = FakeTransport { call, _ ->
            when {
                call.path.endsWith("/upload-token") -> ok(buildJsonObject { put("jwt", "jwt") })
                call.path.endsWith("/check-missing") -> {
                    artifact.writeText("world") // Same byte count, different content.
                    ok(call.json.jsonObject.getValue("hashes"))
                }
                else -> ok()
            }
        }
        val publisher = PagesPublisher(Publications(root), credentials(), fake)
        assertFailsWith<IllegalArgumentException> { publisher.publish(artifact) {} }
        assertTrue(fake.calls.none { it.path.endsWith("/upload") || it.path.endsWith("/deployments") })
    }

    @Test fun successFalseAndMalformedResponsesAreTypedFailures(): Unit = runBlocking {
        val root = root()
        val artifact = root.resolve("errors.html").apply { writeText("hello") }
        for (response in listOf(
            PagesResponse(200, """{"success":false,"errors":[{"code":1001,"message":"First error"},{"code":1002,"message":"Second error"}]}"""),
            PagesResponse(200, "not json"),
            PagesResponse(302, """{"success":true}"""),
            PagesResponse(200, """{"success":{}}"""),
        )) {
            val publisher = PagesPublisher(Publications(root), credentials(), FakeTransport { _, _ -> response })
            val failure = assertFailsWith<PagesException> { publisher.publish(artifact) {} }
            assertEquals(response.status, failure.status)
            if ("First error" in response.body) {
                assertEquals("1001", failure.code)
                assertEquals("First error", failure.message)
            }
        }
    }

    @Test fun takeDownDeletesProjectAndRemovesRecordEvenOn404(): Unit = runBlocking {
        val root = root()
        val artifact = root.resolve("report.html")
        val store = Publications(root)
        val saved = store.reserve(artifact, account) { "umbra-report-abc123" }.copy(lastDeploymentId = "deployment")
        store.save(saved)
        val fake = FakeTransport { call, _ ->
            assertEquals("DELETE", call.request.method)
            assertEquals("/accounts/$account/pages/projects/umbra-report-abc123", call.path)
            assertEquals("Bearer $token", call.authorization)
            error(404, "Already deleted")
        }
        val publisher = PagesPublisher(store, credentials(), fake)
        publisher.takeDown(saved)
        assertNull(publisher.publicationFor(artifact))
        publisher.takeDown(saved)
        assertEquals(2, fake.calls.size)
    }

    @Test fun failedDeletePreservesRecordAndAccountMismatchMakesNoRequest(): Unit = runBlocking {
        val root = root()
        val artifact = root.resolve("report.html")
        val store = Publications(root)
        val saved = store.reserve(artifact, account) { "umbra-report-abc123" }.copy(lastDeploymentId = "deployment")
        store.save(saved)
        val fake = FakeTransport { _, _ -> error(403, "Not allowed") }
        val publisher = PagesPublisher(store, credentials(), fake)
        assertFailsWith<PagesException> { publisher.takeDown(saved) }
        assertEquals(saved, publisher.publicationFor(artifact))
        assertFailsWith<IllegalArgumentException> { publisher.takeDown(saved.copy(accountId = "b".repeat(32))) }
        assertEquals(1, fake.calls.size)
    }

    @Test fun errorsRedactBearerSecretsAndInvalidArtifactsNeverUseNetwork(): Unit = runBlocking {
        val root = root()
        val secrets = credentials()
        val artifact = root.resolve("report.html").apply { writeText("hello") }
        val fake = FakeTransport { call, _ ->
            if (call.path.endsWith("/upload-token")) ok(buildJsonObject { put("jwt", "private-jwt") })
            else if (call.path.endsWith("/check-missing")) error(400, "Rejected $token and private-jwt")
            else ok()
        }
        val publisher = PagesPublisher(Publications(root), secrets, fake)
        val failure = assertFailsWith<PagesException> { publisher.publish(artifact) {} }
        assertFalse(token in failure.toString())
        assertFalse("private-jwt" in failure.toString())
        assertEquals("Rejected [redacted] and [redacted]", failure.message)
        val before = fake.calls.size
        assertFailsWith<IllegalArgumentException> { publisher.publish(root.resolve("missing.html")) {} }
        assertEquals(before, fake.calls.size)
        val io = PagesPublisher(Publications(root), secrets, FakeTransport { _, _ -> throw IOException("secret=$token") })
        val transportFailure = assertFailsWith<PagesException> { io.publish(artifact) {} }
        assertFalse(token in transportFailure.toString())
        assertNull(transportFailure.cause)
    }

    @Test fun disconnectDuringUploadPreventsDeployment(): Unit = runBlocking {
        val root = root()
        val artifact = root.resolve("report.html").apply { writeText("hello") }
        lateinit var publisher: PagesPublisher
        val fake = FakeTransport { call, _ ->
            when {
                call.path.endsWith("/upload-token") -> ok(buildJsonObject { put("jwt", "jwt") })
                call.path.endsWith("/check-missing") -> { publisher.disconnect(); ok(JsonArray(emptyList())) }
                else -> ok()
            }
        }
        publisher = PagesPublisher(Publications(root), credentials(), fake)
        assertFailsWith<IllegalStateException> { publisher.publish(artifact) {} }
        assertTrue(fake.calls.none { it.path.endsWith("/deployments") })
        assertFalse(publisher.connected())
    }
}
