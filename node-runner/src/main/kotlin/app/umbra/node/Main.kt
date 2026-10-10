package app.umbra.node

import app.umbra.core.nodes.Pairing
import app.umbra.core.nodes.Rsp
import app.umbra.core.nodes.fingerprint
import app.umbra.core.nodes.shortFingerprint
import java.io.File
import kotlin.system.exitProcess

/**
 * `umbra-node`: runs Umbra's jobs on a computer you own (architecture spec §10).
 *
 *     umbra-node serve [--port 47100] [--home ~/.umbra-node] [--pair]
 *     umbra-node phones
 *     umbra-node unpair <fingerprint prefix>
 *
 * `--pair` opens a ten-minute pairing window and prints a one-time code to enter on the phone. A paired phone can
 * run commands as this computer's user, in this node's jobs folder, each for no longer than its lease.
 */
fun main(args: Array<String>) {
    fun opt(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val home = NodeHome(File(opt("--home") ?: (System.getProperty("user.home") + "/.umbra-node")))
    when (args.firstOrNull() ?: "serve") {
        "serve" -> {
            home.ensureIdentity()
            val window = if ("--pair" in args) PairingWindow(Pairing.newCode(), System.currentTimeMillis() + 10 * 60_000) else null
            val server = NodeServer(home, opt("--port")?.toInt() ?: Rsp.DEFAULT_PORT, window)
            val port = server.start()
            println("umbra-node “${home.config.name}” listening on port $port")
            println("  node certificate ${shortFingerprint(home.certificate().fingerprint())}")
            println("  jobs folder ${home.jobsDir}")
            println("  paired phones: ${home.paired().size}")
            if (window != null) {
                println()
                println("  Pairing code: ${window.code}   (valid 10 minutes, one use, 5 attempts)")
                println("  On the phone: Distributed Nodes → Pair a node, this computer's address, port $port, and the code.")
                println("  The paired phone will be able to run commands as ${System.getProperty("user.name")} in ${home.jobsDir}.")
            }
            Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
            Thread.currentThread().join()
        }
        "phones" -> home.paired().forEach { println("${shortFingerprint(it.fingerprint)}  ${it.name}") }
        "evidence-decrypt" -> {
            val file = File(args.getOrNull(1) ?: run { System.err.println("which file?"); exitProcess(2) })
            val pass = System.console()?.readPassword("Passphrase: ") ?: System.`in`.bufferedReader().readLine()?.toCharArray() ?: exitProcess(2)
            val plain = runCatching { app.umbra.core.control.Evidence.open(file.readBytes(), pass) }.getOrElse {
                System.err.println("cannot open it: wrong passphrase, or the file was changed"); exitProcess(1)
            }
            System.out.write(plain); System.out.flush()
        }
        "unpair" -> {
            val prefix = args.getOrNull(1)?.replace(":", "")?.lowercase() ?: run { System.err.println("which phone? give a fingerprint prefix"); exitProcess(2) }
            val match = home.paired().filter { it.fingerprint.startsWith(prefix) }
            if (match.size != 1) { System.err.println("${match.size} phones match “$prefix”"); exitProcess(2) }
            home.removePaired(match.single().fingerprint)
            println("unpaired ${match.single().name}")
        }
        else -> { System.err.println("usage: umbra-node serve [--port N] [--home DIR] [--pair] | phones | unpair <prefix> | evidence-decrypt <file>"); exitProcess(2) }
    }
}
