package dev.striptools.strippdf.office

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Times one large file when OFFICE_TIMING_FILE is set; prints to the test report. */
class OfficeTimingTest {
    @Test
    fun time() {
        val f = System.getenv("OFFICE_TIMING_FILE")?.let(::File)
        assumeTrue(f != null && f.isFile)
        for (opts in listOf(OfficeCleaner.Options(), OfficeCleaner.Options(removeComments = true, acceptRevisions = true))) {
            val out = File.createTempFile("timing", ".docx")
            val t0 = System.currentTimeMillis()
            OfficeCleaner.clean(f!!, out, opts)
            println("TIMING ${f.name} $opts ${System.currentTimeMillis() - t0} ms")
            out.delete()
        }
    }
}
