package app.umbra.runtime.linux

import app.umbra.platform.api.BackendException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RootFileSafetyLiveTest {
    private var runtime: RootChrootRuntime? = null

    private suspend fun guest(): RootChrootRuntime = (runtime ?: LiveGuest.installed().also { runtime = it }).also { it.mount() }

    @AfterAll
    fun unmount() { runtime?.let { runBlocking { it.unmount() } } }

    @Test
    fun anAgentSymlinkCannotTurnAConfigWriteIntoARootOverwrite(): Unit = runBlocking {
        val g = guest()
        val home = "/home/agent/.umbra-write-safety"
        val victim = "/etc/umbra-write-safety-fixture"
        try {
            assertTrue(g.exec(listOf("bash", "-c", "printf original > $victim; chmod 600 $victim"), asRoot = true).ok)
            assertTrue(g.exec(listOf("bash", "-c", "mkdir -p $home; ln -sf ${g.layout.rootfs}$victim $home/settings.json")).ok)
            g.writeFile("$home/settings.json", "new settings")
            assertEquals("original", g.exec(listOf("cat", victim), asRoot = true).stdout)
            assertEquals("0:600", g.exec(listOf("stat", "-c", "%u:%a", victim), asRoot = true).stdout.trim())
            assertEquals("new settings", g.readFile("$home/settings.json"))
            assertTrue(g.exec(listOf("test", "!", "-L", "$home/settings.json")).ok)
        } finally {
            g.exec(listOf("rm", "-rf", home, victim), asRoot = true)
        }
    }

    @Test
    fun systemInstallationRejectsASymlinkedOrAgentWritableParent(): Unit = runBlocking {
        val g = guest()
        val system = "/opt/umbra/write-safety-fixture"
        val home = "/home/agent/.umbra-install-safety"
        try {
            assertTrue(g.exec(listOf("mkdir", "-p", home)).ok)
            assertTrue(g.exec(listOf("bash", "-c", "mkdir -p $system; ln -s $home $system/linked; mkdir $system/writable; chmod 777 $system/writable"), asRoot = true).ok)
            assertFailsWith<BackendException> { g.installRootFile("$system/linked/tool", "unsafe") }
            assertFailsWith<BackendException> { g.installRootFile("$system/writable/tool", "unsafe") }
            assertTrue(g.exec(listOf("test", "!", "-e", "$home/tool")).ok)
            assertTrue(g.exec(listOf("test", "!", "-e", "$system/writable/tool")).ok)
            g.installRootFile("$system/safe/deep/tool", "#!/bin/sh\necho works\n", "755")
            assertEquals("works", g.exec(listOf("$system/safe/deep/tool")).stdout.trim())
            assertEquals("0:755", g.exec(listOf("stat", "-c", "%u:%a", "$system/safe/deep/tool")).stdout.trim())
        } finally {
            g.exec(listOf("rm", "-rf", system, home), asRoot = true)
        }
    }
}
