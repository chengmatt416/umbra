package app.umbra.agent.publish

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.io.TempDir

class PagesFilesTest {
    @TempDir lateinit var temporary: Path
    private fun root() = temporary.toRealPath().toFile()
    private fun File.write(relative: String, value: String = ""): File = resolve(relative).apply { parentFile!!.mkdirs(); writeText(value) }
    private fun sized(file: File, bytes: Long) = RandomAccessFile(file, "rw").use { it.setLength(bytes) }

    @Test fun folderIncludesAllFilesAndBuildsSlashPrefixedManifest() {
        val root = root()
        root.write("index.html", "hello")
        root.write("assets/a.js", "js")
        root.write(".well-known/data.json", "{}")
        root.write("unused.bin", "unused")
        val files = PagesPublisher.selectFiles(root)
        assertEquals(listOf("/.well-known/data.json", "/assets/a.js", "/index.html", "/unused.bin"), files.map { it.path })
        val assets = files.map { PagesPublisher.Companion.Asset(it, PagesPublisher.assetKey(it.read(), it.extension)) }
        val manifest = PagesPublisher.manifest(assets)
        assertEquals(files.map { it.path }.toSet(), manifest.keys)
        assets.forEach { assertEquals(it.key, manifest[it.file.path]?.jsonPrimitive?.content) }
    }

    @Test fun singleHtmlFollowsHtmlAndCssReferencesRecursively() {
        val root = root()
        val html = root.write("report.html", """
            <link href="assets/main.css?v=1#fragment" rel="stylesheet">
            <img src="assets/pic%20one.png"><script src="assets/app.mjs"></script>
            <img src="https://example.com/remote.png"><a href="#section">Section</a>
            <style>.a { background: url('assets/inline.png') }</style>
            <div style="background: url(&quot;assets/style.png&quot;)"></div>
            <a href="other.html">Other</a><img src="missing.png">
        """.trimIndent())
        root.write("assets/main.css", """@import "nested/extra.css"; .x { background: url('../plus+image.svg'); src: url(font.woff2) }""")
        root.write("assets/nested/extra.css", """@import '../main.css'; .x { background: url('../pic%20one.png') }""")
        listOf("assets/pic one.png", "assets/app.mjs", "assets/inline.png", "assets/style.png", "assets/font.woff2", "plus+image.svg").forEach { root.write(it) }
        root.write("other.html", "<img src='assets/other.gif'>")
        root.write("assets/other.gif")
        root.write("unreferenced.txt", "private")
        val files = PagesPublisher.selectFiles(html)
        assertEquals(setOf("/index.html", "/assets/main.css", "/assets/nested/extra.css", "/assets/pic one.png", "/assets/app.mjs",
            "/assets/inline.png", "/assets/style.png", "/assets/font.woff2", "/plus+image.svg", "/other.html", "/assets/other.gif"), files.map { it.path }.toSet())
        assertEquals("html", files.single { it.path == "/index.html" }.extension)
    }

    @Test fun escapingReferencesAndBaseUrlsAreRefusedEvenWhenTargetsAreMissing() {
        val root = root()
        for (reference in listOf("../secret.txt", "%2e%2e/secret.txt", "assets/../../secret.txt", "/etc/passwd", "assets%5csecret.txt", "bad%00.txt")) {
            val html = root.write("report.html", "<img src='$reference'>")
            assertFailsWith<IllegalArgumentException>(reference) { PagesPublisher.selectFiles(html) }
        }
        val html = root.write("report.html", "<base href='https://example.com/'><img src='a.png'>")
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(html) }
    }

    @Test fun cssEscapesAreDecodedAndCommentsDoNotAddAssets() {
        val root = root()
        val html = root.write("report.html", "<link href='style.css'><a href='./'>Home</a>")
        root.write("style.css", """
            /* url(../outside.txt) */
            .text::before { content: "url(../not-an-asset.txt)" }
            .a { background: url(my\ image.png) }
            .b { background: url("image\29 .png") }
            .c { background: url('quo\'te.png') }
        """.trimIndent())
        root.write("my image.png")
        root.write("image).png")
        root.write("quo'te.png")
        assertEquals(setOf("/index.html", "/style.css", "/my image.png", "/image).png", "/quo'te.png"),
            PagesPublisher.selectFiles(html).map { it.path }.toSet())
    }

    @Test fun entryFilesDirectoriesAndReferencedSymlinksAreRefused() {
        val root = root()
        root.write("index.html")
        val external = root.write("outside.txt", "secret")
        val site = root.resolve("site").apply { mkdirs() }
        site.write("index.html")
        Files.createSymbolicLink(site.resolve("linked.txt").toPath(), external.toPath())
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(site) }
        Files.createSymbolicLink(root.resolve("alias.html").toPath(), root.resolve("index.html").toPath())
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(root.resolve("alias.html")) }
        val html = root.write("report.html", "<img src='site/linked.txt'>")
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(html) }
        Files.createSymbolicLink(root.resolve("alias").toPath(), site.toPath())
        html.writeText("<img src='alias/not-present.txt'>")
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(html) }
        html.writeText("<img src='alias/../outside.txt'>")
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(html) }
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(root.resolve("alias")) }
    }

    @Test fun rootIndexAndHtmlExtensionAreRequired() {
        val root = root()
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(root) }
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(root.write("report.txt")) }
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(root.resolve("missing.html")) }
        assertEquals("/index.html", PagesPublisher.selectFiles(root.write("REPORT.HTML")).single().path)
    }

    @Test fun exactFileAndTotalByteLimitsAreAcceptedButExcessIsRefused() {
        val root = root()
        root.write("index.html")
        val large = root.write("large.bin")
        sized(large, PagesPublisher.MAX_FILE_BYTES)
        assertEquals(PagesPublisher.MAX_FILE_BYTES, PagesPublisher.selectFiles(root).sumOf { it.size })
        sized(large, PagesPublisher.MAX_FILE_BYTES + 1)
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(root) }
        sized(large, PagesPublisher.MAX_FILE_BYTES)
        for (i in 1..3) sized(root.write("large$i.bin"), PagesPublisher.MAX_FILE_BYTES)
        assertEquals(PagesPublisher.MAX_TOTAL_BYTES, PagesPublisher.selectFiles(root).sumOf { it.size })
        root.write("extra.bin", "x")
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(root) }
    }

    @Test fun exactFileCountLimitIsAcceptedAndExcessIsRefused() {
        val root = root()
        root.write("index.html")
        repeat(999) { root.write("asset$it.bin") }
        assertEquals(1000, PagesPublisher.selectFiles(root).size)
        root.write("excess.bin")
        assertFailsWith<IllegalArgumentException> { PagesPublisher.selectFiles(root) }
    }

    @Test fun generatedNamesAreValidBoundedAndRandom() {
        for (source in listOf("My Report.HTML", "!!!.html", "a".repeat(150) + ".html", "中文.html", "A--B__C.html")) {
            val names = (1..20).map { PagesPublisher.projectName(File(source)) }
            names.forEach {
                assertTrue(it.length <= 58)
                assertTrue(Regex("umbra-[a-z0-9-]+-[a-z0-9]{6}").matches(it), it)
                assertTrue(!it.contains("--"))
            }
            assertEquals(names.size, names.toSet().size)
        }
        val directory = root().resolve("Folder.Name").apply { mkdir() }
        assertTrue(PagesPublisher.projectName(directory).startsWith("umbra-folder-name-"))
    }

    @Test fun hashesUseBase64FollowedByExtensionAndBucketsHonorBothCaps() {
        val content = "asset".toByteArray()
        val expected = Blake3.hash((Base64.getEncoder().encodeToString(content) + "css").toByteArray()).take(16)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, PagesPublisher.assetKey(content, "css"))
        assertNotEquals(PagesPublisher.assetKey(content, "css"), PagesPublisher.assetKey(content, "js"))
        val root = root().toPath()
        fun asset(size: Long) = PagesPublisher.Companion.Asset(PagesPublisher.Companion.SiteFile(root, root.resolve("file.bin"), "/file.bin", size), "key")
        val largest = asset(PagesPublisher.MAX_FILE_BYTES)
        val buckets = PagesPublisher.buckets(listOf(largest, largest, asset(1)))
        assertEquals(listOf(1, 2), buckets.map { it.size })
        assertEquals(listOf(2000, 1), PagesPublisher.buckets(List(2001) { asset(0) }).map { it.size })
        val exact = asset(PagesPublisher.MAX_BUCKET_BYTES / 4 * 3)
        assertEquals(1, PagesPublisher.buckets(listOf(exact)).size)
        assertEquals(2, PagesPublisher.buckets(listOf(exact, asset(1))).size)
        assertTrue(PagesPublisher.buckets(emptyList()).isEmpty())
    }

    @Test fun allRequestedContentTypesHaveMappings() {
        val expected = mapOf("html" to "text/html", "css" to "text/css", "js" to "application/javascript", "mjs" to "application/javascript",
            "json" to "application/json", "svg" to "image/svg+xml", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp", "avif" to "image/avif", "ico" to "image/x-icon", "woff" to "font/woff",
            "woff2" to "font/woff2", "ttf" to "font/ttf", "txt" to "text/plain", "xml" to "application/xml", "wasm" to "application/wasm",
            "mp4" to "video/mp4", "webm" to "video/webm", "mp3" to "audio/mpeg", "pdf" to "application/pdf", "unknown" to "application/octet-stream")
        expected.forEach { (extension, type) -> assertEquals(type, PagesPublisher.contentType(extension.uppercase(Locale.ROOT))) }
    }
}
