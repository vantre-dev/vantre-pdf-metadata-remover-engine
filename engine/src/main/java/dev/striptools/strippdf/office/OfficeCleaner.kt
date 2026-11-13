package dev.striptools.strippdf.office

import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Calendar
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a copy of a Word / Excel / PowerPoint file with personal metadata removed.
 * Visible content (text, comments' wording, tracked edits, formulas, slides) is left
 * alone; what changes is who/when/where information around it. The approach mirrors
 * Office's own Document Inspector, plus a few things it misses (photo EXIF, save
 * paths, printer names, editing-session IDs).
 */
object OfficeCleaner {

    data class Options(
        /** Clear core, extended and custom document properties. Pro users may keep them. */
        val removeProperties: Boolean = true,
        /** Delete comments and their threads entirely (Pro). Off: comments stay, authors anonymised. */
        val removeComments: Boolean = false,
        /** Word: accept all tracked changes so deleted text is gone for good (Pro). */
        val acceptRevisions: Boolean = false,
        /** PowerPoint: delete speaker notes (Pro). */
        val removeSpeakerNotes: Boolean = false
    )

    const val ANONYMOUS = "Author"
    private const val ANONYMOUS_INITIALS = "A"

    /** DOS epoch: Office itself writes 1980-01-01 timestamps into its zips. */
    private val ZIP_TIME: Long = Calendar.getInstance().apply {
        clear(); set(1980, Calendar.JANUARY, 1, 0, 0, 0)
    }.timeInMillis

    fun clean(input: File, output: File, options: Options = Options(), isCancelled: () -> Boolean = { false }) {
        OoxmlPackage(input).use { pkg -> clean(pkg, output, options, isCancelled) }
    }

    private fun clean(pkg: OoxmlPackage, output: File, options: Options, isCancelled: () -> Boolean) {
        // ── Parts to drop entirely ────────────────────────────────────────
        val removed = HashSet<String>()
        pkg.rootTarget(OoxmlRel.THUMBNAIL)?.let(removed::add)
        if (options.removeProperties) pkg.rootTarget(OoxmlRel.CUSTOM)?.let(removed::add)
        pkg.mainPart?.let { main ->
            pkg.rels(main).filter { !it.external && (it.type.endsWith(OoxmlRel.PEOPLE) || it.type.endsWith(OoxmlRel.REVISION_INFO)) }
                .mapNotNullTo(removed) { it.resolved }
        }
        // Element references to drop inside a part, keyed by part name (relationship ids).
        val dropRefs = HashMap<String, MutableSet<String>>()
        if (options.removeComments) planCommentRemoval(pkg, removed, dropRefs)
        if (options.removeSpeakerNotes && pkg.kind == OfficeKind.POWERPOINT) {
            for (slide in pkg.entries.map { it.name }.filter { it.matches(Regex("""ppt/slides/slide\d+\.xml""")) }) {
                pkg.rels(slide).filter { !it.external && it.type.endsWith("/notesSlide") }.mapNotNullTo(removed) { it.resolved }
            }
        }
        removed.toList().forEach { removed += OoxmlPackage.relsPathFor(it) }

        // Relationship ids to drop, keyed by .rels path (on top of those targeting removed parts).
        val dropRelIds = HashMap<String, MutableSet<String>>()
        if (pkg.kind == OfficeKind.WORD) {
            // Main document and glossary (building blocks) each have their own settings part.
            for (settings in pkg.entries.map { it.name }.filter { it.startsWith("word/") && it.endsWith("/settings.xml") }) {
                val ids = pkg.text(settings)?.let(::wordSettingsRelIds).orEmpty()
                if (ids.isNotEmpty()) dropRelIds[OoxmlPackage.relsPathFor(settings)] = ids.toMutableSet()
            }
        }

        val coreName = pkg.rootTarget(OoxmlRel.CORE)
        val appName = pkg.rootTarget(OoxmlRel.APP)
        val excelAuthors = if (pkg.kind == OfficeKind.EXCEL) collectExcelCommentAuthors(pkg) else emptySet()

        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream().buffered()).use { zos ->
            zos.setLevel(java.util.zip.Deflater.DEFAULT_COMPRESSION)
            val written = HashSet<String>()
            for (entry in pkg.entries) {
                if (isCancelled()) throw InterruptedException("cancelled")
                val name = entry.name
                // Duplicate names (seen in damaged files) resolve to the first entry on read anyway.
                if (name in removed || entry.isDirectory || !written.add(name)) continue
                zos.putNextEntry(ZipEntry(name).apply { time = ZIP_TIME })

                val lower = name.lowercase()
                when {
                    name == "[Content_Types].xml" ->
                        zos.writeText(removeOverrides(pkg.text(name)!!, removed))

                    name.endsWith(".rels") ->
                        zos.writeText(cleanRels(pkg.text(name)!!, name, removed, dropRelIds[name].orEmpty()))

                    name == coreName && options.removeProperties ->
                        zos.writeText(emptyCore(pkg.text(name)!!))

                    name == appName && options.removeProperties ->
                        zos.writeText(cleanApp(pkg.text(name)!!))

                    pkg.kind == OfficeKind.WORD && name.startsWith("word/") && name.endsWith("/settings.xml") ->
                        zos.writeText(cleanWordSettings(pkg.text(name)!!))

                    pkg.kind == OfficeKind.WORD && name.startsWith("word/") && lower.endsWith(".xml") ->
                        streamWord(pkg, entry, zos, options, isCancelled)

                    pkg.kind == OfficeKind.EXCEL && name in dropRefs ->
                        dropRefs.getValue(name).let { ids ->
                            streamRewrite(pkg, entry, zos, isCancelled) { tag ->
                                if (tag.localName == "legacyDrawing" && tag.attrs.any { (k, v) -> k.endsWith(":id") && v in ids }) {
                                    tag.drop = true; true
                                } else false
                            }
                        }

                    pkg.kind == OfficeKind.POWERPOINT && name in dropRefs ->
                        zos.writeText(removeSlideCommentRefs(pkg.text(name)!!))

                    pkg.kind == OfficeKind.EXCEL && name == "xl/workbook.xml" ->
                        zos.writeText(cleanWorkbook(pkg.text(name)!!))

                    pkg.kind == OfficeKind.EXCEL && name.matches(Regex("""xl/comments\d*\.xml""")) ->
                        zos.writeText(cleanExcelComments(pkg.text(name)!!, excelAuthors))

                    pkg.kind == OfficeKind.EXCEL && name.startsWith("xl/persons/") && lower.endsWith(".xml") ->
                        zos.writeText(XmlText.rewriteString(pkg.text(name)!!, ::excelPersonAttributes))

                    pkg.kind == OfficeKind.EXCEL && name.startsWith("xl/revisions/") && lower.endsWith(".xml") ->
                        zos.writeText(XmlText.rewriteString(pkg.text(name)!!, ::excelRevisionAttributes))

                    pkg.kind == OfficeKind.POWERPOINT && (name == "ppt/commentAuthors.xml" || name == "ppt/authors.xml") ->
                        zos.writeText(cleanPptAuthors(pkg.text(name)!!))

                    pkg.kind == OfficeKind.POWERPOINT && name == "ppt/presentation.xml" ->
                        zos.writeText(markPresentationPrivate(pkg.text(name)!!))

                    lower.contains("/printersettings/") ->
                        zos.write(blankPrinterName(pkg.bytes(name)!!))

                    lower.contains("/media/") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) ->
                        zos.write(ImageMetadata.strip(pkg.bytes(name)!!))

                    else -> pkg.open(entry).use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (isCancelled()) throw InterruptedException("cancelled")
                            zos.write(buf, 0, n)
                        }
                    }
                }
                zos.closeEntry()
            }
        }
    }

    private fun ZipOutputStream.writeText(s: String) = write(s.toByteArray(Charsets.UTF_8))

    /** Writer onto the open zip entry that never closes the zip stream itself. */
    private fun entryWriter(zos: ZipOutputStream) = OutputStreamWriter(object : java.io.FilterOutputStream(zos) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }, Charsets.UTF_8).buffered(64 * 1024)

    /** Reader that aborts between chunks once the user cancels, so huge parts stop promptly. */
    private class CancellableReader(private val inner: java.io.Reader, private val isCancelled: () -> Boolean) : java.io.Reader() {
        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (isCancelled()) throw InterruptedException("cancelled")
            return inner.read(cbuf, off, len)
        }
        override fun close() = inner.close()
    }

    private fun reader(pkg: OoxmlPackage, entry: ZipEntry, isCancelled: () -> Boolean) =
        CancellableReader(InputStreamReader(pkg.open(entry), Charsets.UTF_8), isCancelled)

    private fun streamRewrite(pkg: OoxmlPackage, entry: ZipEntry, zos: ZipOutputStream, isCancelled: () -> Boolean, transform: XmlTags.Transform) {
        val writer = entryWriter(zos)
        reader(pkg, entry, isCancelled).use { XmlTags.rewrite(it, writer, transform) }
        writer.flush()
    }

    private fun streamWord(pkg: OoxmlPackage, entry: ZipEntry, zos: ZipOutputStream, options: Options, isCancelled: () -> Boolean) {
        val writer = entryWriter(zos)
        val processor = WordPartProcessor(writer, options.removeComments, options.acceptRevisions)
        reader(pkg, entry, isCancelled).use { XmlTags.tokenize(it, processor) }
        processor.finish()
    }

    // ── Comment removal (Pro) ─────────────────────────────────────────────

    private fun planCommentRemoval(pkg: OoxmlPackage, removed: MutableSet<String>, dropRefs: MutableMap<String, MutableSet<String>>) {
        val main = pkg.mainPart ?: return
        val commentTypes = listOf("/comments", "/commentsExtended", "/commentsIds", "/commentsExtensible")
        when (pkg.kind) {
            OfficeKind.WORD ->
                pkg.rels(main).filter { r -> !r.external && commentTypes.any { r.type.endsWith(it) } }.mapNotNullTo(removed) { it.resolved }

            OfficeKind.EXCEL -> {
                var allSheetsCleared = true
                val sheets = pkg.rels(main).filter { !it.external && it.type.endsWith("/worksheet") }.mapNotNull { it.resolved }
                for (sheet in sheets) {
                    val rels = pkg.rels(sheet)
                    val comments = rels.filter { !it.external && (it.type.endsWith("/comments") || it.type.endsWith("/threadedComment")) }
                    if (comments.isEmpty()) continue
                    // Notes are drawn by a VML part; drop it only if it holds nothing but notes.
                    val vml = rels.filter { !it.external && it.type.endsWith("/vmlDrawing") }
                    val vmlOnlyNotes = vml.all { r ->
                        val x = r.resolved?.let(pkg::text) ?: return@all false
                        val types = Regex("""ObjectType="([^"]+)"""").findAll(x).map { it.groupValues[1] }.toList()
                        types.all { it == "Note" }
                    }
                    if (!vmlOnlyNotes) { allSheetsCleared = false; continue }
                    comments.mapNotNullTo(removed) { it.resolved }
                    vml.mapNotNullTo(removed) { it.resolved }
                    if (vml.isNotEmpty()) dropRefs.getOrPut(sheet) { HashSet() } += vml.map { it.id }
                }
                if (allSheetsCleared) pkg.rels(main).filter { !it.external && it.type.endsWith("/person") }.mapNotNullTo(removed) { it.resolved }
            }

            OfficeKind.POWERPOINT -> {
                for (slide in pkg.entries.map { it.name }.filter { it.matches(Regex("""ppt/slides/slide\d+\.xml""")) }) {
                    val comments = pkg.rels(slide).filter { !it.external && it.type.endsWith("/comments") }
                    comments.mapNotNullTo(removed) { it.resolved }
                    // Modern comments are also referenced from the slide's extension list.
                    if (comments.isNotEmpty()) dropRefs.getOrPut(slide) { HashSet() } += comments.map { it.id }
                }
                pkg.rels(main).filter { r -> !r.external && (r.type.endsWith("/commentAuthors") || r.type.endsWith("/authors")) }
                    .mapNotNullTo(removed) { it.resolved }
            }
        }
    }

    internal fun removeSlideCommentRefs(xml: String): String {
        var out = Regex("""<([\w.-]+):ext\b[^>]*>(?:(?!</\1:ext>).)*?commentRel(?:(?!</\1:ext>).)*?</\1:ext>""", RegexOption.DOT_MATCHES_ALL)
            .replace(xml, "")
        out = Regex("""<([\w.-]+):extLst>\s*</\1:extLst>""").replace(out, "")
        return out
    }

    // ── Package plumbing ──────────────────────────────────────────────────

    private fun removeOverrides(xml: String, removed: Set<String>): String =
        Regex("""<(?:[\w.-]+:)?Override\b[^>]*?/>""").replace(xml) { m ->
            val part = XmlTag.parse(m.value).attr("PartName")?.removePrefix("/")
            if (part != null && part in removed) "" else m.value
        }

    private fun cleanRels(xml: String, relsName: String, removed: Set<String>, dropIds: Set<String>): String {
        val source = OoxmlPackage.sourcePartFor(relsName)
        return Regex("""<(?:[\w.-]+:)?Relationship\b[^>]*?/>""").replace(xml) { m ->
            val rel = OoxmlPackage.parseRels(m.value, source).firstOrNull() ?: return@replace m.value
            when {
                rel.id in dropIds -> ""
                rel.resolved != null && rel.resolved in removed -> ""
                else -> m.value
            }
        }
    }

    // ── Document properties ───────────────────────────────────────────────

    /** Keeps the root element (and its namespace declarations), drops every property. */
    internal fun emptyCore(xml: String): String {
        val root = Regex("""<((?:[\w.-]+:)?coreProperties)\b[^>]*?(/?)>""").find(xml) ?: return xml
        val decl = xml.substring(0, root.range.first)
        if (root.groupValues[2] == "/") return xml
        return decl + root.value + "</${root.groupValues[1]}>"
    }

    internal fun cleanApp(xml: String): String {
        var out = xml
        for (field in OfficeInspector.APP_FIELDS + "HLinks") out = XmlText.removeElements(out, field)
        return out
    }

    // ── Word ──────────────────────────────────────────────────────────────

    internal fun wordAttributes(tag: XmlTag): Boolean {
        // <w:rsid w:val=".."/> inside styles is the same session fingerprint as the attributes.
        if (tag.name == "w:rsid" || tag.name == "w:rsidRoot") { tag.drop = true; return true }
        var changed = tag.attrs.removeAll { (k, _) -> k.startsWith("w:rsid") || k == "w:date" || k.endsWith(":dateUtc") }
        if (tag.attr("w:author")?.let { it.isNotEmpty() && it != ANONYMOUS } == true) { tag.set("w:author", ANONYMOUS); changed = true }
        if (tag.attr("w:initials")?.let { it.isNotEmpty() && it != ANONYMOUS_INITIALS } == true) { tag.set("w:initials", ANONYMOUS_INITIALS); changed = true }
        return changed
    }

    /**
     * Removes the revision-session table, template path and mail-merge source, and turns on
     * Word's own "remove personal information on save" so later edits stay anonymous.
     */
    internal fun cleanWordSettings(xml: String): String {
        var out = xml
        for (el in listOf("rsids", "attachedTemplate", "mailMerge")) out = XmlText.removeElements(out, el)
        out = XmlText.rewriteString(out, ::wordAttributes)
        if (!Regex("""<w:removePersonalInformation\b""").containsMatchIn(out)) {
            // Schema order: writeProtection, view, zoom, removePersonalInformation, removeDateAndTime, ...
            val anchor = listOf("zoom", "view", "writeProtection").firstNotNullOfOrNull { el ->
                Regex("""<w:$el\b[^>]*?(?:/>|>.*?</w:$el>)""", RegexOption.DOT_MATCHES_ALL).find(out)
            } ?: Regex("""<w:settings\b[^>]*>""").find(out)
            if (anchor != null) {
                val insert = "<w:removePersonalInformation/>" +
                    if (Regex("""<w:removeDateAndTime\b""").containsMatchIn(out)) "" else "<w:removeDateAndTime/>"
                out = out.substring(0, anchor.range.last + 1) + insert + out.substring(anchor.range.last + 1)
            }
        }
        return out
    }

    /** Relationship ids used by the template link and mail-merge data source in a settings part. */
    internal fun wordSettingsRelIds(xml: String): Set<String> {
        val blocks = Regex("""<w:attachedTemplate\b[^>]*/>""").findAll(xml).map { it.value } +
            Regex("""<w:mailMerge\b.*?</w:mailMerge>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.value }
        return blocks.flatMap { b -> Regex("""\br:id="([^"]+)"""").findAll(b).map { it.groupValues[1] } }.toSet()
    }

    // ── Excel ─────────────────────────────────────────────────────────────

    internal fun cleanWorkbook(xml: String): String {
        var out = Regex(
            """<([\w.-]+):AlternateContent\b(?:(?!</\1:AlternateContent>).)*?absPath(?:(?!</\1:AlternateContent>).)*?</\1:AlternateContent>""",
            RegexOption.DOT_MATCHES_ALL
        ).replace(xml, "")
        out = XmlText.removeElements(out, "absPath")
        out = XmlText.removeElements(out, "revisionPtr")
        out = XmlText.rewriteString(out) { tag ->
            when (tag.localName) {
                "fileSharing" -> tag.attrs.removeAll { it.first == "userName" }
                // Excel's own "remove personal information from file properties on save".
                "workbookPr" -> if (tag.attr("filterPrivacy") != "1") { tag.set("filterPrivacy", "1"); true } else false
                else -> false
            }
        }
        return out
    }

    private fun collectExcelCommentAuthors(pkg: OoxmlPackage): Set<String> =
        pkg.entries.map { it.name }.filter { it.matches(Regex("""xl/comments\d*\.xml""")) }
            .flatMap { n -> pkg.text(n)?.let { XmlText.elementTexts(it, "author") }.orEmpty() }
            .filter { it.isNotBlank() }.toSet()

    /**
     * Legacy Excel notes store authors in an <authors> list and Excel also types
     * "Name:" as the first bold run of each note. Both are replaced.
     */
    internal fun cleanExcelComments(xml: String, authors: Set<String>): String {
        var out = Regex("""(<author>)(.*?)(</author>)""", RegexOption.DOT_MATCHES_ALL).replace(xml) { m ->
            m.groupValues[1] + ANONYMOUS + m.groupValues[3]
        }
        for (name in authors) {
            val escaped = Regex.escape(XmlText.escapeText(name))
            out = Regex("""(<t(?:\s[^>]*)?>)$escaped:(\s*)(</t>)""").replace(out) { m ->
                m.groupValues[1] + "$ANONYMOUS:" + m.groupValues[2] + m.groupValues[3]
            }
        }
        return out
    }

    /** Legacy shared-workbook change log: who edited and their user list. */
    internal fun excelRevisionAttributes(tag: XmlTag): Boolean = when (tag.localName) {
        "header" -> tag.attr("userName")?.let { tag.set("userName", ANONYMOUS); true } ?: false
        "userInfo" -> tag.attr("name")?.let { tag.set("name", ANONYMOUS); true } ?: false
        else -> false
    }

    internal fun excelPersonAttributes(tag: XmlTag): Boolean {
        if (tag.localName != "person") return false
        tag.attrs.removeAll { it.first == "userId" || it.first == "providerId" }
        tag.set("displayName", ANONYMOUS)
        return true
    }

    // ── PowerPoint ────────────────────────────────────────────────────────

    internal fun cleanPptAuthors(xml: String): String {
        val out = XmlText.removeElements(xml, "presenceInfo")
        return XmlText.rewriteString(out) { tag ->
            when (tag.localName) {
                "cmAuthor" -> {
                    tag.set("name", ANONYMOUS); tag.set("initials", ANONYMOUS_INITIALS); true
                }
                "author" -> {
                    tag.set("name", ANONYMOUS); tag.set("initials", ANONYMOUS_INITIALS)
                    if (tag.attr("userId") != null) tag.set("userId", ANONYMOUS)
                    if (tag.attr("providerId") != null) tag.set("providerId", "None")
                    true
                }
                else -> false
            }
        }
    }

    /** PowerPoint's own "remove personal information on save" switch. */
    internal fun markPresentationPrivate(xml: String): String {
        var done = false
        return XmlText.rewriteString(xml) { tag ->
            if (done || tag.localName != "presentation") return@rewriteString false
            done = true
            if (tag.attr("removePersonalInfoOnSave") == "1") false
            else { tag.set("removePersonalInfoOnSave", "1"); true }
        }
    }

    // ── Shared ────────────────────────────────────────────────────────────

    /** Zeroes DEVMODE.dmDeviceName (first 32 UTF-16 chars) so the printer's network name is gone. */
    internal fun blankPrinterName(b: ByteArray): ByteArray {
        if (b.size < 64) return b
        return b.copyOf().also { java.util.Arrays.fill(it, 0, 64, 0) }
    }
}
