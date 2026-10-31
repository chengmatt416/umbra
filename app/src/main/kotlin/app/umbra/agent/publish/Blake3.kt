package app.umbra.agent.publish

/** Unkeyed BLAKE3, with the default 32-byte digest. No native code or platform crypto provider. */
object Blake3 {
    fun hash(input: ByteArray): ByteArray = Hasher().update(input).digest()

    class Hasher {
        private val chunk = ByteArray(1024)
        private var length = 0
        private var chunks = 0L
        private val stack = mutableListOf<IntArray>()

        fun update(input: ByteArray, offset: Int = 0, count: Int = input.size - offset): Hasher {
            require(offset >= 0 && count >= 0 && offset <= input.size - count)
            var position = offset
            val end = offset + count
            while (position < end) {
                if (length == chunk.size) {
                    var cv = chunkOutput(chunk, length, chunks).chainingValue()
                    chunks++
                    var total = chunks
                    while (total and 1L == 0L) {
                        cv = parent(stack.removeAt(stack.lastIndex), cv).chainingValue()
                        total = total ushr 1
                    }
                    stack += cv
                    length = 0
                }
                val copied = minOf(end - position, chunk.size - length)
                input.copyInto(chunk, length, position, position + copied)
                position += copied
                length += copied
            }
            return this
        }

        /** Does not consume the hasher; subsequent updates and repeated digests are allowed. */
        fun digest(): ByteArray {
            var output = chunkOutput(chunk, length, chunks)
            for (i in stack.indices.reversed()) output = parent(stack[i], output.chainingValue())
            val words = compress(output.cv, output.block, 0, output.length, output.flags or ROOT)
            return ByteArray(32) { i -> (words[i / 4] ushr (8 * (i % 4))).toByte() }
        }
    }

    private data class Output(val cv: IntArray, val block: IntArray, val counter: Long, val length: Int, val flags: Int) {
        fun chainingValue(): IntArray = compress(cv, block, counter, length, flags).copyOf(8)
    }

    private fun chunkOutput(bytes: ByteArray, length: Int, counter: Long): Output {
        var cv = IV.copyOf()
        val blocks = maxOf(1, (length + 63) / 64)
        for (index in 0 until blocks) {
            val start = index * 64
            val size = minOf(64, length - start)
            val words = IntArray(16)
            for (i in 0 until size) words[i / 4] = words[i / 4] or ((bytes[start + i].toInt() and 255) shl (8 * (i % 4)))
            val flags = (if (index == 0) CHUNK_START else 0) or (if (index == blocks - 1) CHUNK_END else 0)
            val output = Output(cv, words, counter, size, flags)
            if (index == blocks - 1) return output
            cv = output.chainingValue()
        }
        error("Unreachable BLAKE3 chunk")
    }

    private fun parent(left: IntArray, right: IntArray): Output = Output(IV, left + right, 0, 64, PARENT)

    private fun compress(cv: IntArray, block: IntArray, counter: Long, length: Int, flags: Int): IntArray {
        val state = cv + intArrayOf(IV[0], IV[1], IV[2], IV[3], counter.toInt(), (counter ushr 32).toInt(), length, flags)
        var message = block
        repeat(7) { round ->
            mix(state, 0, 4, 8, 12, message[0], message[1])
            mix(state, 1, 5, 9, 13, message[2], message[3])
            mix(state, 2, 6, 10, 14, message[4], message[5])
            mix(state, 3, 7, 11, 15, message[6], message[7])
            mix(state, 0, 5, 10, 15, message[8], message[9])
            mix(state, 1, 6, 11, 12, message[10], message[11])
            mix(state, 2, 7, 8, 13, message[12], message[13])
            mix(state, 3, 4, 9, 14, message[14], message[15])
            if (round != 6) message = IntArray(16) { message[PERMUTATION[it]] }
        }
        return IntArray(16) { i -> if (i < 8) state[i] xor state[i + 8] else state[i] xor cv[i - 8] }
    }

    private fun mix(v: IntArray, a: Int, b: Int, c: Int, d: Int, x: Int, y: Int) {
        v[a] = v[a] + v[b] + x
        v[d] = Integer.rotateRight(v[d] xor v[a], 16)
        v[c] += v[d]
        v[b] = Integer.rotateRight(v[b] xor v[c], 12)
        v[a] = v[a] + v[b] + y
        v[d] = Integer.rotateRight(v[d] xor v[a], 8)
        v[c] += v[d]
        v[b] = Integer.rotateRight(v[b] xor v[c], 7)
    }

    private const val CHUNK_START = 1
    private const val CHUNK_END = 2
    private const val PARENT = 4
    private const val ROOT = 8
    private val IV = intArrayOf(0x6A09E667, 0xBB67AE85.toInt(), 0x3C6EF372, 0xA54FF53A.toInt(), 0x510E527F, 0x9B05688C.toInt(), 0x1F83D9AB, 0x5BE0CD19)
    private val PERMUTATION = intArrayOf(2, 6, 3, 10, 7, 0, 4, 13, 1, 11, 12, 5, 9, 14, 15, 8)
}
