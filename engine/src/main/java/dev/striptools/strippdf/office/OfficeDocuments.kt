package dev.striptools.strippdf.office

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import dev.striptools.strippdf.R
import dev.striptools.strippdf.data.DocumentFormat
import dev.striptools.strippdf.data.PdfMetadata
import dev.striptools.strippdf.pdf.ScrubOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Android glue around [OfficeInspector] / [OfficeCleaner]: Uri I/O, temp files, outcomes. */
object OfficeDocuments {

    fun extract(context: Context, uri: Uri, format: DocumentFormat): PdfMetadata? {
        val (name, size) = nameAndSize(context, uri)
        val temp = copyToTemp(context, uri) ?: return PdfMetadata(uri = uri, fileName = name, fileSize = size, format = format)
        return try {
            val report = OfficeInspector.inspect(temp)
            PdfMetadata(
                uri = uri,
                fileName = name,
                fileSize = size,
                format = format,
                office = report,
                title = report.core["title"],
                author = report.core["creator"],
                subject = report.core["subject"],
                keywords = report.core["keywords"],
                creator = listOfNotNull(report.software["Application"], report.software["AppVersion"]).joinToString(" ").ifBlank { null },
                creationDate = report.core["created"],
                modDate = report.core["modified"],
                infoEntries = report.core + report.app + report.software + report.custom.mapKeys { "custom:${it.key}" }
            )
        } catch (_: OfficeEncryptedException) {
            PdfMetadata(uri = uri, fileName = name, fileSize = size, format = format, isEncrypted = true)
        } catch (_: Exception) {
            // Damaged or not really Office: keep it in the list so the scrub explains why it failed.
            PdfMetadata(uri = uri, fileName = name, fileSize = size, format = format)
        } finally {
            temp.delete()
        }
    }

    fun scrub(
        context: Context,
        sourceUri: Uri,
        originalFileName: String,
        options: OfficeCleaner.Options,
        isCancelled: () -> Boolean,
        onError: ((String) -> Unit)? = null
    ): ScrubOutcome {
        val stripped = originalFileName.removePrefix("Vantre_").removePrefix("Strip_")
        val output = File(context.cacheDir, "Vantre_$stripped")
        val input = copyToTemp(context, sourceUri) ?: return fail(
            onError, context.getString(R.string.scrub_error_cannot_read_source), ScrubOutcome.ERR_ACCESS_DENIED
        )
        return try {
            OfficeCleaner.clean(input, output, options, isCancelled)
            ScrubOutcome.Success(output)
        } catch (_: InterruptedException) {
            output.delete()
            ScrubOutcome.Failed("Cancelled", ScrubOutcome.ERR_CANCELLED)
        } catch (_: OfficeEncryptedException) {
            output.delete()
            fail(onError, context.getString(R.string.scrub_error_office_password), ScrubOutcome.ERR_UNSUPPORTED_FORMAT)
        } catch (_: NotOfficeException) {
            output.delete()
            fail(onError, context.getString(R.string.scrub_error_office_invalid), ScrubOutcome.ERR_UNSUPPORTED_FORMAT)
        } catch (e: java.util.zip.ZipException) {
            output.delete()
            fail(onError, context.getString(R.string.scrub_error_office_invalid), ScrubOutcome.ERR_UNSUPPORTED_FORMAT)
        } catch (e: IOException) {
            output.delete()
            fail(onError, context.getString(R.string.scrub_error_office_failed), ScrubOutcome.ERR_PROCESSING_FAILED)
        } catch (e: RuntimeException) {
            output.delete()
            fail(onError, context.getString(R.string.scrub_error_office_failed), ScrubOutcome.ERR_PROCESSING_FAILED)
        } finally {
            input.delete()
        }
    }

    fun fullRawJson(metadata: PdfMetadata): String {
        val r = metadata.office
        val json = JSONObject()
            .put("fileName", metadata.fileName)
            .put("fileSize", metadata.fileSize)
            .put("format", metadata.format.name)
            .put("isEncrypted", metadata.isEncrypted)
        if (r != null) {
            json.put("coreProperties", JSONObject(r.core as Map<*, *>))
                .put("extendedProperties", JSONObject((r.app + r.software) as Map<*, *>))
                .put("customProperties", JSONObject(r.custom as Map<*, *>))
                .put("people", JSONArray(r.people))
                .put("commentCount", r.commentCount)
                .put("trackedChangeCount", r.trackedChangeCount)
                .put("hasRevisionIds", r.hasRevisionIds)
                .put("hasThumbnail", r.hasThumbnail)
                .put("imageCount", r.imageCount)
                .put("imagesWithMetadata", r.imagesWithMetadata)
                .put("imagesWithGps", r.imagesWithGps)
                .put("hasMacros", r.hasMacros)
                .put("localPaths", JSONArray(r.localPaths))
                .put("linkedFilePaths", JSONArray(r.linkedFilePaths))
                .put("hasPrinterInfo", r.hasPrinterInfo)
        }
        return json.toString(2).replace("\\/", "/")
    }

    private fun fail(onError: ((String) -> Unit)?, reason: String, code: String): ScrubOutcome {
        onError?.invoke(reason)
        return ScrubOutcome.Failed(reason, code)
    }

    private fun nameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") {
            val f = uri.path?.let(::File)
            return (f?.name ?: "Unknown") to (f?.length() ?: 0L)
        }
        var name = "Unknown"
        var size = 0L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    private fun copyToTemp(context: Context, uri: Uri): File? {
        val temp = File(context.cacheDir, "office_${UUID.randomUUID()}.tmp")
        return try {
            val stream = if (uri.scheme == "file") uri.path?.let(::File)?.takeIf { it.exists() }?.inputStream()
            else context.contentResolver.openInputStream(uri)
            stream?.use { s -> temp.outputStream().use { s.copyTo(it) } } ?: return null
            temp
        } catch (_: Exception) {
            temp.delete()
            null
        }
    }
}
