package app.umbra.platform.shell

import java.util.concurrent.TimeUnit

/** Finding and killing the children of a process on runtimes that lack `ProcessHandle`. */
object ProcessTree {
    /** The handles of everything below [process] when the runtime has `Process.descendants()`, else null. */
    fun descendantsViaHandle(process: Process): List<ProcessHandle>? = try {
        process.descendants().toList()
    } catch (_: NoSuchMethodError) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** The pid of [process]: `Process.pid()` where it exists, otherwise the private field of the implementation. */
    fun pidOf(process: Process): Int? {
        try {
            return process.pid().toInt()
        } catch (_: NoSuchMethodError) {
        } catch (_: UnsupportedOperationException) {
        }
        for (name in listOf("pid", "id")) {
            try {
                val field = process.javaClass.getDeclaredField(name)
                field.isAccessible = true
                return (field.get(process) as Number).toInt()
            } catch (_: ReflectiveOperationException) {
            } catch (_: RuntimeException) {
            }
        }
        return null
    }

    /** Kills every descendant of [pid], deepest first, using the process table (`ps -A -o PID,PPID`). */
    fun killDescendantsOf(pid: Int) {
        val table = try {
            val ps = ProcessBuilder("ps", "-A", "-o", "PID,PPID").redirectErrorStream(true).start()
            ps.outputStream.close()
            val text = ps.inputStream.readBytes().toString(Charsets.UTF_8)
            ps.waitFor(5, TimeUnit.SECONDS)
            text
        } catch (_: Exception) {
            return
        }
        val children = HashMap<Int, MutableList<Int>>()
        for (line in table.lineSequence().drop(1)) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size >= 2) {
                val child = cols[0].toIntOrNull() ?: continue
                val parent = cols[1].toIntOrNull() ?: continue
                children.getOrPut(parent) { ArrayList() } += child
            }
        }
        val order = ArrayList<Int>()
        fun walk(p: Int) {
            for (c in children[p].orEmpty()) { walk(c); order += c }
        }
        walk(pid)
        for (victim in order) {
            try {
                ProcessBuilder("kill", "-9", victim.toString()).start().waitFor(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
    }
}
