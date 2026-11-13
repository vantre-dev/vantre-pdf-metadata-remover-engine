package dev.striptools.strippdf.office

import java.io.Writer

/**
 * One streaming pass over a WordprocessingML part (document, headers, footers, notes,
 * comments, styles, numbering, glossary):
 *
 * - always: author / initials anonymised, rsids and edit dates removed ([OfficeCleaner.wordAttributes]);
 * - [removeComments]: comment range markers and reference marks removed;
 * - [acceptRevisions]: tracked changes accepted the way Word's "Accept All" does. Inserted and
 *   moved-to content is kept without its wrapper, deleted and moved-from content is dropped,
 *   formatting-change history is dropped, deleted table rows are removed, and paragraphs whose
 *   whole content and mark were deleted disappear.
 *
 * Memory stays bounded: only the current paragraph / table row is buffered, and only in
 * accept mode.
 */
internal class WordPartProcessor(
    private val out: Writer,
    private val removeComments: Boolean,
    private val acceptRevisions: Boolean
) : XmlTags.TokenSink {

    private enum class Action { KEEP, UNWRAP }

    private class Frame(val name: String, val action: Action)

    /** A paragraph or table row held back until we know whether it survives. */
    private class Pending(val name: String, val depth: Int) {
        val buf = StringBuilder()
        var deleted = false
        var hasContent = false
    }

    private val frames = ArrayDeque<Frame>()
    private val pending = ArrayDeque<Pending>()
    private var skipDepth = 0

    private fun emit(s: String) {
        val p = pending.lastOrNull()
        if (p != null) p.buf.append(s) else out.write(s)
    }

    private fun emit(buf: CharArray, off: Int, len: Int) {
        val p = pending.lastOrNull()
        if (p != null) p.buf.append(buf, off, len) else out.write(buf, off, len)
    }

    private fun nameOf(raw: String, from: Int): String {
        var end = from
        while (end < raw.length && !raw[end].isWhitespace() && raw[end] != '>' && raw[end] != '/') end++
        return raw.substring(from, end)
    }

    private fun parentIs(vararg names: String): Boolean {
        if (frames.size < names.size) return false
        for (i in names.indices) if (frames[frames.size - 1 - i].name != names[names.size - 1 - i]) return false
        return true
    }

    override fun text(buf: CharArray, off: Int, len: Int) {
        if (skipDepth > 0) return
        if (acceptRevisions && frames.lastOrNull()?.name == "w:t") {
            pending.lastOrNull()?.let { p -> if ((off until off + len).any { !buf[it].isWhitespace() }) p.hasContent = true }
        }
        emit(buf, off, len)
    }

    override fun other(raw: String) {
        if (skipDepth == 0) emit(raw)
    }

    override fun startTag(raw: String) {
        val selfClosing = raw.endsWith("/>")
        val name = nameOf(raw, 1)

        if (skipDepth > 0) {
            if (!selfClosing) skipDepth++
            return
        }

        if (removeComments && name in COMMENT_MARKERS) {
            if (!selfClosing) skipDepth = 1
            return
        }

        if (acceptRevisions) {
            when {
                name in REMOVED_WITH_CONTENT || ((name == "w:del" || name == "w:moveFrom") && !selfClosing) -> {
                    if (!selfClosing) skipDepth = 1
                    return
                }
                name == "w:del" || name == "w:moveFrom" -> {
                    // Empty marker: a deleted paragraph mark (in pPr/rPr) or deleted row (in trPr).
                    if (parentIs("w:pPr", "w:rPr")) pending.lastOrNull { it.name == "w:p" }?.deleted = true
                    if (frames.lastOrNull()?.name == "w:trPr") pending.lastOrNull { it.name == "w:tr" }?.deleted = true
                    return
                }
                name in DROPPED_MARKERS || ((name == "w:ins" || name == "w:moveTo") && selfClosing) -> return
                name == "w:ins" || name == "w:moveTo" -> {
                    frames.addLast(Frame(name, Action.UNWRAP))
                    return
                }
                (name == "w:p" || name == "w:tr") && !selfClosing -> pending.addLast(Pending(name, frames.size))
            }
            if (name in CONTENT_TAGS) pending.lastOrNull()?.hasContent = true
        }

        // Fast path: most tags carry nothing to anonymise, so skip attribute parsing.
        if (!mayNeedRewrite(raw)) {
            emit(raw)
            if (!selfClosing) frames.addLast(Frame(name, Action.KEEP))
            return
        }
        val tag = XmlTag.parse(raw)
        val changed = OfficeCleaner.wordAttributes(tag)
        if (!(changed && tag.drop && tag.selfClosing)) emit(if (changed) tag.render() else raw)
        if (!selfClosing) frames.addLast(Frame(name, Action.KEEP))
    }

    override fun endTag(raw: String) {
        if (skipDepth > 0) {
            skipDepth--
            return
        }
        val frame = frames.removeLastOrNull()
        if (frame?.action == Action.UNWRAP) return
        emit(raw)
        val p = pending.lastOrNull()
        if (acceptRevisions && p != null && frame != null && frame.name == p.name && frames.size == p.depth) {
            pending.removeLast()
            val keep = when (p.name) {
                "w:tr" -> !p.deleted
                else -> !p.deleted || p.hasContent
            }
            if (keep) {
                emit(p.buf.toString())
                if (p.hasContent) pending.lastOrNull()?.hasContent = true
            }
        }
    }

    private fun mayNeedRewrite(raw: String): Boolean =
        raw.contains("w:rsid") || raw.contains("w:author") || raw.contains("w:initials") ||
            raw.contains("w:date=") || raw.contains("dateUtc")

    fun finish() {
        // Unbalanced input: never lose buffered bytes.
        while (pending.isNotEmpty()) {
            val p = pending.removeLast()
            emit(p.buf.toString())
        }
        out.flush()
    }

    companion object {
        private val COMMENT_MARKERS = setOf("w:commentRangeStart", "w:commentRangeEnd", "w:commentReference")

        private val REMOVED_WITH_CONTENT = setOf(
            "w:rPrChange", "w:pPrChange", "w:sectPrChange", "w:tblPrChange", "w:tblPrExChange",
            "w:trPrChange", "w:tcPrChange", "w:tblGridChange", "w:numberingChange"
        )

        private val DROPPED_MARKERS = setOf(
            "w:moveFromRangeStart", "w:moveFromRangeEnd", "w:moveToRangeStart", "w:moveToRangeEnd",
            "w:customXmlInsRangeStart", "w:customXmlInsRangeEnd", "w:customXmlDelRangeStart", "w:customXmlDelRangeEnd",
            "w:customXmlMoveFromRangeStart", "w:customXmlMoveFromRangeEnd", "w:customXmlMoveToRangeStart", "w:customXmlMoveToRangeEnd",
            "w:cellIns", "w:cellDel", "w:cellMerge"
        )

        /** Anything that makes a paragraph visibly non-empty. */
        private val CONTENT_TAGS = setOf(
            "w:tab", "w:br", "w:cr", "w:sym", "w:drawing", "w:pict", "w:object", "w:fldSimple", "w:fldChar",
            "w:footnoteReference", "w:endnoteReference", "w:ptab", "m:oMath", "m:oMathPara", "w:noBreakHyphen", "w:softHyphen"
        )
    }
}
