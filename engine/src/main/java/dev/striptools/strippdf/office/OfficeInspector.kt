package dev.striptools.strippdf.office

import java.io.File
import java.io.InputStreamReader

/** Everything privacy-relevant found in a Word / Excel / PowerPoint file. */
data class OfficeReport(
    val kind: OfficeKind,
    /** Core properties by local name: title, creator, lastModifiedBy, created, revision, ... */
    val core: Map<String, String> = emptyMap(),
    /** Extended (app) properties that identify people or their machines; removed on clean. */
    val app: Map<String, String> = emptyMap(),
    /** Producing application and version. Kept on clean: readers use it to pick compatibility rules. */
    val software: Map<String, String> = emptyMap(),
    /** User-defined custom properties. */
    val custom: Map<String, String> = emptyMap(),
    /** Names attached to comments, tracked changes and co-authoring. */
    val people: List<String> = emptyList(),
    val commentCount: Int = 0,
    val trackedChangeCount: Int = 0,
    /** Word rsids, Excel revision pointers, PowerPoint revision info: per-session editing fingerprints. */
    val hasRevisionIds: Boolean = false,
    val hasThumbnail: Boolean = false,
    val imageCount: Int = 0,
    val imagesWithMetadata: Int = 0,
    val imagesWithGps: Int = 0,
    val hasMacros: Boolean = false,
    /** Local file paths and network shares that cleaning removes (template, save location, mail merge). */
    val localPaths: List<String> = emptyList(),
    /** Links to files on the author's disk. Kept on clean: removing them would break the document. */
    val linkedFilePaths: List<String> = emptyList(),
    val hasPrinterInfo: Boolean = false,
    /** PowerPoint slides with speaker notes text. */
    val speakerNotesCount: Int = 0
) {
    val hasPersonalProperties: Boolean
        get() = core.values.any { it.isNotBlank() } || app.values.any { it.isNotBlank() } || custom.isNotEmpty()
}

object OfficeInspector {

    /** Identifying extended properties; counts like Pages/Words are content, not identity. */
    internal val APP_FIELDS = listOf("Company", "Manager", "Template", "TotalTime", "HyperlinkBase")

    /**
     * Kept deliberately: LibreOffice (and likely other readers) switch import behaviour on these,
     * and removing them changed rendering of real spreadsheets and decks in testing.
     */
    internal val SOFTWARE_FIELDS = listOf("Application", "AppVersion")

    private val TRACKED_CHANGE_TAGS = setOf(
        "w:ins", "w:del", "w:moveFrom", "w:moveTo", "w:rPrChange", "w:pPrChange", "w:sectPrChange",
        "w:tblPrChange", "w:trPrChange", "w:tcPrChange", "w:tblGridChange", "w:numberingChange", "w:cellIns", "w:cellDel"
    )

    fun inspect(file: File): OfficeReport = OoxmlPackage(file).use { pkg -> inspect(pkg) }

    internal fun inspect(pkg: OoxmlPackage): OfficeReport {
        val core = pkg.rootTarget(OoxmlRel.CORE)?.let { pkg.text(it) }?.let(::readCore).orEmpty()
        val appXml = pkg.rootTarget(OoxmlRel.APP)?.let { pkg.text(it) }
        val app = appXml?.let { readApp(it, APP_FIELDS) }.orEmpty()
        val software = appXml?.let { readApp(it, SOFTWARE_FIELDS) }.orEmpty()
        val custom = pkg.rootTarget(OoxmlRel.CUSTOM)?.let { pkg.text(it) }?.let(::readCustom).orEmpty()

        val people = LinkedHashSet<String>()
        var comments = 0
        var tracked = 0
        var revisionIds = false
        var printer = false
        val paths = LinkedHashSet<String>()
        val linked = LinkedHashSet<String>()
        app["HyperlinkBase"]?.takeIf { it.isNotBlank() }?.let(paths::add)

        // External relationships pointing at local disks / shares, in any part.
        for (relsPart in pkg.relsParts) {
            val xml = pkg.text(relsPart) ?: continue
            val source = OoxmlPackage.sourcePartFor(relsPart)
            val removable = if (pkg.kind == OfficeKind.WORD && source.endsWith("/settings.xml")) {
                pkg.text(source)?.let(OfficeCleaner::wordSettingsRelIds).orEmpty()
            } else emptySet()
            OoxmlPackage.parseRels(xml, source)
                .filter { it.external && OoxmlPackage.isLocalPath(it.target) }
                .forEach { if (it.id in removable) paths += it.target else linked += it.target }
        }

        var notes = 0
        var images = 0
        var imagesMeta = 0
        var imagesGps = 0
        var macros = false

        for (entry in pkg.entries) {
            val name = entry.name
            val lower = name.lowercase()
            when {
                lower.endsWith("vbaproject.bin") -> macros = true
                lower.contains("/printersettings/") -> if (!printer) printer = pkg.bytes(name)?.let(::printerNamePresent) == true
                lower.contains("/media/") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) -> {
                    val b = pkg.bytes(name) ?: continue
                    images++
                    val info = ImageMetadata.inspect(b)
                    if (info.hasMetadata) imagesMeta++
                    if (info.hasGps) imagesGps++
                }
            }
        }

        when (pkg.kind) {
            OfficeKind.WORD -> {
                for (entry in pkg.entries) {
                    val name = entry.name
                    if (!name.startsWith("word/") || !name.endsWith(".xml")) continue
                    val isComments = name.substringAfterLast('/') == "comments.xml"
                    InputStreamReader(pkg.open(entry), Charsets.UTF_8).use { r ->
                        XmlTags.tokenize(r, object : XmlTags.TokenSink {
                            override fun text(buf: CharArray, off: Int, len: Int) = Unit
                            override fun endTag(raw: String) = Unit
                            override fun other(raw: String) = Unit
                            override fun startTag(raw: String) {
                                // Cheap string checks first; only parse tags that carry names.
                                val end = raw.indexOfFirst { it.isWhitespace() || it == '>' || it == '/' }.let { if (it < 0) raw.length else it }
                                val name = raw.substring(1, end.coerceAtLeast(1))
                                if (name in TRACKED_CHANGE_TAGS) tracked++
                                if (isComments && name == "w:comment") comments++
                                if (!revisionIds && raw.contains("w:rsid")) revisionIds = true
                                if (raw.contains("author=")) {
                                    val tag = XmlTag.parse(raw)
                                    tag.attr("w:author")?.takeIf { it.isNotBlank() }?.let(people::add)
                                    if (tag.localName == "person") tag.attr("w15:author")?.takeIf { it.isNotBlank() }?.let(people::add)
                                }
                            }
                        })
                    }
                }
            }
            OfficeKind.EXCEL -> {
                pkg.text("xl/workbook.xml")?.let { wb ->
                    Regex("""<(?:[\w.-]+:)?absPath\b[^>]*\burl="([^"]*)"""").findAll(wb)
                        .forEach { paths += XmlText.unescape(it.groupValues[1]) }
                    if (Regex("""<(?:[\w.-]+:)?revisionPtr\b""").containsMatchIn(wb)) revisionIds = true
                    Regex("""<fileSharing\b[^>]*\buserName="([^"]*)"""").find(wb)?.let { people += XmlText.unescape(it.groupValues[1]) }
                }
                for (entry in pkg.entries) {
                    val name = entry.name
                    if (!name.startsWith("xl/") || !name.endsWith(".xml")) continue
                    when {
                        name.matches(Regex("""xl/comments\d*\.xml""")) -> pkg.text(name)?.let { xml ->
                            XmlText.elementTexts(xml, "author").filter { it.isNotBlank() }.forEach(people::add)
                            comments += Regex("""<comment\b""").findAll(xml).count()
                        }
                        name.startsWith("xl/threadedComments/") -> pkg.text(name)?.let { xml ->
                            comments += Regex("""<(?:[\w.-]+:)?threadedComment\b""").findAll(xml).count()
                        }
                        name.startsWith("xl/persons/") -> pkg.text(name)?.let { xml ->
                            XmlTags.scan(xml.reader()) { tag ->
                                if (tag.localName == "person") tag.attr("displayName")?.takeIf { it.isNotBlank() }?.let(people::add)
                            }
                        }
                        name.startsWith("xl/revisions/") -> pkg.text(name)?.let { xml ->
                            XmlTags.scan(xml.reader()) { tag ->
                                when (tag.localName) {
                                    "header" -> tag.attr("userName")
                                    "userInfo" -> tag.attr("name")
                                    else -> null
                                }?.takeIf { it.isNotBlank() }?.let(people::add)
                            }
                        }
                    }
                }
            }
            OfficeKind.POWERPOINT -> {
                for (entry in pkg.entries) {
                    val name = entry.name
                    if (!name.startsWith("ppt/") || !name.endsWith(".xml")) continue
                    when {
                        name == "ppt/commentAuthors.xml" || name == "ppt/authors.xml" -> pkg.text(name)?.let { xml ->
                            XmlTags.scan(xml.reader()) { tag ->
                                if (tag.localName == "cmAuthor" || tag.localName == "author") {
                                    tag.attr("name")?.takeIf { it.isNotBlank() }?.let(people::add)
                                }
                            }
                        }
                        name.startsWith("ppt/comments/") -> pkg.text(name)?.let { xml ->
                            comments += Regex("""<(?:[\w.-]+:)?cm\b""").findAll(xml).count()
                        }
                        name == "ppt/revisionInfo.xml" -> revisionIds = true
                        name.startsWith("ppt/notesSlides/") && !name.contains("/_rels/") -> pkg.text(name)?.let { xml ->
                            // Ignore the slide-number field every notes page carries.
                            val body = XmlText.removeElements(xml, "fld")
                            if (XmlText.elementTexts(body, "t").any { it.isNotBlank() }) notes++
                        }
                    }
                }
            }
        }

        return OfficeReport(
            kind = pkg.kind,
            core = core,
            app = app,
            software = software,
            custom = custom,
            people = people.filter { it != OfficeCleaner.ANONYMOUS },
            commentCount = comments,
            trackedChangeCount = tracked,
            hasRevisionIds = revisionIds,
            hasThumbnail = pkg.rootTarget(OoxmlRel.THUMBNAIL)?.let(pkg::has) == true,
            imageCount = images,
            imagesWithMetadata = imagesMeta,
            imagesWithGps = imagesGps,
            hasMacros = macros,
            localPaths = paths.toList(),
            linkedFilePaths = linked.toList(),
            hasPrinterInfo = printer,
            speakerNotesCount = notes
        )
    }

    internal fun readCore(xml: String): Map<String, String> {
        val body = xml.substringAfter("coreProperties", "").substringAfter('>', "")
        val out = LinkedHashMap<String, String>()
        Regex("""<((?:[\w.-]+:)?([\w.-]+))(?:\s[^>]*)?>([^<]*)</\1\s*>""").findAll(body).forEach { m ->
            val value = XmlText.unescape(m.groupValues[3]).trim()
            if (value.isNotEmpty()) out[m.groupValues[2]] = value
        }
        return out
    }

    internal fun readApp(xml: String, fields: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (field in fields) {
            XmlText.elementTexts(xml, field).firstOrNull { it.isNotBlank() }?.let { out[field] = it }
        }
        return out
    }

    internal fun readCustom(xml: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        Regex("""<((?:[\w.-]+:)?property)\b([^>]*)>(.*?)</\1\s*>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).forEach { m ->
            val name = XmlTag.parse("<p ${m.groupValues[2]}>").attr("name") ?: return@forEach
            out[name] = XmlText.unescape(m.groupValues[3].replace(Regex("<[^>]*>"), "")).trim()
        }
        return out
    }

    /** DEVMODE starts with the printer's device name as 32 UTF-16 chars. */
    internal fun printerNamePresent(b: ByteArray): Boolean =
        b.size >= 64 && (0 until 64).any { b[it] != 0.toByte() }
}
