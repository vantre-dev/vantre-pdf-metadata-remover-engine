package dev.striptools.strippdf.office

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

enum class OfficeKind(val extensions: Set<String>) {
    WORD(setOf("docx", "docm", "dotx", "dotm")),
    EXCEL(setOf("xlsx", "xlsm", "xltx", "xltm")),
    POWERPOINT(setOf("pptx", "pptm", "ppsx", "ppsm", "potx", "potm"));

    companion object {
        fun fromExtension(name: String): OfficeKind? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

/** Thrown for password-protected Office files, which are an encrypted OLE container, not a zip. */
class OfficeEncryptedException : Exception("Office file is password protected")

/** Thrown when the zip is not an Office Open XML package. */
class NotOfficeException(message: String) : Exception(message)

internal object OoxmlRel {
    const val OFFICE_DOCUMENT = "/officeDocument"
    const val CORE = "/metadata/core-properties"
    const val APP = "/extended-properties"
    const val CUSTOM = "/custom-properties"
    const val THUMBNAIL = "/metadata/thumbnail"
    const val PEOPLE = "/people"
    const val REVISION_INFO = "/revisionInfo"
    const val ATTACHED_TEMPLATE = "/attachedTemplate"
    const val MAIL_MERGE_SOURCE = "/mailMergeSource"
    const val MAIL_MERGE_HEADER = "/mailMergeHeaderSource"
    const val RELS_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
}

internal data class Relationship(
    val id: String,
    val type: String,
    val target: String,
    val external: Boolean,
    /** Package part the target resolves to (no leading slash); null for external targets. */
    val resolved: String?
)

/**
 * Read-only view over an OOXML zip. Small XML parts are loaded on demand; large parts
 * are streamed by callers through [open].
 */
internal class OoxmlPackage(file: File) : AutoCloseable {
    private val zip: ZipFile = try {
        ZipFile(file)
    } catch (e: java.util.zip.ZipException) {
        if (isOleContainer(file)) throw OfficeEncryptedException()
        throw NotOfficeException("Not a zip archive")
    }

    val entries: List<ZipEntry>
    private val names: Set<String>
    val rootRels: List<Relationship>
    val mainPart: String?
    val kind: OfficeKind

    init {
        try {
            entries = zip.entries().toList()
            // Zip-slip style names never occur in real Office files; refuse rather than rewrite them.
            if (entries.any { it.name.startsWith("/") || it.name.split('/').contains("..") }) throw NotOfficeException("Unsafe entry name")
            names = entries.mapTo(HashSet()) { it.name }
            if ("[Content_Types].xml" !in names) throw NotOfficeException("Missing [Content_Types].xml")
            rootRels = rels("")
            mainPart = rootRels.firstOrNull { it.type.endsWith(OoxmlRel.OFFICE_DOCUMENT) }?.resolved
            kind = when {
                mainPart == null -> throw NotOfficeException("No main document part")
                mainPart.startsWith("word/") -> OfficeKind.WORD
                mainPart.startsWith("xl/") -> OfficeKind.EXCEL
                mainPart.startsWith("ppt/") -> OfficeKind.POWERPOINT
                else -> throw NotOfficeException("Unknown main part $mainPart")
            }
        } catch (e: Exception) {
            zip.close()
            throw e
        }
    }

    fun has(name: String) = name in names
    fun entry(name: String): ZipEntry? = zip.getEntry(name)
    fun open(entry: ZipEntry) = zip.getInputStream(entry)
    fun bytes(name: String): ByteArray? = zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
    fun text(name: String): String? = bytes(name)?.let { decode(it) }

    fun rootTarget(typeSuffix: String): String? = rootRels.firstOrNull { it.type.endsWith(typeSuffix) }?.resolved

    /** Relationships owned by [sourcePart] ("" for the package root). */
    fun rels(sourcePart: String): List<Relationship> {
        val relsName = relsPathFor(sourcePart)
        val xml = text(relsName) ?: return emptyList()
        return parseRels(xml, sourcePart)
    }

    val relsParts: List<String> get() = entries.map { it.name }.filter { it.endsWith(".rels") }

    override fun close() = zip.close()

    companion object {
        fun decode(b: ByteArray): String = when {
            b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
            b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
            b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> String(b, 3, b.size - 3, Charsets.UTF_8)
            else -> String(b, Charsets.UTF_8)
        }

        fun isOleContainer(file: File): Boolean = runCatching {
            file.inputStream().use { s ->
                val h = ByteArray(8); s.read(h) == 8 &&
                    h.contentEquals(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
            }
        }.getOrDefault(false)

        fun relsPathFor(sourcePart: String): String {
            if (sourcePart.isEmpty()) return "_rels/.rels"
            val dir = sourcePart.substringBeforeLast('/', "")
            val name = sourcePart.substringAfterLast('/')
            return if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
        }

        /** Inverse of [relsPathFor]: the part a .rels file describes. */
        fun sourcePartFor(relsPath: String): String {
            if (relsPath == "_rels/.rels") return ""
            val dir = relsPath.substringBeforeLast("/_rels/", "").let { if (relsPath.startsWith("_rels/")) "" else it }
            val name = relsPath.substringAfterLast('/').removeSuffix(".rels")
            return if (dir.isEmpty()) name else "$dir/$name"
        }

        private val REL = Regex("""<(?:[\w.-]+:)?Relationship\b([^>]*?)/?>""")

        fun parseRels(xml: String, sourcePart: String): List<Relationship> =
            REL.findAll(xml).mapNotNull { m ->
                val tag = XmlTag.parse("<r ${m.groupValues[1].trim().removeSuffix("/")}/>")
                val id = tag.attr("Id") ?: return@mapNotNull null
                val target = tag.attr("Target") ?: return@mapNotNull null
                val external = tag.attr("TargetMode").equals("External", ignoreCase = true)
                Relationship(id, tag.attr("Type") ?: "", target, external, if (external) null else resolve(sourcePart, target))
            }.toList()

        fun resolve(sourcePart: String, target: String): String {
            val path = target.substringBefore('#')
            val parts = ArrayDeque<String>()
            if (!path.startsWith("/")) {
                sourcePart.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.forEach { parts.addLast(it) }
            }
            path.split('/').forEach { seg ->
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeLast()
                    else -> parts.addLast(java.net.URLDecoder.decode(seg.replace("+", "%2B"), "UTF-8"))
                }
            }
            return parts.joinToString("/")
        }

        /** Local file paths (drive letters, UNC shares, file: URIs) that reveal user names or machines. */
        fun isLocalPath(target: String): Boolean {
            val t = target.trim()
            return t.startsWith("file:", ignoreCase = true) ||
                Regex("""^[A-Za-z]:[\\/]""").containsMatchIn(t) ||
                t.startsWith("\\\\") ||
                t.startsWith("/Users/") || t.startsWith("/home/")
        }
    }
}
