package dev.striptools.strippdf.office

import java.io.Reader
import java.io.Writer

/**
 * Minimal streaming tokenizer for OOXML parts. Only start and empty-element tags are
 * surfaced; text, end tags, comments, CDATA and processing instructions pass through
 * byte-for-byte. Memory stays flat regardless of part size, which matters for
 * multi-hundred-MB document.xml / sheet parts.
 */
internal class XmlTag(val name: String, val attrs: MutableList<Pair<String, String>>, val selfClosing: Boolean) {
    /** Set by a transform to remove this tag; only honoured for empty elements so nesting stays intact. */
    var drop = false

    fun attr(name: String): String? = attrs.firstOrNull { it.first == name }?.second?.let(XmlText::unescape)
    val localName: String get() = name.substringAfter(':')

    fun set(name: String, value: String) {
        val i = attrs.indexOfFirst { it.first == name }
        val escaped = XmlText.escapeAttr(value)
        if (i >= 0) attrs[i] = name to escaped else attrs.add(name to escaped)
    }

    fun render(): String = buildString {
        append('<').append(name)
        attrs.forEach { (k, v) -> append(' ').append(k).append("=\"").append(v).append('"') }
        append(if (selfClosing) "/>" else ">")
    }

    companion object {
        private val ATTR = Regex("""([^\s=/>]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

        fun parse(raw: String): XmlTag {
            val body = raw.substring(1, raw.length - 1)
            val selfClosing = body.endsWith("/")
            val inner = if (selfClosing) body.dropLast(1) else body
            val nameEnd = inner.indexOfFirst { it.isWhitespace() }.let { if (it < 0) inner.length else it }
            val name = inner.substring(0, nameEnd)
            // Values keep their original escaping; double quotes inside single-quoted values are re-escaped.
            val attrs = ATTR.findAll(inner.substring(nameEnd)).map { m ->
                m.groupValues[1] to (m.groups[2]?.value ?: m.groupValues[3].replace("\"", "&quot;"))
            }.toMutableList()
            return XmlTag(name, attrs, selfClosing)
        }
    }
}

internal object XmlTags {

    /** Returns true when the tag was modified and must be re-rendered. */
    fun interface Transform { fun apply(tag: XmlTag): Boolean }

    fun rewrite(input: Reader, output: Writer, transform: Transform) =
        tokenize(input, object : TokenSink {
            override fun text(buf: CharArray, off: Int, len: Int) = output.write(buf, off, len)
            override fun startTag(raw: String) {
                val tag = XmlTag.parse(raw)
                output.write(
                    when {
                        !transform.apply(tag) -> raw
                        tag.drop && tag.selfClosing -> ""
                        else -> tag.render()
                    }
                )
            }
            override fun endTag(raw: String) = output.write(raw)
            override fun other(raw: String) = output.write(raw)
        }).also { output.flush() }

    fun scan(input: Reader, visit: (XmlTag) -> Unit) =
        tokenize(input, object : TokenSink {
            override fun text(buf: CharArray, off: Int, len: Int) = Unit
            override fun startTag(raw: String) = visit(XmlTag.parse(raw))
            override fun endTag(raw: String) = Unit
            override fun other(raw: String) = Unit
        })

    /** Receives every token in document order; concatenating them reproduces the input exactly. */
    interface TokenSink {
        fun text(buf: CharArray, off: Int, len: Int)
        /** Start or empty-element tag, raw. */
        fun startTag(raw: String)
        fun endTag(raw: String)
        /** Comment, CDATA, processing instruction, doctype. */
        fun other(raw: String)
    }

    fun tokenize(input: Reader, sink: TokenSink) {
        val buf = CharArray(64 * 1024)
        val tag = StringBuilder()
        // 0 = text, 1 = start tag, 2 = other markup (end tag / PI / comment / CDATA / doctype)
        var state = 0
        var quote = 0.toChar()
        var terminator = ">"
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            var textStart = 0
            var i = 0
            while (i < n) {
                val c = buf[i]
                when (state) {
                    0 -> if (c == '<') {
                        if (i > textStart) sink.text(buf, textStart, i - textStart)
                        tag.setLength(0); tag.append(c)
                        state = 1; quote = 0.toChar()
                    }
                    1 -> {
                        tag.append(c)
                        if (tag.length == 2 && (c == '/' || c == '?' || c == '!')) {
                            state = 2
                            terminator = if (c == '?') "?>" else ">"
                        } else if (quote != 0.toChar()) {
                            if (c == quote) quote = 0.toChar()
                        } else if (c == '"' || c == '\'') {
                            quote = c
                        } else if (c == '>') {
                            sink.startTag(tag.toString())
                            state = 0; textStart = i + 1
                        }
                    }
                    2 -> {
                        tag.append(c)
                        if (tag.length == 4 && tag.startsWith("<!--")) terminator = "-->"
                        if (tag.length == 9 && tag.startsWith("<![CDATA[")) terminator = "]]>"
                        if (c == '>' && tag.length >= terminator.length + 1 && tag.endsWith(terminator)) {
                            val raw = tag.toString()
                            if (raw.startsWith("</")) sink.endTag(raw) else sink.other(raw)
                            state = 0; textStart = i + 1
                        }
                    }
                }
                i++
            }
            if (state == 0 && n > textStart) sink.text(buf, textStart, n - textStart)
        }
        // Truncated markup at EOF: pass through rather than dropping bytes.
        if (state != 0) sink.other(tag.toString())
    }
}

internal object XmlText {
    fun unescape(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
            when (val e = m.groupValues[1]) {
                "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
                else -> runCatching {
                    val cp = if (e.startsWith("#x")) e.substring(2).toInt(16) else e.substring(1).toInt()
                    String(Character.toChars(cp))
                }.getOrDefault(m.value)
            }
        }
    }

    fun escapeAttr(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun escapeText(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Removes every element named [localName] (any prefix), including its content. */
    fun removeElements(xml: String, localName: String): String {
        val n = Regex.escape(localName)
        val selfClosing = Regex("""<(?:[\w.-]+:)?$n(?:\s[^>]*)?/>""")
        val paired = Regex("""<((?:[\w.-]+:)?$n)(?:\s[^>]*)?>.*?</\1\s*>""", RegexOption.DOT_MATCHES_ALL)
        return paired.replace(selfClosing.replace(xml, ""), "")
    }

    /** Text content of every element named [localName] (any prefix). */
    fun elementTexts(xml: String, localName: String): List<String> {
        val n = Regex.escape(localName)
        return Regex("""<((?:[\w.-]+:)?$n)(?:\s[^>]*)?>(.*?)</\1\s*>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { unescape(it.groupValues[2].replace(Regex("<[^>]*>"), "")).trim() }.toList()
    }

    /** Applies [transform] to every start tag of a small, fully loaded part. */
    fun rewriteString(xml: String, transform: XmlTags.Transform): String {
        val out = java.io.StringWriter(xml.length)
        XmlTags.rewrite(java.io.StringReader(xml), out, transform)
        return out.toString()
    }
}
