package dev.striptools.strippdf.office

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs inspect → clean → re-inspect over a folder of real Office files and writes a
 * TSV report. Skipped unless OFFICE_CORPUS points at the folder; cleaned copies go to
 * OFFICE_CORPUS_OUT for external validation (LibreOffice / python-docx etc.).
 */
class OfficeCorpusTest {
    @Test
    fun corpus() {
        val dir = System.getenv("OFFICE_CORPUS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val out = File(System.getenv("OFFICE_CORPUS_OUT") ?: "build/office_out").apply { mkdirs() }
        val report = StringBuilder("file\tstatus\tkind\tbefore\tafter\tms\tdetail\n")
        dir!!.listFiles()!!.sortedBy { it.name }.forEach { f ->
            val t0 = System.currentTimeMillis()
            val row = try {
                val before = OfficeInspector.inspect(f)
                val cleaned = File(out, f.name)
                val pro = System.getenv("OFFICE_PRO") == "1"
                OfficeCleaner.clean(f, cleaned, OfficeCleaner.Options(removeComments = pro, acceptRevisions = pro, removeSpeakerNotes = pro))
                val after = OfficeInspector.inspect(cleaned)
                "${f.name}\tok\t${before.kind}\t${summary(before)}\t${summary(after)}\t${System.currentTimeMillis() - t0}\t"
            } catch (e: OfficeEncryptedException) {
                "${f.name}\tencrypted\t\t\t\t\t"
            } catch (e: NotOfficeException) {
                "${f.name}\tnot-office\t\t\t\t\t${e.message}"
            } catch (e: Throwable) {
                "${f.name}\tERROR\t\t\t\t\t${e::class.simpleName}: ${e.message?.take(160)}"
            }
            report.append(row).append('\n')
        }
        File(out, "_report.tsv").writeText(report.toString())
    }

    private fun summary(r: OfficeReport) = buildList {
        if (r.core.isNotEmpty()) add("core=${r.core.keys.joinToString(",")}")
        if (r.app.isNotEmpty()) add("app=${r.app.keys.joinToString(",")}")
        if (r.custom.isNotEmpty()) add("custom=${r.custom.size}")
        if (r.people.isNotEmpty()) add("people=${r.people.size}")
        if (r.commentCount > 0) add("comments=${r.commentCount}")
        if (r.trackedChangeCount > 0) add("tracked=${r.trackedChangeCount}")
        if (r.hasRevisionIds) add("revIds")
        if (r.hasThumbnail) add("thumb")
        if (r.imagesWithMetadata > 0) add("imgMeta=${r.imagesWithMetadata}/${r.imageCount}")
        if (r.imagesWithGps > 0) add("gps=${r.imagesWithGps}")
        if (r.hasMacros) add("macros")
        if (r.localPaths.isNotEmpty()) add("paths=${r.localPaths.size}")
        if (r.linkedFilePaths.isNotEmpty()) add("linked=${r.linkedFilePaths.size}")
        if (r.hasPrinterInfo) add("printer")
        if (r.speakerNotesCount > 0) add("notes=${r.speakerNotesCount}")
    }.joinToString(" ")
}
