package dev.striptools.strippdf.pdf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dev.striptools.strippdf.data.PdfMetadata
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object PdfMetadataExtractor {
    private const val TAG = "PdfMetadataExtractor"
    private const val MAX_DICT_ENTRIES = 120
    private const val MAX_VALUE_CHARS = 800
    private val MALFORMED_DICT_PATTERN = Regex("""<<\s*(/[A-Za-z][A-Za-z0-9]*)\s*>>""")

    // C2PA namespace/claim markers and IPTC "Digital Source Type" values that flag
    // AI-generated or AI-edited content inside an XMP packet.
    private val AI_PROVENANCE_MARKERS = listOf(
        "c2pa",
        "urn:c2pa",
        "contentauth",
        "trainedalgorithmicmedia",
        "compositewithtrainedalgorithmicmedia",
        "digitalsourcetype"
    )

    /**
     * Walks the catalog's /AF (Associated Files) array for a Filespec whose
     * /AFRelationship is C2PA_Manifest — how a C2PA manifest is typically
     * attached to a PDF as an embedded JUMBF file.
     */
    private fun detectC2paManifest(catalogCos: COSDictionary): Boolean {
        val af = catalogCos.getDictionaryObject(COSName.getPDFName("AF")) as? COSArray ?: return false
        for (i in 0 until af.size()) {
            val spec = when (val item = af.getObject(i)) {
                is COSDictionary -> item
                is COSObject -> item.getObject() as? COSDictionary
                else -> null
            } ?: continue
            val relationship = (spec.getDictionaryObject(COSName.getPDFName("AFRelationship")) as? COSName)?.name
            if (relationship == "C2PA_Manifest") return true
        }
        return false
    }

    fun extract(context: Context, uri: Uri): PdfMetadata? {
        PDFBoxResourceLoader.init(context)

        var fileName = "Unknown"
        var fileSize = 0L

        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null) {
                fileName = file.name
                fileSize = file.length()
            }
        }

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) {
                    fileName = cursor.getString(nameIndex) ?: fileName
                }
                if (sizeIndex >= 0) {
                    fileSize = cursor.getLong(sizeIndex)
                }
            }
        }

        val inputTempFile = File(context.cacheDir, "extract_${UUID.randomUUID()}.pdf")

        return try {
            val inputStream = if (uri.scheme == "file") {
                uri.path?.let { File(it) }?.takeIf { it.exists() }?.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }

            inputStream?.use { stream ->
                FileOutputStream(inputTempFile).use { fos ->
                    stream.copyTo(fos)
                }
            } ?: return PdfMetadata(uri = uri, fileName = fileName, fileSize = fileSize)

            patchMalformedDicts(inputTempFile)

            val previousForceParsing = System.getProperty("org.apache.pdfbox.forceParsing")
            System.setProperty("org.apache.pdfbox.forceParsing", "true")

            try {
                PDDocument.load(inputTempFile).use { document ->
                    val info = document.documentInformation
                    val infoEntries = extractDictionaryEntries(info.cosObject)
                    val catalogEntries = extractDictionaryEntries(document.documentCatalog.cosObject)

                    val hasXmp = try {
                        document.documentCatalog.metadata != null
                    } catch (_: Exception) {
                        false
                    }

                    val hasAiProvenanceTag = try {
                        val xmpText = document.documentCatalog.metadata?.createInputStream()?.use { it.readBytes() }
                            ?.toString(Charsets.UTF_8) ?: ""
                        AI_PROVENANCE_MARKERS.any { marker -> xmpText.contains(marker, ignoreCase = true) }
                    } catch (_: Exception) { false }

                    val hasC2paManifest = try {
                        detectC2paManifest(document.documentCatalog.cosObject)
                    } catch (_: Exception) { false }

                    var annotationCount = 0
                    for (i in 0 until document.numberOfPages) {
                        try {
                            val page = document.getPage(i)
                            annotationCount += page.annotations.size
                        } catch (_: Exception) {
                        }
                    }

                    val catalog = document.documentCatalog
                    val catalogCos = catalog.cosObject

                    val bookmarkCount = try {
                        countOutlineItems(catalog.documentOutline?.firstChild)
                    } catch (_: Exception) { 0 }

                    val hasOpenAction = try {
                        catalogCos.getDictionaryObject(COSName.getPDFName("OpenAction")) != null
                    } catch (_: Exception) { false }

                    val jsActionCount = try {
                        val names = catalogCos.getDictionaryObject(COSName.getPDFName("Names"))
                        if (names is COSDictionary) {
                            val jsTree = names.getDictionaryObject(COSName.getPDFName("JavaScript"))
                            if (jsTree is COSDictionary) {
                                val namesArr = jsTree.getDictionaryObject(COSName.getPDFName("Names")) as? COSArray
                                namesArr?.size()?.div(2) ?: 0
                            } else 0
                        } else 0
                    } catch (_: Exception) { 0 }

                    val formFieldCount = try {
                        catalog.acroForm?.fields?.size ?: 0
                    } catch (_: Exception) { 0 }

                    val signatureCount = try {
                        catalog.acroForm?.fields
                            ?.count { it.fieldType == "Sig" } ?: 0
                    } catch (_: Exception) { 0 }

                    val ocgLayerCount = try {
                        val ocProps = catalogCos.getDictionaryObject(COSName.getPDFName("OCProperties")) as? COSDictionary
                        val ocgs = ocProps?.getDictionaryObject(COSName.getPDFName("OCGs")) as? COSArray
                        ocgs?.size() ?: if (ocProps != null) 1 else 0
                    } catch (_: Exception) { 0 }

                    val pieceInfo = try {
                        catalogCos.getDictionaryObject(COSName.getPDFName("PieceInfo")) as? COSDictionary
                    } catch (_: Exception) { null }

                    val hasIllustratorLayers = try {
                        pieceInfo != null && (
                            pieceInfo.getDictionaryObject(COSName.getPDFName("Illustrator")) != null ||
                            pieceInfo.getDictionaryObject(COSName.getPDFName("AI")) != null
                        )
                    } catch (_: Exception) { false }

                    val hasInDesignLayers = try {
                        pieceInfo != null && (
                            pieceInfo.getDictionaryObject(COSName.getPDFName("InDesign")) != null ||
                            pieceInfo.getDictionaryObject(COSName.getPDFName("ADBE_InDesign")) != null
                        )
                    } catch (_: Exception) { false }

                    val hasOtherLayers = try {
                        val knownPieceInfoKeys = setOf("Illustrator", "AI", "InDesign", "ADBE_InDesign")
                        val pieceInfoHasUnknownMarkers = pieceInfo?.keySet()?.any { key ->
                            val keyName = (key as? COSName)?.name ?: key.toString()
                            keyName !in knownPieceInfoKeys
                        } == true

                        val hasUnclassifiedOcgLayers =
                            ocgLayerCount > 0 && !hasIllustratorLayers && !hasInDesignLayers

                        pieceInfoHasUnknownMarkers || hasUnclassifiedOcgLayers
                    } catch (_: Exception) { false }

                    PdfMetadata(
                        uri = uri,
                        fileName = fileName,
                        fileSize = fileSize,
                        pageCount = document.numberOfPages,
                        pdfVersion = document.version.toString(),
                        isEncrypted = document.isEncrypted,
                        title = info.title,
                        author = info.author,
                        subject = info.subject,
                        keywords = info.keywords,
                        creator = info.creator,
                        producer = info.producer,
                        creationDate = info.creationDate?.time?.toString(),
                        modDate = info.modificationDate?.time?.toString(),
                        trapped = info.trapped,
                        hasXmp = hasXmp,
                        annotationCount = annotationCount,
                        bookmarkCount = bookmarkCount,
                        jsActionCount = jsActionCount,
                        hasOpenAction = hasOpenAction,
                        hasIllustratorLayers = hasIllustratorLayers,
                        hasInDesignLayers = hasInDesignLayers,
                        hasOtherLayers = hasOtherLayers,
                        ocgLayerCount = ocgLayerCount,
                        formFieldCount = formFieldCount,
                        signatureCount = signatureCount,
                        hasC2paManifest = hasC2paManifest,
                        hasAiProvenanceTag = hasAiProvenanceTag,
                        infoEntries = infoEntries,
                        catalogEntries = catalogEntries
                    )
                }
            } finally {
                if (previousForceParsing != null) {
                    System.setProperty("org.apache.pdfbox.forceParsing", previousForceParsing)
                } else {
                    System.clearProperty("org.apache.pdfbox.forceParsing")
                }
                if (inputTempFile.exists()) inputTempFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Metadata extraction failed for '$fileName': ${e.message}")
            if (inputTempFile.exists()) inputTempFile.delete()
            PdfMetadata(
                uri = uri,
                fileName = fileName,
                fileSize = fileSize
            )
        }
    }

    fun extractFullRawJson(context: Context, uri: Uri): String? {
        PDFBoxResourceLoader.init(context)

        var fileName = "Unknown"
        var fileSize = 0L

        if (uri.scheme == "file") {
            val file = uri.path?.let { File(it) }
            if (file != null) {
                fileName = file.name
                fileSize = file.length()
            }
        }

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex >= 0) fileSize = cursor.getLong(sizeIndex)
            }
        }

        val inputTempFile = File(context.cacheDir, "extract_full_${UUID.randomUUID()}.pdf")

        return try {
            val inputStream = if (uri.scheme == "file") {
                uri.path?.let { File(it) }?.takeIf { it.exists() }?.inputStream()
            } else {
                context.contentResolver.openInputStream(uri)
            }

            inputStream?.use { stream ->
                FileOutputStream(inputTempFile).use { fos -> stream.copyTo(fos) }
            } ?: return null

            patchMalformedDicts(inputTempFile)

            val previousForceParsing = System.getProperty("org.apache.pdfbox.forceParsing")
            System.setProperty("org.apache.pdfbox.forceParsing", "true")

            try {
                PDDocument.load(inputTempFile).use { document ->
                    val infoEntries = extractDictionaryEntriesFull(document.documentInformation.cosObject)
                    val catalogEntries = extractDictionaryEntriesFull(document.documentCatalog.cosObject)
                    val annotations = extractAnnotations(document)

                    buildString {
                        appendLine("{")
                        appendLine("  \"fileName\": ${jsonString(fileName)},")
                        appendLine("  \"fileSize\": $fileSize,")
                        appendLine("  \"uri\": ${jsonString(uri.toString())},")
                        appendLine("  \"pageCount\": ${document.numberOfPages},")
                        appendLine("  \"pdfVersion\": ${jsonString(document.version.toString())},")
                        appendLine("  \"isEncrypted\": ${document.isEncrypted},")
                        appendLine("  \"infoEntries\": ${toJsonObject(infoEntries, "  ")},")
                        appendLine("  \"catalogEntries\": ${toJsonObject(catalogEntries, "  ")},")
                        appendLine("  \"annotations\": ${toJsonArray(annotations, "  ")}")
                        append("}")
                    }
                }
            } finally {
                if (previousForceParsing != null) {
                    System.setProperty("org.apache.pdfbox.forceParsing", previousForceParsing)
                } else {
                    System.clearProperty("org.apache.pdfbox.forceParsing")
                }
                if (inputTempFile.exists()) inputTempFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Full JSON extraction failed for '$fileName': ${e.message}")
            if (inputTempFile.exists()) inputTempFile.delete()
            null
        }
    }

    /**
     * Patches malformed PDF dictionaries (e.g. `<< /Key >>` with no value) using a
     * streaming approach so memory usage is bounded to ~8 MB regardless of file size.
     *
     * Reads [file] in 4 MB chunks, keeps a 512-byte lookahead so patterns never split
     * across a chunk boundary, writes patched output to a sibling temp file, then
     * atomically replaces the original.
     *
     * The replacement is length-preserving (padded with spaces) so PDF byte offsets
     * and the xref table remain valid.
     */
    private fun patchMalformedDicts(file: File) {
        val chunkSize = 4 * 1024 * 1024
        val lookahead = 512
        val parentDir = file.parentFile ?: file.absoluteFile.parentFile ?: return
        val temp = File(parentDir, "${file.name}.patching")
        try {
            file.inputStream().buffered(chunkSize).use { input ->
                temp.outputStream().buffered(chunkSize).use { output ->
                    val pending = StringBuilder(chunkSize + lookahead)
                    val buf = ByteArray(chunkSize)

                    fun flush(reserveTail: Int) {
                        val end = if (reserveTail > 0)
                            (pending.length - reserveTail).coerceAtLeast(0)
                        else pending.length
                        if (end == 0) return
                        val patched = pending.substring(0, end).replace(MALFORMED_DICT_PATTERN) { m ->
                            "<< >>".padEnd(m.value.length)
                        }
                        output.write(patched.toByteArray(Charsets.ISO_8859_1))
                        pending.delete(0, end)
                    }

                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        pending.append(String(buf, 0, n, Charsets.ISO_8859_1))
                        flush(lookahead)
                    }
                    flush(0)
                }
            }
            file.delete()
            temp.renameTo(file)
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun extractDictionaryEntries(dictionary: COSDictionary?): Map<String, String> {
        if (dictionary == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        var count = 0
        for (key in dictionary.keySet()) {
            if (count >= MAX_DICT_ENTRIES) {
                map["__truncated__"] = "Only first $MAX_DICT_ENTRIES entries are shown"
                break
            }
            val keyName = (key as? COSName)?.name ?: key.toString()
            val value = dictionary.getDictionaryObject(key) as? COSBase
            map[keyName] = summarizeCosValue(value)
            count++
        }
        return map
    }

    private fun cosToString(value: COSBase?, depth: Int = 0): String = when (value) {
        null -> "null"
        is COSString -> value.getString()
        is COSName -> value.name
        is COSNumber -> {
            val f = value.floatValue()
            val i = value.intValue()
            if (f == i.toFloat()) i.toString() else f.toString()
        }
        is COSBoolean -> value.getValue().toString()
        is COSObject -> if (depth < 2) cosToString(value.getObject(), depth + 1) else "…"
        is COSStream -> {
            val t = (value.getDictionaryObject(COSName.getPDFName("Type")) as? COSName)?.name ?: ""
            val s = (value.getDictionaryObject(COSName.getPDFName("Subtype")) as? COSName)?.name ?: ""
            when {
                s.isNotEmpty() -> "stream:$s"
                t.isNotEmpty() -> "stream:$t"
                else -> "stream"
            }
        }
        is COSDictionary -> {
            if (depth > 1) "…"
            else {
                val t = (value.getDictionaryObject(COSName.getPDFName("Type")) as? COSName)?.name ?: ""
                val s = (value.getDictionaryObject(COSName.getPDFName("Subtype")) as? COSName)?.name ?: ""
                when {
                    t.isNotEmpty() && s.isNotEmpty() -> "$t/$s"
                    s.isNotEmpty() -> s
                    t.isNotEmpty() -> t
                    else -> "{${value.keySet().size} entries}"
                }
            }
        }
        is COSArray -> {
            if (depth > 1) "[…]"
            else {
                val count = value.size()
                val items = (0 until minOf(count, 4)).map { i -> cosToString(value.getObject(i), depth + 1) }
                val suffix = if (count > 4) " +${count - 4}" else ""
                "[${items.joinToString(", ")}$suffix]"
            }
        }
        else -> value.toString()
    }

    private fun summarizeCosValue(value: COSBase?): String {
        val raw = cosToString(value)
        return if (raw.length > MAX_VALUE_CHARS) raw.take(MAX_VALUE_CHARS) + "…" else raw
    }

    private fun extractDictionaryEntriesFull(dictionary: COSDictionary?): Map<String, String> {
        if (dictionary == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        for (key in dictionary.keySet()) {
            val keyName = (key as? COSName)?.name ?: key.toString()
            val value = dictionary.getDictionaryObject(key) as? COSBase
            map[keyName] = summarizeCosValueFull(value)
        }
        return map
    }

    private fun summarizeCosValueFull(value: COSBase?): String {
        val raw = cosToString(value)
        return if (raw.length > MAX_VALUE_CHARS) raw.take(MAX_VALUE_CHARS) + "…" else raw
    }

    private fun extractAnnotations(document: PDDocument): List<AnnotationEntry> {
        val out = mutableListOf<AnnotationEntry>()
        for (index in 0 until document.numberOfPages) {
            val page = runCatching { document.getPage(index) }.getOrNull() ?: continue
            val pageAnnotations = runCatching { page.annotations }.getOrDefault(emptyList<PDAnnotation>())
            for (annotation in pageAnnotations) {
                val dict = extractDictionaryEntriesFull(annotation.cosObject)
                out += AnnotationEntry(
                    pageIndex = index,
                    subtype = annotation.subtype ?: "Unknown",
                    contents = annotation.contents ?: "",
                    rawEntries = dict
                )
            }
        }
        return out
    }

    private fun jsonString(value: String): String {
        return "\"" + value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
    }

    private fun toJsonObject(map: Map<String, String>, indent: String): String {
        if (map.isEmpty()) return "{}"
        val body = map.entries.joinToString(",\n") { (k, v) ->
            "$indent  ${jsonString(k)}: ${jsonString(v)}"
        }
        return "{\n$body\n$indent}"
    }

    private fun toJsonArray(items: List<AnnotationEntry>, indent: String): String {
        if (items.isEmpty()) return "[]"
        val body = items.joinToString(",\n") { item ->
            buildString {
                appendLine("$indent  {")
                appendLine("$indent    \"pageIndex\": ${item.pageIndex},")
                appendLine("$indent    \"subtype\": ${jsonString(item.subtype)},")
                appendLine("$indent    \"contents\": ${jsonString(item.contents)},")
                appendLine("$indent    \"rawEntries\": ${toJsonObject(item.rawEntries, "$indent    ")}")
                append("$indent  }")
            }
        }
        return "[\n$body\n$indent]"
    }

    private data class AnnotationEntry(
        val pageIndex: Int,
        val subtype: String,
        val contents: String,
        val rawEntries: Map<String, String>
    )

    private fun countOutlineItems(item: PDOutlineItem?): Int {
        var n = 0
        var cur = item
        while (cur != null) {
            n++
            n += countOutlineItems(cur.firstChild)
            cur = cur.nextSibling
        }
        return n
    }
}
