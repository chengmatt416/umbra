package app.umbra.agent.ui.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

internal fun Node.local() = localName ?: nodeName.substringAfter(':')
internal fun Node.children(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
internal fun Node.descendants(name: String): List<Element> = buildList {
    var count = 0
    fun visit(node: Node, depth: Int) {
        require(depth < 128 && ++count <= 100_000)
        node.children().forEach { if (it.local() == name) add(it); visit(it, depth + 1) }
    }
    visit(this@descendants, 0)
}
internal fun Element.attribute(name: String): String = (0 until attributes.length).map { attributes.item(it) }
    .firstOrNull { it.local() == name }?.nodeValue.orEmpty()
internal fun Node.runs(): String = buildString {
    fun visit(node: Node) {
        node.children().forEach { child -> when (child.local()) {
            "t" -> append(child.textContent)
            "tab" -> append('\t')
            "br", "cr" -> append('\n')
            else -> visit(child)
        } }
    }
    visit(this@runs)
}

internal object OfficeXml {
    fun parse(bytes: ByteArray): Document {
        // Office parts have no DTD; refusing one also works on Android's XML implementation.
        require(!bytes.toString(Charsets.UTF_8).contains("<!DOCTYPE", ignoreCase = true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        }.parse(ByteArrayInputStream(bytes)).also { it.descendants("__validate_depth__") }
    }

    fun bytes(document: Document): ByteArray = ByteArrayOutputStream().also { out ->
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(out))
    }.toByteArray()

    fun text(parent: Element, namespace: String, qualifiedName: String, text: String): Element =
        parent.ownerDocument.createElementNS(namespace, qualifiedName).also {
            it.setAttributeNS("http://www.w3.org/XML/1998/namespace", "xml:space", "preserve")
            it.textContent = text
            parent.appendChild(it)
        }
}

/** Copy untouched local ZIP records verbatim, including compressed bytes and data descriptors. */
internal class OfficeArchive(private val original: ByteArray) {
    val parts: Map<String, ByteArray>
    private val end: Int
    private val central: Int
    private val records: List<Record>
    private data class Record(val name: String, val offset: Int, val header: ByteArray)

    init {
        if (original.size > MAX_FILE_BYTES) throw FileTooLarge()
        end = (original.size - 22 downTo maxOf(0, original.size - 65557)).firstOrNull {
            original.i32(it) == 0x06054b50 && it + 22 + original.u16(it + 20) == original.size
        } ?: error("Invalid ZIP")
        require(original.u16(end + 4) == 0 && original.u16(end + 6) == 0)
        val count = original.u16(end + 10)
        require(count == original.u16(end + 8) && count <= 4000)
        central = original.i32(end + 16)
        require(central >= 0 && central <= end && original.i32(end + 12) == end - central)
        var position = central
        records = List(count) {
            require(original.i32(position) == 0x02014b50)
            val length = 46 + original.u16(position + 28) + original.u16(position + 30) + original.u16(position + 32)
            require(position + length <= end && original.u16(position + 34) == 0)
            require(original.u16(position + 8) and 1 == 0)
            val name = original.copyOfRange(position + 46, position + 46 + original.u16(position + 28)).toString(Charsets.UTF_8)
            val offset = original.i32(position + 42)
            require(offset >= 0 && offset < central && original.i32(offset) == 0x04034b50)
            Record(name, offset, original.copyOfRange(position, position + length)).also { position += length }
        }
        require(position == end && records.map { it.name }.distinct().size == count && records.map { it.offset }.distinct().size == count)
        var expanded = 0
        parts = linkedMapOf<String, ByteArray>().apply {
            ZipInputStream(ByteArrayInputStream(original)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(size < 4000 && entry.name !in this)
                    val data = zip.readNBytes(MAX_FILE_BYTES - expanded + 1)
                    expanded += data.size
                    if (expanded > MAX_FILE_BYTES) throw FileTooLarge()
                    put(entry.name, data)
                }
            }
        }
        require(parts.keys == records.map { it.name }.toSet())
    }

    fun xml(path: String) = OfficeXml.parse(parts[path] ?: error("Missing Office part: $path"))

    fun rewrite(path: String, document: Document): ByteArray = rewrite(mapOf(path to OfficeXml.bytes(document)))

    fun rewrite(changes: Map<String, ByteArray>): ByteArray {
        require(changes.keys.all { it in parts })
        val out = ByteArrayOutputStream()
        val updated = mutableMapOf<String, ByteArray>()
        val sorted = records.sortedBy { it.offset }
        out.write(original, 0, sorted.firstOrNull()?.offset ?: central)
        sorted.forEachIndexed { index, record ->
            val header = record.header.copyOf()
            header.put32(42, out.size())
            val data = changes[record.name]
            if (data == null) {
                val next = sorted.getOrNull(index + 1)?.offset ?: central
                out.write(original, record.offset, next - record.offset)
            } else {
                val compressor = Deflater(Deflater.DEFAULT_COMPRESSION, true)
                val compressed = try {
                    ByteArrayOutputStream().also { buffer -> DeflaterOutputStream(buffer, compressor).use { it.write(data) } }.toByteArray()
                } finally { compressor.end() }
                val crc = CRC32().apply { update(data) }.value.toInt()
                val flags = header.u16(8) and 8.inv()
                header.put16(8, flags); header.put16(10, 8)
                header.put32(16, crc); header.put32(20, compressed.size); header.put32(24, data.size)
                val localLength = 30 + original.u16(record.offset + 26) + original.u16(record.offset + 28)
                val local = original.copyOfRange(record.offset, record.offset + localLength)
                local.put16(6, flags); local.put16(8, 8)
                local.put32(14, crc); local.put32(18, compressed.size); local.put32(22, data.size)
                out.write(local); out.write(compressed)
            }
            updated[record.name] = header
        }
        val offset = out.size()
        records.forEach { out.write(updated.getValue(it.name)) }
        val ending = original.copyOfRange(end, original.size)
        ending.put32(12, out.size() - offset); ending.put32(16, offset)
        out.write(ending)
        return out.toByteArray().also { require(it.size <= MAX_FILE_BYTES) }
    }
}

internal fun ByteArray.u16(offset: Int): Int {
    require(offset >= 0 && offset + 2 <= size)
    return (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
}
internal fun ByteArray.i32(offset: Int): Int = u16(offset) or (u16(offset + 2) shl 16)
internal fun ByteArray.put16(offset: Int, value: Int) { this[offset] = value.toByte(); this[offset + 1] = (value ushr 8).toByte() }
internal fun ByteArray.put32(offset: Int, value: Int) { put16(offset, value); put16(offset + 2, value ushr 16) }
