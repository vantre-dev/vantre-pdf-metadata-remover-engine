package dev.striptools.strippdf.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class OfficeCleanerTest {

    private fun fixture(name: String) = File(javaClass.classLoader!!.getResource("office/$name")!!.toURI())

    @Test
    fun `word file loses identity, session ids and photo gps`() {
        val src = fixture("Settlement_draft.docx")
        val before = OfficeInspector.inspect(src)
        assertEquals("Jane Doe", before.core["creator"])
        assertEquals("Acme Legal LLP", before.app["Company"])
        assertTrue(before.hasRevisionIds)
        assertEquals(1, before.imagesWithGps)

        val out = File.createTempFile("clean", ".docx").apply { deleteOnExit() }
        OfficeCleaner.clean(src, out)
        val after = OfficeInspector.inspect(out)

        assertTrue(after.core.isEmpty())
        assertTrue(after.app.isEmpty())
        assertTrue(after.people.isEmpty())
        assertFalse(after.hasRevisionIds)
        assertEquals(0, after.imagesWithMetadata)
        assertEquals(before.imageCount, after.imageCount)
        // Body text untouched.
        ZipFile(out).use { z ->
            val body = z.getInputStream(z.getEntry("word/document.xml")).reader().readText()
            assertTrue(body.contains("Confidential settlement terms."))
            assertTrue(z.getInputStream(z.getEntry("word/settings.xml")).reader().readText().contains("<w:removePersonalInformation/>"))
        }
    }

    @Test
    fun `jpeg keeps orientation but drops camera and gps`() {
        val src = fixture("Settlement_draft.docx")
        val jpeg = ZipFile(src).use { z ->
            val e = z.entries().toList().first { it.name.startsWith("word/media/") }
            z.getInputStream(e).readBytes()
        }
        val cleaned = ImageMetadata.strip(jpeg)
        assertFalse(ImageMetadata.inspect(cleaned).hasMetadata)
        assertFalse(ImageMetadata.inspect(cleaned).hasGps)
        // Minimal EXIF with orientation 6 (rotate 90) survives so the photo does not turn sideways.
        val app1 = String(cleaned, 0, 64, Charsets.ISO_8859_1)
        assertTrue(app1.contains("Exif"))
        assertFalse(String(cleaned, Charsets.ISO_8859_1).contains("iPhone"))
        // Pixel data identical: everything from SOS onward matches.
        val sos = { b: ByteArray -> (0 until b.size - 1).first { b[it] == 0xFF.toByte() && b[it + 1] == 0xDA.toByte() } }
        assertTrue(jpeg.copyOfRange(sos(jpeg), jpeg.size).contentEquals(cleaned.copyOfRange(sos(cleaned), cleaned.size)))
    }

    @Test
    fun `streaming tokenizer leaves text and markup byte-identical when nothing changes`() {
        val xml = """<?xml version="1.0"?><!-- a > b --><w:p a='x"y' b="1"><![CDATA[<w:t>]]><w:t>5 > 3 &amp; ok</w:t></w:p>"""
        assertEquals(xml, XmlText.rewriteString(xml) { false })
    }

    @Test
    fun `word attributes are anonymised`() {
        val xml = """<w:ins w:id="1" w:author="Jane Doe" w:date="2025-01-01T00:00:00Z" w:rsidR="00AB"><w:r/></w:ins><w:rsid w:val="00AB"/>"""
        val out = XmlText.rewriteString(xml, OfficeCleaner::wordAttributes)
        assertEquals("""<w:ins w:id="1" w:author="Author"><w:r/></w:ins>""", out)
    }
}

class WordPartProcessorTest {
    private fun run(xml: String, comments: Boolean = false, accept: Boolean = false): String {
        val out = java.io.StringWriter()
        val p = WordPartProcessor(out, comments, accept)
        XmlTags.tokenize(java.io.StringReader(xml), p)
        p.finish()
        return out.toString()
    }

    private val doc = """<w:body>""" +
        """<w:p><w:r><w:t>Keep </w:t></w:r><w:ins w:id="1" w:author="Jane"><w:r><w:t>added</w:t></w:r></w:ins><w:del w:id="2" w:author="Jane"><w:r><w:delText>secret</w:delText></w:r></w:del></w:p>""" +
        """<w:p><w:pPr><w:rPr><w:del w:id="3" w:author="Jane"/></w:rPr></w:pPr><w:del w:id="4" w:author="Jane"><w:r><w:delText>whole paragraph gone</w:delText></w:r></w:del></w:p>""" +
        """<w:tbl><w:tr><w:tc><w:p><w:r><w:t>row stays</w:t></w:r></w:p></w:tc></w:tr>""" +
        """<w:tr><w:trPr><w:del w:id="5" w:author="Jane"/></w:trPr><w:tc><w:p><w:r><w:t>row deleted</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""" +
        """<w:p><w:r><w:rPr><w:b/><w:rPrChange w:id="6" w:author="Jane"><w:rPr><w:i/></w:rPr></w:rPrChange></w:rPr><w:t>bold</w:t></w:r>""" +
        """<w:commentRangeStart w:id="0"/><w:r><w:t>noted</w:t></w:r><w:commentRangeEnd w:id="0"/><w:r><w:commentReference w:id="0"/></w:r></w:p>""" +
        """<w:moveFrom w:id="7" w:author="Jane"><w:r><w:t>old place</w:t></w:r></w:moveFrom><w:moveTo w:id="8" w:author="Jane"><w:r><w:t>new place</w:t></w:r></w:moveTo>""" +
        """</w:body>"""

    @Test
    fun `accepting keeps insertions and drops deletions, rows and change history`() {
        val out = run(doc, accept = true)
        org.junit.Assert.assertTrue(out.contains("Keep ") && out.contains("added") && out.contains("row stays") && out.contains("new place"))
        listOf("secret", "whole paragraph gone", "row deleted", "old place", "rPrChange", "w:ins", "w:del", "w:moveTo", "<w:i/>").forEach {
            org.junit.Assert.assertFalse("still contains $it: $out", out.contains(it))
        }
        org.junit.Assert.assertEquals(3, Regex("<w:p>").findAll(out).count() + Regex("<w:p ").findAll(out).count())
        // Comments untouched when not asked.
        org.junit.Assert.assertTrue(out.contains("commentReference"))
    }

    @Test
    fun `without accepting, revisions stay but authors are anonymised`() {
        val out = run(doc)
        org.junit.Assert.assertTrue(out.contains("secret") && out.contains("<w:ins"))
        org.junit.Assert.assertFalse(out.contains("Jane"))
    }

    @Test
    fun `removing comments drops anchors only`() {
        val out = run(doc, comments = true)
        org.junit.Assert.assertFalse(out.contains("comment"))
        org.junit.Assert.assertTrue(out.contains("noted"))
    }

    @Test
    fun `output is well formed`() {
        listOf(run(doc, true, true), run(doc, false, true), run(doc, true, false)).forEach {
            javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(org.xml.sax.InputSource(java.io.StringReader(it.replace("<w:body>", "<w:body xmlns:w=\"urn:w\">"))))
        }
    }
}
