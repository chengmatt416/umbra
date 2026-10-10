package app.umbra.runtime.linux

/** A `ps -A -o PID,PPID` listing, to find everything that descends from one process. */
internal object ProcessTable {
    /**
     * Every descendant of [root] in [listing], children before their parents, [root] itself excluded.
     * Lines that are not two numbers (the header, noise) are ignored; a cycle in a corrupt listing
     * cannot make the walk loop.
     */
    fun descendantsOf(root: Long, listing: String): List<Long> {
        val children = HashMap<Long, MutableList<Long>>()
        for (line in listing.lineSequence()) {
            val columns = line.trim().split(Regex("\\s+"))
            if (columns.size < 2) continue
            val pid = columns[0].toLongOrNull() ?: continue
            val parent = columns[1].toLongOrNull() ?: continue
            if (pid != parent) children.getOrPut(parent) { ArrayList() } += pid
        }
        val seen = HashSet<Long>().apply { add(root) }
        val order = ArrayList<Long>()
        fun walk(parent: Long) {
            for (child in children[parent].orEmpty()) {
                if (seen.add(child)) {
                    walk(child)
                    order += child
                }
            }
        }
        walk(root)
        return order
    }
}
