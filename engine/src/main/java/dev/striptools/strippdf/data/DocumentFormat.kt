package dev.striptools.strippdf.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.striptools.strippdf.office.OfficeKind
import java.io.File

/** File types the app can audit and clean. */
enum class DocumentFormat(val officeKind: OfficeKind?) {
    PDF(null),
    WORD(OfficeKind.WORD),
    EXCEL(OfficeKind.EXCEL),
    POWERPOINT(OfficeKind.POWERPOINT);

    val isOffice: Boolean get() = officeKind != null

    companion object {
        const val MIME_PDF = "application/pdf"

        private val OFFICE_MIME_BY_EXTENSION = mapOf(
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "docm" to "application/vnd.ms-word.document.macroenabled.12",
            "dotx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
            "dotm" to "application/vnd.ms-word.template.macroenabled.12",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xlsm" to "application/vnd.ms-excel.sheet.macroenabled.12",
            "xltx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
            "xltm" to "application/vnd.ms-excel.template.macroenabled.12",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "pptm" to "application/vnd.ms-powerpoint.presentation.macroenabled.12",
            "ppsx" to "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "ppsm" to "application/vnd.ms-powerpoint.slideshow.macroenabled.12",
            "potx" to "application/vnd.openxmlformats-officedocument.presentationml.template",
            "potm" to "application/vnd.ms-powerpoint.template.macroenabled.12"
        )

        /** Everything the document picker and share sheet should offer. */
        val PICKER_MIME_TYPES: Array<String> = (listOf(MIME_PDF) + OFFICE_MIME_BY_EXTENSION.values).toTypedArray()

        fun mimeTypeFor(fileName: String): String {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return if (ext == "pdf") MIME_PDF else OFFICE_MIME_BY_EXTENSION[ext] ?: MIME_PDF
        }

        fun fromFileName(fileName: String): DocumentFormat? {
            if (fileName.endsWith(".pdf", ignoreCase = true)) return PDF
            return when (OfficeKind.fromExtension(fileName)) {
                OfficeKind.WORD -> WORD
                OfficeKind.EXCEL -> EXCEL
                OfficeKind.POWERPOINT -> POWERPOINT
                null -> null
            }
        }

        fun fromMimeType(mime: String?): DocumentFormat? {
            if (mime == null) return null
            if (mime == MIME_PDF) return PDF
            val ext = OFFICE_MIME_BY_EXTENSION.entries.firstOrNull { it.value.equals(mime, ignoreCase = true) }?.key ?: return null
            return fromFileName("x.$ext")
        }

        /**
         * Name and mime type decide first; file contents settle anything ambiguous
         * (octet-stream shares, missing extensions). PDF stays the fallback so the
         * existing PDF path behaves exactly as before for anything unrecognised.
         */
        fun detect(context: Context, uri: Uri): DocumentFormat {
            val name = displayName(context, uri)
            val byName = name?.let(::fromFileName)
            val byMime = runCatching { context.contentResolver.getType(uri) }.getOrNull()?.let(::fromMimeType)
            val claimed = byName ?: byMime
            // Contents beat the name: a PDF renamed .docx must still go down the PDF path, and
            // a .docx renamed .pdf down the Office one.
            return when (head(context, uri)) {
                Head.PDF -> PDF
                Head.ZIP -> claimed?.takeIf { it.isOffice } ?: sniffZip(context, uri) ?: claimed ?: PDF
                // Encrypted Office files are OLE containers; keep the claimed Office type so the
                // user gets the "remove the password in Office" message.
                Head.OLE -> claimed ?: PDF
                Head.OTHER, null -> claimed ?: PDF
            }
        }

        private enum class Head { PDF, ZIP, OLE, OTHER }

        private fun head(context: Context, uri: Uri): Head? = runCatching {
            val stream = if (uri.scheme == "file") uri.path?.let(::File)?.inputStream() else context.contentResolver.openInputStream(uri)
            stream?.use { s ->
                // %PDF may follow a few junk bytes in real files; look a little way in.
                val b = ByteArray(1024)
                val n = s.read(b).coerceAtLeast(0)
                when {
                    n >= 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() -> Head.ZIP
                    n >= 4 && b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte() && b[2] == 0x11.toByte() && b[3] == 0xE0.toByte() -> Head.OLE
                    String(b, 0, n, Charsets.ISO_8859_1).contains("%PDF") -> Head.PDF
                    else -> Head.OTHER
                }
            }
        }.getOrNull()

        fun displayName(context: Context, uri: Uri): String? {
            if (uri.scheme == "file") return uri.path?.let { File(it).name }
            return runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }

        private fun sniffZip(context: Context, uri: Uri): DocumentFormat? = runCatching {
            val stream = if (uri.scheme == "file") uri.path?.let(::File)?.inputStream() else context.contentResolver.openInputStream(uri)
            stream?.use { s ->
                java.util.zip.ZipInputStream(s).use { zip ->
                    generateSequence { zip.nextEntry }.take(200).map { it.name }.firstNotNullOfOrNull { n ->
                        when {
                            n.startsWith("word/") -> WORD
                            n.startsWith("xl/") -> EXCEL
                            n.startsWith("ppt/") -> POWERPOINT
                            else -> null
                        }
                    }
                }
            }
        }.getOrNull()
    }
}
