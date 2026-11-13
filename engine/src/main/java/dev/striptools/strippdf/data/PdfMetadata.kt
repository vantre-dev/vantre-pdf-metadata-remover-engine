package dev.striptools.strippdf.data

import android.net.Uri
import dev.striptools.strippdf.office.OfficeReport

/**
 * Audit result for one selected document. Named for its PDF origins; Word, Excel and
 * PowerPoint files use the shared fields (title, author, dates, ...) plus [office].
 */
data class PdfMetadata(
    val uri: Uri,
    val fileName: String,
    val fileSize: Long,
    val format: DocumentFormat = DocumentFormat.PDF,
    val office: OfficeReport? = null,
    val pageCount: Int = 0,
    val pdfVersion: String? = null,
    val isEncrypted: Boolean = false,
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String? = null,
    val producer: String? = null,
    val creationDate: String? = null,
    val modDate: String? = null,
    val trapped: String? = null,
    val hasXmp: Boolean = false,
    val annotationCount: Int = 0,
    val bookmarkCount: Int = 0,
    val jsActionCount: Int = 0,
    val hasOpenAction: Boolean = false,
    val hasIllustratorLayers: Boolean = false,
    val hasInDesignLayers: Boolean = false,
    val hasOtherLayers: Boolean = false,
    val ocgLayerCount: Int = 0,
    val formFieldCount: Int = 0,
    val signatureCount: Int = 0,
    val hasC2paManifest: Boolean = false,
    val hasAiProvenanceTag: Boolean = false,
    val infoEntries: Map<String, String> = emptyMap(),
    val catalogEntries: Map<String, String> = emptyMap()
)
