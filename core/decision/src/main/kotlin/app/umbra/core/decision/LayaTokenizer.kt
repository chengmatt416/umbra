package app.umbra.core.decision

import java.text.Normalizer
import java.util.regex.Pattern
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The byte-level BPE tokenizer of Laya's ModernBERT checkpoints, read from the checkpoint's `tokenizer.json`, with the
 * behaviour of Hugging Face `tokenizers` that Laya relies on (always `add_special_tokens=False`):
 *
 * 1. added tokens that are not normalized (`[CLS]`, `[SEP]`, `<|endoftext|>`, …) are split out of the raw text,
 *    leftmost-longest, honouring `lstrip`/`rstrip`;
 * 2. the rest is normalized (NFC) and the normalized added tokens (runs of spaces, `|||EMAIL_ADDRESS|||`, …) are split out;
 * 3. what remains is split by the GPT-2 pattern and each piece's UTF-8 bytes are merged by rank.
 *
 * Golden fixtures in the tests pin the ids against the Python tokenizer.
 */
class LayaTokenizer private constructor(
    private val vocab: Map<String, Int>,
    private val ranks: Map<String, Int>,
    private val raw: AddedTokens,
    private val normalized: AddedTokens,
    private val nfc: Boolean,
) {
    fun idOf(token: String): Int = vocab[token] ?: raw.idOf(token) ?: normalized.idOf(token)
        ?: throw IllegalArgumentException("token $token is not in the vocabulary")

    /** Token ids of [text], without special tokens around it. */
    fun encode(text: String): List<Int> {
        val out = ArrayList<Int>(text.length / 3 + 4)
        for ((piece, id) in raw.split(text)) {
            if (id != null) { out += id; continue }
            val norm = if (nfc) Normalizer.normalize(piece, Normalizer.Form.NFC) else piece
            for ((part, nid) in normalized.split(norm)) {
                if (nid != null) out += nid else model(part, out)
            }
        }
        return out
    }

    private fun model(text: String, out: MutableList<Int>) {
        val m = SPLIT.matcher(text)
        while (m.find()) {
            val piece = m.group()
            val word = buildString(piece.length * 2) { for (b in piece.toByteArray(Charsets.UTF_8)) append(BYTE_CHARS[b.toInt() and 0xff]) }
            out.addAll(bpe(word))
        }
    }

    private val cache = object : LinkedHashMap<String, List<Int>>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Int>>?) = size > 20_000
    }

    private fun bpe(word: String): List<Int> {
        synchronized(cache) { cache[word]?.let { return it } }
        var symbols: MutableList<String> = word.codePoints().toArray().mapTo(ArrayList(word.length)) { String(Character.toChars(it)) }
        while (symbols.size > 1) {
            var best = Int.MAX_VALUE
            var at = -1
            for (i in 0 until symbols.size - 1) {
                val rank = ranks[symbols[i] + " " + symbols[i + 1]] ?: continue
                if (rank < best) { best = rank; at = i }
            }
            if (at < 0) break
            val a = symbols[at]
            val b = symbols[at + 1]
            val merged = ArrayList<String>(symbols.size)
            var i = 0
            while (i < symbols.size) {
                if (i < symbols.size - 1 && symbols[i] == a && symbols[i + 1] == b) { merged += a + b; i += 2 }
                else { merged += symbols[i]; i++ }
            }
            symbols = merged
        }
        val ids = symbols.map { vocab[it] ?: throw IllegalStateException("byte-level symbol $it is not in the vocabulary") }
        synchronized(cache) { cache[word] = ids }
        return ids
    }

    private class Added(val content: String, val id: Int, val lstrip: Boolean, val rstrip: Boolean)

    /** Leftmost-longest matching of a set of added tokens, as `tokenizers`' AddedVocabulary does with aho-corasick. */
    private class AddedTokens(tokens: List<Added>) {
        private val byFirst: Map<Char, List<Added>> = tokens.groupBy { it.content[0] }.mapValues { (_, v) -> v.sortedByDescending { it.content.length } }
        private val byContent = tokens.associateBy { it.content }

        fun idOf(content: String) = byContent[content]?.id

        fun split(text: String): List<Pair<String, Int?>> {
            if (byFirst.isEmpty() || text.isEmpty()) return listOf(text to null)
            val out = ArrayList<Pair<String, Int?>>()
            var start = 0 // first character not yet emitted
            var i = 0
            while (i < text.length) {
                val match = byFirst[text[i]]?.firstOrNull { text.startsWith(it.content, i) }
                if (match == null) { i++; continue }
                var from = i
                var to = i + match.content.length
                if (match.lstrip) while (from > start && text[from - 1].isWhitespace()) from--
                if (match.rstrip) while (to < text.length && text[to].isWhitespace()) to++
                if (from > start) out += text.substring(start, from) to null
                out += text.substring(from, to) to match.id
                start = to
                i = to
            }
            if (start < text.length) out += text.substring(start) to null
            return out
        }
    }

    companion object {
        /** GPT-2's pre-tokenization pattern, as `tokenizers`' ByteLevel pre-tokenizer uses it (`use_regex: true`). */
        private val SPLIT: Pattern = Pattern.compile(
            "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+",
            Pattern.UNICODE_CHARACTER_CLASS,
        )

        /** GPT-2's reversible byte to printable character table. */
        private val BYTE_CHARS: CharArray = run {
            val printable = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toSet()
            var next = 256
            CharArray(256) { b -> if (b in printable) b.toChar() else (next++).toChar() }
        }

        fun fromJson(text: String): LayaTokenizer {
            val root = Json.parseToJsonElement(text).jsonObject
            val model = root["model"]!!.jsonObject
            require(model["type"]?.jsonPrimitive?.content == "BPE") { "only BPE tokenizers are supported" }
            require((root["pre_tokenizer"] as? JsonObject)?.get("type")?.jsonPrimitive?.content == "ByteLevel") { "only the ByteLevel pre-tokenizer is supported" }
            val normalizer = (root["normalizer"] as? JsonObject)?.get("type")?.jsonPrimitive?.content
            require(normalizer == null || normalizer == "NFC") { "unsupported normalizer $normalizer" }
            val vocab = HashMap<String, Int>(70_000).apply { model["vocab"]!!.jsonObject.forEach { (k, v) -> put(k, v.jsonPrimitive.int) } }
            val ranks = HashMap<String, Int>(70_000)
            model["merges"]!!.jsonArray.forEachIndexed { rank, m ->
                val key = when (m) {
                    is JsonArray -> m[0].jsonPrimitive.content + " " + m[1].jsonPrimitive.content
                    is JsonPrimitive -> m.content
                    else -> throw IllegalArgumentException("bad merge $m")
                }
                ranks.putIfAbsent(key, rank)
            }
            val added = (root["added_tokens"] as? JsonArray).orEmpty().map { it.jsonObject }.map {
                it["normalized"]!!.jsonPrimitive.boolean to Added(it["content"]!!.jsonPrimitive.content, it["id"]!!.jsonPrimitive.int,
                    it["lstrip"]?.jsonPrimitive?.boolean == true, it["rstrip"]?.jsonPrimitive?.boolean == true)
            }
            return LayaTokenizer(vocab, ranks, AddedTokens(added.filter { !it.first }.map { it.second }),
                AddedTokens(added.filter { it.first }.map { it.second }), normalizer == "NFC")
        }
    }
}
