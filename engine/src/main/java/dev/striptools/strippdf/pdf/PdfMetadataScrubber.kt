package dev.striptools.strippdf.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.contentstream.operator.OperatorName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Maximum bitmap pixels per page before DPI is auto-reduced to stay under ~64 MB ARGB_8888.
private const val MAX_BITMAP_PIXELS = 16_000_000
private const val SCRUB_TAG = "PdfMetadataScrubber"
private val MALFORMED_DICT_PATTERN = Regex("""<<\s*(/[A-Za-z][A-Za-z0-9]*)\s*>>""")

sealed class ScrubOutcome {
    data class Success(val file: File) : ScrubOutcome()
    /** PDF is encrypted; no password was supplied. */
    object NeedsPassword : ScrubOutcome()
    /** PDF is encrypted and the supplied password did not unlock it. */
    object WrongPassword : ScrubOutcome()
    /** [errorCode] is one of the ERR_* constants; [reason] is the human-readable message. */
    data class Failed(val reason: String, val errorCode: String = ERR_PROCESSING_FAILED) : ScrubOutcome()

    companion object {
        const val ERR_UNSUPPORTED_FORMAT = "ERR_UNSUPPORTED_FORMAT"
        const val ERR_ACCESS_DENIED      = "ERR_ACCESS_DENIED"
        const val ERR_PROCESSING_FAILED  = "ERR_PROCESSING_FAILED"
        const val ERR_CANCELLED          = "ERR_CANCELLED"
    }
}

object PdfMetadataScrubber {
    fun scrub(
        context: Context,
        sourceUri: Uri,
        originalFileName: String,
        annotationMode: AnnotationMode = AnnotationMode.KEEP,
        preserveBookmarks: Boolean = false,
        removeDocumentInfo: Boolean = true,
        removeXmp: Boolean = true,
        signaturePolicy: SignaturePolicy = SignaturePolicy.PRESERVE_ALL,
        rasterizeOutput: Boolean = false,
        outputPreset: String = "Balanced",
        password: String? = null,
        keepPassword: Boolean = false,
        onPageProgress: ((current: Int, total: Int) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
        onError: ((String) -> Unit)? = null
    ): ScrubOutcome {
        PDFBoxResourceLoader.init(context)
        
        val prefix = "Vantre_"
        val stripped = originalFileName
            .removePrefix("Vantre_")
            .removePrefix("Strip_")
        val outFileName = "$prefix$stripped"
        val tempFile = File(context.cacheDir, outFileName)
        
        return try {
            // Copy the URI content to a local temp file first.
            // File-based loading uses random-access parsing which is
            // significantly more tolerant of malformed PDF structures
            // (e.g. PDFGear signed documents with broken dictionaries).
            val inputTempFile = File(context.cacheDir, "input_${UUID.randomUUID()}.pdf")
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                FileOutputStream(inputTempFile).use { fos ->
                    inputStream.copyTo(fos)
                }
            } ?: run {
                onError?.invoke("Could not read source file")
                return ScrubOutcome.Failed(
                    context.getString(dev.striptools.strippdf.R.string.scrub_error_cannot_read_source),
                    ScrubOutcome.ERR_ACCESS_DENIED
                )
            }

            // Pre-process: fix malformed PDF dictionaries for small files only.
            // PDFGear (and some other tools) produce broken syntax like
            // << /F >> where the dictionary key has no value. This causes
            // PDFBox's BaseParser to throw "Unknown dir object c='>'"
            // because it hits >> where it expects a value.
            // We patch these to << /F null >> which is valid PDF.
            patchMalformedDicts(inputTempFile)

            // Enable lenient parsing for other edge cases
            val previousForceParsing = System.getProperty("org.apache.pdfbox.forceParsing")
            System.setProperty("org.apache.pdfbox.forceParsing", "true")

            try {
                val loadedDocument = try {
                    PDDocument.load(inputTempFile, password ?: "")
                } catch (e: InvalidPasswordException) {
                    return if (password.isNullOrEmpty()) {
                        ScrubOutcome.NeedsPassword
                    } else {
                        ScrubOutcome.WrongPassword
                    }
                }
                // Owner-only encryption: load with "" succeeded but the file is still
                // encrypted (permissions restrictions only). Treat the same as user-password
                // so the user is informed before we strip protection.
                if (loadedDocument.isEncrypted && password.isNullOrEmpty()) {
                    loadedDocument.close()
                    return ScrubOutcome.NeedsPassword
                }
                loadedDocument.use { document ->
                    if (!keepPassword) {
                        // Strip all security so the saved output is unprotected.
                        document.setAllSecurityToBeRemoved(true)
                    } else if (document.isEncrypted && !password.isNullOrEmpty()) {
                        // Re-encrypt with the same password on save.
                        val perms = AccessPermission()
                        val policy = StandardProtectionPolicy(password, password, perms)
                        policy.encryptionKeyLength = 128
                        document.protect(policy)
                    }
                    val catalog = document.documentCatalog
                    
                    // 1. Wipe Info Dictionary (Scorched Earth)
                    if (removeDocumentInfo) {
                        try {
                            val info = document.documentInformation
                            if (info != null) {
                                val cos = info.cosObject
                                if (cos != null) {
                                    val keys = cos.keySet().toMutableList()
                                    for (key in keys) {
                                        cos.removeItem(key)
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                        document.documentInformation = com.tom_roush.pdfbox.pdmodel.PDDocumentInformation()
                    }
                    
                    // 2. Wipe XMP Metadata (Catalog level)
                    if (removeXmp) {
                        catalog.metadata = null
                    }
                    
                    // 3. Remove OutputIntents (ICC Profiles/System strings)
                    catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("OutputIntents"))
                    
                    // 4. Remove PieceInfo (Private App Data)
                    catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("PieceInfo"))
                    
                    // 5. Remove Names (Embedded files, JS, Dest names)
                    if (!preserveBookmarks) {
                        catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("Names"))
                    } else {
                        val names = catalog.cosObject.getDictionaryObject(COSName.getPDFName("Names")) as? COSDictionary
                        names?.removeItem(COSName.getPDFName("JavaScript"))
                        names?.removeItem(COSName.getPDFName("EmbeddedFiles"))
                        names?.removeItem(COSName.getPDFName("Dests"))
                        names?.removeItem(COSName.getPDFName("AlternatePresentations"))
                        names?.removeItem(COSName.getPDFName("Renditions"))
                    }
                    // Remove associated-file hooks that can reference embedded content.
                    catalog.cosObject.removeItem(COSName.getPDFName("AF"))
                    
                    // 6. Remove OpenAction & JavaScript triggers
                    catalog.cosObject.removeItem(COSName.getPDFName("OpenAction"))
                    catalog.cosObject.removeItem(COSName.getPDFName("AA"))

                    // 6b. Remove optional content/layer configuration
                    catalog.cosObject.removeItem(COSName.getPDFName("OCProperties"))
                    
                    // 7. Remove StructTreeRoot (Tagged PDF/Accessibility)
                    catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("StructTreeRoot"))
                    catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("MarkInfo"))
                    
                    // 8. Remove ViewerPreferences
                    catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("ViewerPreferences"))
                    
                    // 9. Wipe PageLabels
                    catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("PageLabels"))

                    // 9b. Remove Digital Signature Permissions & References
                    catalog.cosObject.removeItem(COSName.getPDFName("Perms"))
                    catalog.cosObject.removeItem(COSName.getPDFName("DSS"))
                    catalog.cosObject.removeItem(COSName.getPDFName("Extensions"))

                    // 9c. Remove Outlines (Bookmarks)
                    if (!preserveBookmarks) {
                        catalog.documentOutline = null
                        catalog.pageMode = com.tom_roush.pdfbox.pdmodel.PageMode.USE_NONE
                    }

                    val preservedBookmarkActions = if (preserveBookmarks) {
                        collectBookmarkActionDictionaries(catalog.documentOutline?.firstChild)
                    } else {
                        emptySet()
                    }

                    // 10. Regenerate Document ID and force clean full-save output.
                    document.document.documentID = COSArray().apply {
                        add(COSString(UUID.randomUUID().toString()))
                        add(COSString(UUID.randomUUID().toString()))
                    }

                    // 11. Page-Level Scrubbing
                    for (i in 0 until document.numberOfPages) {
                        val page = document.getPage(i)
                        val pageCos = page.cosObject
                        
                        pageCos.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("PieceInfo"))
                        if (removeXmp) {
                            pageCos.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("Metadata"))
                        }
                        pageCos.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("StructParents"))
                        pageCos.removeItem(COSName.getPDFName("AA"))
                        pageCos.removeItem(COSName.getPDFName("OpenAction"))
                        pageCos.removeItem(COSName.getPDFName("OCParents"))
                        flattenOptionalContentOnPage(page, document)

                        try {
                            for (annot in page.annotations) {
                                // Keep visual annotation content unless requested otherwise,
                                // but always strip interactive hooks.
                                stripInteractiveActionKeys(annot.cosObject)
                                annot.cosObject.removeItem(COSName.getPDFName("Dest"))
                                annot.cosObject.removeItem(COSName.getPDFName("OC"))
                                annot.cosObject.removeItem(COSName.getPDFName("OCMD"))
                            }
                        } catch (e: Exception) {}
                        
                        // A. Wipe Metadata from Page-level objects (Images, etc.)
                        val resources = page.resources
                        if (resources != null) {
                            val xObjectNames = resources.xObjectNames
                            for (name in xObjectNames) {
                                val xObject = resources.getXObject(name)
                                if (xObject is PDFormXObject) {
                                    scrubOptionalContentFromForm(xObject, document)
                                }
                                if (xObject is com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject) {
                                    // 1. Wipe PDF-level metadata entries for the image
                                    if (removeXmp) {
                                        xObject.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("Metadata"))
                                    }
                                    xObject.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("PieceInfo"))

                                    // 2. Forensic Raster Re-Baking (Deep Binary Scrubbing)
                                    // This removes Ducky, APP14, and other embedded image markers
                                    if (removeXmp) {
                                        try {
                                            val bitmap = xObject.image
                                            if (bitmap != null) {
                                                val outStream = java.io.ByteArrayOutputStream()
                                                // Re-encode at high quality to preserve visuals but kill metadata
                                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, outStream)
                                                val cleanImage = com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromStream(document, java.io.ByteArrayInputStream(outStream.toByteArray()))
                                                
                                                // Replace the original XObject in page resources with the cleaned version
                                                resources.put(name, cleanImage)
                                            }
                                        } catch (e: Exception) {
                                            // Fallback: if re-baking fails, the object is still stripped of /Metadata
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 12. Deep Object & Stream Sweep (Aggressive XMP packet destruction)
                    val objects = document.document.objects
                    val emptyXmp = "<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n" +
                                   "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"></x:xmpmeta>\n" +
                                   "<?xpacket end=\"w\"?>"
                                   
                    for (obj in objects) {
                        val cosBase = obj.getObject()
                        if (cosBase is com.tom_roush.pdfbox.cos.COSDictionary) {
                            if (removeXmp) {
                                cosBase.removeItem(COSName.METADATA)
                            }
                            cosBase.removeItem(COSName.getPDFName("PieceInfo"))
                            if (removeXmp) {
                                cosBase.removeItem(COSName.getPDFName("XMP"))
                            }
                            cosBase.removeItem(COSName.getPDFName("OC"))
                            cosBase.removeItem(COSName.getPDFName("OCMD"))

                            if (!preserveBookmarks) {
                                cosBase.removeItem(COSName.getPDFName("Outlines"))
                                cosBase.removeItem(COSName.getPDFName("Dests"))
                            }

                            val keepBookmarkAction = preserveBookmarks && cosBase in preservedBookmarkActions
                            if (!keepBookmarkAction) {
                                if (looksLikeActionDictionary(cosBase)) {
                                    clearDictionary(cosBase)
                                } else {
                                    stripActionPayloadKeys(cosBase)
                                }
                            }

                            scrubPotentialFileSpec(cosBase)
                            deepScrubCosValue(
                                base = cosBase,
                                preserveBookmarks = preserveBookmarks,
                                removeXmp = removeXmp,
                                preservedBookmarkActions = preservedBookmarkActions
                            )
                        }
                        if (cosBase is com.tom_roush.pdfbox.cos.COSStream) {
                            try {
                                if (removeXmp) {
                                    cosBase.createInputStream().use { stream ->
                                        val buffer = ByteArray(1024)
                                        val bytesRead = stream.read(buffer)
                                        if (bytesRead > 0) {
                                            val content = String(buffer, 0, bytesRead)
                                            if (content.contains("<?xpacket")) {
                                                cosBase.createOutputStream().use { out ->
                                                    out.write(emptyXmp.toByteArray())
                                                }
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }

                    if (annotationMode != AnnotationMode.KEEP) {
                        catalog.acroForm?.cosObject?.let { scrubFieldTree(it) }
                    }
                    
                    // 13. Annotation Handling (Pro Feature)
                    when (annotationMode) {
                        AnnotationMode.REMOVE -> {
                            // 1. Deep-wipe AcroForm
                            catalog.acroForm?.let { form ->
                                // Clear the fields list to sever connections to signatures/widgets
                                try { form.fields = emptyList() } catch (e: Exception) {}
                            }
                            catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("AcroForm"))
                            
                            // 2. Clear Annots on every page
                            for (i in 0 until document.numberOfPages) {
                                val page = document.getPage(i)
                                try {
                                    for (annot in page.annotations) {
                                        // Target Signature specific keys (/V is the signature dictionary)
                                        annot.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("V"))
                                        annot.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("AP")) // Appearance
                                    }
                                } catch (e: Exception) {}
                                page.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("Annots"))
                            }
                        }
                        AnnotationMode.FLATTEN -> {
                            // A. Flatten AcroForm (Interactive fields)
                            try {
                                catalog.acroForm?.let { form ->
                                    form.flatten()
                                }
                            } catch (e: Exception) {}

                            // B. Flatten other annotations (Stamps, highlights, etc.)
                            for (i in 0 until document.numberOfPages) {
                                val page = document.getPage(i)
                                val annotations = page.annotations
                                if (annotations.isNotEmpty()) {
                                    try {
                                        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                                            for (annot in annotations) {
                                                val appearance = annot.normalAppearanceStream
                                                if (appearance != null) {
                                                    val rect = annot.rectangle
                                                    val matrix = com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(rect.lowerLeftX, rect.lowerLeftY)
                                                    cs.saveGraphicsState()
                                                    cs.transform(matrix)
                                                    cs.drawForm(appearance)
                                                    cs.restoreGraphicsState()
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {}
                                    page.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("Annots"))
                                }
                            }
                            
                            // C. Final Purge: Remove the AcroForm container itself
                            catalog.cosObject.removeItem(com.tom_roush.pdfbox.cos.COSName.getPDFName("AcroForm"))
                        }
                        AnnotationMode.KEEP -> { /* Do nothing */ }
                    }

                    if (annotationMode == AnnotationMode.KEEP) {
                        applySignaturePolicy(catalog, signaturePolicy)
                    }

                    // 14. Save to temp file
                    if (rasterizeOutput) {
                        val completed = rasterizeAndSave(document, tempFile, outputPreset, onPageProgress, isCancelled)
                        if (!completed) return ScrubOutcome.Failed("Cancelled", ScrubOutcome.ERR_CANCELLED)
                    } else {
                        FileOutputStream(tempFile).use { outputStream ->
                            document.save(outputStream)
                        }
                    }

                    // 15. Backdate to Unix Epoch
                    tempFile.setLastModified(0L)

                    ScrubOutcome.Success(tempFile)
                }
            } finally {
                // Restore force-parsing property
                if (previousForceParsing != null) {
                    System.setProperty("org.apache.pdfbox.forceParsing", previousForceParsing)
                } else {
                    System.clearProperty("org.apache.pdfbox.forceParsing")
                }
                // Clean up the input temp file
                if (inputTempFile.exists()) inputTempFile.delete()
            }
        } catch (e: InvalidPasswordException) {
            if (tempFile.exists()) tempFile.delete()
            if (password.isNullOrEmpty()) ScrubOutcome.NeedsPassword else ScrubOutcome.WrongPassword
        } catch (e: Exception) {
            val (errorCode, reason) = classifyScrubError(context, e)
            Log.w(SCRUB_TAG, "Failed to scrub '$originalFileName' [$errorCode]: $reason")
            onError?.invoke(reason)
            if (tempFile.exists()) tempFile.delete()
            ScrubOutcome.Failed(reason, errorCode)
        }
    }

    /** Returns Pair(errorCode, humanReadableReason). */
    private fun classifyScrubError(context: Context, error: Throwable): Pair<String, String> {
        val msg = (error.message ?: "").lowercase()
        return when {
            "end-of-file" in msg || "%%eof" in msg || "offset 0" in msg || "missing end of file" in msg ->
                ScrubOutcome.ERR_UNSUPPORTED_FORMAT to context.getString(dev.striptools.strippdf.R.string.scrub_error_incomplete_file)
            "password" in msg || "encrypted" in msg ->
                ScrubOutcome.ERR_UNSUPPORTED_FORMAT to context.getString(dev.striptools.strippdf.R.string.scrub_error_password_protected)
            "access" in msg || "permission" in msg || "security" in msg ->
                ScrubOutcome.ERR_ACCESS_DENIED to context.getString(dev.striptools.strippdf.R.string.scrub_error_access_denied)
            else ->
                ScrubOutcome.ERR_PROCESSING_FAILED to context.getString(dev.striptools.strippdf.R.string.scrub_error_processing_failed)
        }
    }

    /**
     * Patches malformed PDF dictionaries where a key has no value.
     * Example: << /F >> becomes << /F () >> (empty string value).
     * Length-preserving: we pad with spaces so byte offsets don't shift
     * and the xref table stays valid (no brute-force recovery needed).
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
}

private val ACTION_PAYLOAD_KEYS = listOf(
    "A",
    "AA",
    "OpenAction",
    "JS",
    "JavaScript",
    "Next",
    "Dest"
)

private val FILESPEC_KEYS = listOf(
    "EF",
    "RF",
    "F",
    "UF",
    "DOS",
    "Mac",
    "Unix",
    "CI",
    "Desc",
    "AF",
    "Filespec",
    "EmbeddedFile",
    "EmbeddedFiles"
)

private val ACTION_SUBTYPES = setOf(
    "JavaScript",
    "Launch",
    "URI",
    "GoToR",
    "GoToE",
    "Named",
    "SubmitForm",
    "ResetForm",
    "ImportData",
    "Hide",
    "SetOCGState",
    "Rendition",
    "Sound",
    "Movie",
    "RichMediaExecute"
)

private fun stripInteractiveActionKeys(dictionary: COSDictionary) {
    ACTION_PAYLOAD_KEYS.forEach { key ->
        dictionary.removeItem(COSName.getPDFName(key))
    }
}

private fun stripActionPayloadKeys(dictionary: COSDictionary) {
    stripInteractiveActionKeys(dictionary)
    dictionary.removeItem(COSName.getPDFName("JS"))
    dictionary.removeItem(COSName.getPDFName("JavaScript"))
}

private fun looksLikeActionDictionary(dictionary: COSDictionary): Boolean {
    val subtype = dictionary.getDictionaryObject(COSName.getPDFName("S"))
    val subtypeName = when (subtype) {
        is COSName -> subtype.name
        else -> null
    }
    return subtypeName != null && subtypeName in ACTION_SUBTYPES
}

private fun clearDictionary(dictionary: COSDictionary) {
    dictionary.keySet().toList().forEach { key ->
        dictionary.removeItem(key)
    }
}

private fun scrubPotentialFileSpec(dictionary: COSDictionary) {
    val type = (dictionary.getDictionaryObject(COSName.TYPE) as? COSName)?.name
    if (type == "Filespec" || type == "EmbeddedFile") {
        clearDictionary(dictionary)
        return
    }

    FILESPEC_KEYS.forEach { key ->
        dictionary.removeItem(COSName.getPDFName(key))
    }
}

private fun deepScrubCosValue(
    base: COSBase?,
    preserveBookmarks: Boolean,
    removeXmp: Boolean,
    preservedBookmarkActions: Set<COSDictionary>,
    visited: MutableSet<COSDictionary> = mutableSetOf()
) {
    when (base) {
        is COSObject -> deepScrubCosValue(base.getObject(), preserveBookmarks, removeXmp, preservedBookmarkActions, visited)
        is COSArray -> {
            for (i in 0 until base.size()) {
                deepScrubCosValue(base.getObject(i), preserveBookmarks, removeXmp, preservedBookmarkActions, visited)
            }
        }
        is COSDictionary -> {
            if (!visited.add(base)) return

            if (removeXmp) {
                base.removeItem(COSName.METADATA)
                base.removeItem(COSName.getPDFName("XMP"))
            }

            base.removeItem(COSName.getPDFName("PieceInfo"))
            base.removeItem(COSName.getPDFName("OC"))
            base.removeItem(COSName.getPDFName("OCMD"))

            if (!preserveBookmarks) {
                base.removeItem(COSName.getPDFName("Outlines"))
                base.removeItem(COSName.getPDFName("Dests"))
            }

            stripActionPayloadKeys(base)
            scrubPotentialFileSpec(base)

            val keepBookmarkAction = preserveBookmarks && base in preservedBookmarkActions
            if (!keepBookmarkAction && looksLikeActionDictionary(base)) {
                clearDictionary(base)
                return
            }

            base.keySet().toList().forEach { key ->
                deepScrubCosValue(base.getDictionaryObject(key), preserveBookmarks, removeXmp, preservedBookmarkActions, visited)
            }
        }
    }
}

private fun scrubFieldTree(root: COSDictionary, visited: MutableSet<COSDictionary> = mutableSetOf()) {
    if (!visited.add(root)) return

    stripInteractiveActionKeys(root)

    val kids = root.getDictionaryObject(COSName.KIDS)
    if (kids is COSArray) {
        for (index in 0 until kids.size()) {
            val child = resolveDictionary(kids.getObject(index)) ?: continue
            scrubFieldTree(child, visited)
        }
    }
}

private fun resolveDictionary(base: COSBase?): COSDictionary? = when (base) {
    is COSDictionary -> base
    is COSObject -> base.getObject() as? COSDictionary
    else -> null
}

private fun collectBookmarkActionDictionaries(start: PDOutlineItem?): Set<COSDictionary> {
    val actions = mutableSetOf<COSDictionary>()
    val visitedActions = mutableSetOf<COSDictionary>()

    fun walk(item: PDOutlineItem?) {
        var current = item
        while (current != null) {
            val action = resolveDictionary(current.cosObject.getDictionaryObject(COSName.getPDFName("A")))
            if (action != null) {
                collectActionChain(action, actions, visitedActions)
            }
            walk(current.firstChild)
            current = current.nextSibling
        }
    }

    walk(start)
    return actions
}

private fun collectActionChain(
    action: COSDictionary,
    out: MutableSet<COSDictionary>,
    visited: MutableSet<COSDictionary>
) {
    if (!visited.add(action)) return
    out += action

    val next = action.getDictionaryObject(COSName.getPDFName("Next"))
    when (next) {
        is COSDictionary -> collectActionChain(next, out, visited)
        is COSObject -> {
            val nextDict = next.getObject() as? COSDictionary
            if (nextDict != null) collectActionChain(nextDict, out, visited)
        }
        is COSArray -> {
            for (i in 0 until next.size()) {
                val nextDict = resolveDictionary(next.getObject(i))
                if (nextDict != null) collectActionChain(nextDict, out, visited)
            }
        }
    }
}

private fun flattenOptionalContentOnPage(page: com.tom_roush.pdfbox.pdmodel.PDPage, document: PDDocument) {
    val rewritten = rewriteOptionalContentTokens(PDFStreamParser(page).apply { parse() }.tokens)
    val newStream = PDStream(document)
    newStream.createOutputStream().use { out ->
        ContentStreamWriter(out).writeTokens(rewritten)
    }
    page.setContents(newStream)
}

private fun scrubOptionalContentFromForm(
    form: PDFormXObject,
    document: PDDocument,
    visited: MutableSet<COSDictionary> = mutableSetOf()
) {
    val formDict = form.cosObject
    if (!visited.add(formDict)) return

    formDict.removeItem(COSName.getPDFName("OC"))
    formDict.removeItem(COSName.getPDFName("OCMD"))

    val rewritten = rewriteOptionalContentTokens(PDFStreamParser(form).apply { parse() }.tokens)
    formDict.createOutputStream().use { out ->
        ContentStreamWriter(out).writeTokens(rewritten)
    }

    val resources = form.resources ?: return
    for (name in resources.xObjectNames) {
        val child = resources.getXObject(name)
        if (child is PDFormXObject) {
            scrubOptionalContentFromForm(child, document, visited)
        }
    }
}

private fun rewriteOptionalContentTokens(tokens: List<Any>): List<Any> {
    val output = mutableListOf<Any>()
    val operands = mutableListOf<Any>()
    var optionalContentDepth = 0

    fun flushOperands() {
        if (operands.isNotEmpty()) {
            output.addAll(operands)
            operands.clear()
        }
    }

    for (token in tokens) {
        when (token) {
            is Operator -> {
                when (token.name) {
                    OperatorName.BEGIN_MARKED_CONTENT,
                    OperatorName.BEGIN_MARKED_CONTENT_SEQ -> {
                        val tag = operands.firstOrNull() as? COSName
                        val isOptionalContentWrapper = tag?.name == "OC"
                        if (isOptionalContentWrapper) {
                            optionalContentDepth += 1
                            operands.clear()
                        } else if (optionalContentDepth == 0) {
                            flushOperands()
                            output.add(token)
                        } else {
                            operands.clear()
                        }
                    }

                    OperatorName.END_MARKED_CONTENT -> {
                        if (optionalContentDepth > 0) {
                            optionalContentDepth -= 1
                            operands.clear()
                        } else {
                            flushOperands()
                            output.add(token)
                        }
                    }

                    else -> {
                        if (optionalContentDepth == 0) {
                            flushOperands()
                            output.add(token)
                        } else {
                            operands.clear()
                        }
                    }
                }
            }

            else -> {
                if (optionalContentDepth == 0) {
                    operands.add(token)
                }
            }
        }
    }

    flushOperands()
    return output
}

private fun rasterizeAndSave(
    document: PDDocument,
    tempFile: File,
    outputPreset: String,
    onPageProgress: ((current: Int, total: Int) -> Unit)? = null,
    isCancelled: (() -> Boolean)? = null
): Boolean {
    val (dpi, jpegQuality, useLosslessPng) = when (outputPreset) {
        "Fast" -> Triple(72f, 0.65f, false)
        "Maximum fidelity" -> Triple(300f, 0.95f, false)
        "Maximum fidelity (lossless)" -> Triple(300f, 1.0f, true)
        else -> Triple(150f, 0.85f, false)
    }

    // Use Android's PdfRenderer for rasterization fidelity (fonts/layers/blending),
    // then pack rendered pages into a new image-only PDF via PDFBox.
    val sourceForRender = File(tempFile.parentFile ?: tempFile.absoluteFile.parentFile, "raster_source_${System.currentTimeMillis()}.pdf")
    val rasterized = PDDocument()

    try {
        FileOutputStream(sourceForRender).use { outputStream ->
            document.save(outputStream)
        }

        ParcelFileDescriptor.open(sourceForRender, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val scale = dpi / 72f

                for (index in 0 until renderer.pageCount) {
                    if (isCancelled?.invoke() == true) {
                        return false
                    }
                    renderer.openPage(index).use { renderPage ->
                        val pageWidthPts = renderPage.width.toFloat()
                        val pageHeightPts = renderPage.height.toFloat()

                        // OOM guard: if projected pixels exceed threshold, scale DPI down
                        // proportionally so the bitmap stays within the safe pixel budget.
                        val projectedPixels = (pageWidthPts * scale).roundToInt().toLong() *
                                             (pageHeightPts * scale).roundToInt().toLong()
                        val safeDpi = if (projectedPixels > MAX_BITMAP_PIXELS) {
                            val reduction = sqrt(MAX_BITMAP_PIXELS.toFloat() / projectedPixels)
                            dpi * reduction
                        } else {
                            dpi
                        }
                        val safeScale = safeDpi / 72f
                        val bitmapWidthPx = (pageWidthPts * safeScale).roundToInt().coerceAtLeast(1)
                        val bitmapHeightPx = (pageHeightPts * safeScale).roundToInt().coerceAtLeast(1)

                        val bitmap = Bitmap.createBitmap(bitmapWidthPx, bitmapHeightPx, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)

                        val matrix = Matrix().apply { setScale(safeScale, safeScale) }
                        renderPage.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                        val page = PDPage(PDRectangle(pageWidthPts, pageHeightPts))
                        page.rotation = 0
                        rasterized.addPage(page)

                        val imageXObject = if (useLosslessPng) {
                            com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(rasterized, bitmap)
                        } else {
                            val jpegBytes = ByteArrayOutputStream().use { out ->
                                bitmap.compress(Bitmap.CompressFormat.JPEG, (jpegQuality * 100f).toInt(), out)
                                out.toByteArray()
                            }
                            JPEGFactory.createFromStream(rasterized, ByteArrayInputStream(jpegBytes))
                        }

                        PDPageContentStream(rasterized, page, PDPageContentStream.AppendMode.OVERWRITE, true, true).use { cs ->
                            cs.drawImage(imageXObject, 0f, 0f, pageWidthPts, pageHeightPts)
                        }

                        bitmap.recycle()
                        onPageProgress?.invoke(index + 1, renderer.pageCount)
                    }
                }
            }
        }

        rasterized.document.documentID = COSArray().apply {
            add(COSString(UUID.randomUUID().toString()))
            add(COSString(UUID.randomUUID().toString()))
        }

        FileOutputStream(tempFile).use { outputStream ->
            rasterized.save(outputStream)
        }
    } finally {
        rasterized.close()
        sourceForRender.delete()
    }
    return true
}

private fun applySignaturePolicy(
    catalog: com.tom_roush.pdfbox.pdmodel.PDDocumentCatalog,
    signaturePolicy: SignaturePolicy
) {
    if (signaturePolicy == SignaturePolicy.PRESERVE_ALL) return

    val form = catalog.acroForm ?: return
    val existingFields = runCatching { form.fields }.getOrNull() ?: return
    if (existingFields.isEmpty()) return

    val retainedFields = existingFields.filter { field ->
        if (field.fieldType != "Sig") {
            true
        } else {
            when (signaturePolicy) {
                SignaturePolicy.PRESERVE_ALL -> true
                SignaturePolicy.REMOVE_ALL -> false
                SignaturePolicy.REMOVE_UNSIGNED_ONLY -> {
                    field.cosObject.getDictionaryObject(COSName.getPDFName("V")) != null
                }
            }
        }
    }

    if (retainedFields.size != existingFields.size) {
        runCatching {
            form.fields = retainedFields
        }
    }
}
