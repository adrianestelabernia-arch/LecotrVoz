package com.lectorvoz.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI
import java.nio.charset.Charset
import java.util.UUID
import java.util.zip.ZipFile

internal fun appendJoined(sb: StringBuilder, t: String) {
    if (sb.isNotEmpty()) {
        if (sb.endsWith("-") && sb.length > 1 && sb[sb.length - 2].isLetter() && t.isNotEmpty() && t[0].isLowerCase()) {
            sb.setLength(sb.length - 1)
        } else {
            sb.append(' ')
        }
    }
    sb.append(t)
}

private class LineSplitter(private val limit: Int, private val sink: (String) -> Unit) {
    private val sb = StringBuilder()
    fun feed(buf: CharArray, n: Int) {
        for (i in 0 until n) {
            val c = buf[i]
            if (c == '\n') {
                sink(sb.toString()); sb.setLength(0)
            } else if (c != '\r') {
                sb.append(c)
                if (sb.length >= limit) {
                    val cut = sb.lastIndexOf(" ")
                    if (cut > limit / 2) {
                        val head = sb.substring(0, cut)
                        val rest = sb.substring(cut + 1)
                        sb.setLength(0); sb.append(rest)
                        sink(head)
                    } else {
                        sink(sb.toString()); sb.setLength(0)
                    }
                }
            }
        }
    }
    fun finish() { if (sb.isNotEmpty()) sink(sb.toString()); sb.setLength(0) }
}

private val HEADS = setOf("h1", "h2", "h3")

private class Collector : NodeVisitor {
    val sb = StringBuilder()
    override fun head(node: Node, depth: Int) {
        if (node is TextNode) {
            sb.append(node.text())
        } else if (node is Element) {
            val tag = node.normalName()
            if (tag == "br") sb.append('\n')
            else if (node.isBlock) {
                sb.append('\n')
                if (tag in HEADS) sb.append('\u0001')
            }
        }
    }
    override fun tail(node: Node, depth: Int) {
        if (node is Element && node.isBlock) sb.append('\n')
    }
}

class Importer(private val ctx: Context, private val repo: LibraryRepository) {

    suspend fun import(uri: Uri, onProgress: (String, Float) -> Unit): DocMeta = withContext(Dispatchers.IO) {
        val name = displayName(uri) ?: "Documento"
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = ctx.contentResolver.getType(uri) ?: ""
        val format = when {
            ext == "pdf" || mime == "application/pdf" -> "pdf"
            ext == "epub" || mime.contains("epub") -> "epub"
            ext in listOf("html", "htm", "xhtml") || mime.contains("html") -> "html"
            ext in listOf("md", "markdown") || mime.contains("markdown") -> "md"
            else -> "txt"
        }
        val title = name.substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { name }
        val id = UUID.randomUUID().toString()
        val dir = repo.dir(id)
        dir.mkdirs()
        val tmp = File(ctx.cacheDir, "import_$id")
        try {
            onProgress("Copiando archivo…", 0f)
            val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("No se puede abrir el archivo")
            input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val w = SectionWriter(dir)
            when (format) {
                "pdf" -> importPdf(tmp, w, onProgress)
                "epub" -> importEpub(tmp, w) { onProgress("Procesando EPUB…", it) }
                "html" -> { onProgress("Procesando HTML…", 0.5f); importHtml(tmp, w) }
                "md" -> importText(tmp, true, w) { onProgress("Procesando texto…", it) }
                else -> importText(tmp, false, w) { onProgress("Procesando texto…", it) }
            }
            w.finish()
            if (w.total < 5) throw IllegalStateException("No se ha encontrado texto en el archivo")
            val meta = DocMeta(id, title, format, System.currentTimeMillis(), w.total, w.titles.toList(), w.counts.toIntArray())
            repo.saveMeta(meta)
            meta
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        } finally {
            tmp.delete()
        }
    }

    private fun displayName(uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment

    // ------------------------------------------------------------------ TXT / MD
    private fun detectCharset(file: File): Charset {
        val head = ByteArray(65536)
        val n = file.inputStream().use { it.read(head) }.coerceAtLeast(0)
        if (n >= 2 && head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte()) return Charsets.UTF_16LE
        if (n >= 2 && head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte()) return Charsets.UTF_16BE
        val len = if (n > 8) n - 4 else n
        val s = String(head, 0, len, Charsets.UTF_8)
        return if (s.contains('\uFFFD')) Charset.forName("windows-1252") else Charsets.UTF_8
    }

    private fun detectJoin(file: File, cs: Charset): Boolean {
        val buf = CharArray(100_000)
        val n = InputStreamReader(FileInputStream(file), cs).use { it.read(buf) }
        if (n <= 0) return false
        var blank = 0
        var non = 0
        String(buf, 0, n).split('\n').forEach { if (it.isBlank()) blank++ else non++ }
        return blank >= 0.03 * non
    }

    private val chapterRe = Regex("^(?i:cap[íi]tulo|chapter|parte|part|libro|book|acto|act|secci[óo]n|section)\\s+(?:\\d+|[IVXLCDM]+)\\b.{0,60}\$")
    private val bareRe = Regex("^(?i:pr[óo]logo|ep[íi]logo|prologue|epilogue|introducci[óo]n|introduction|prefacio|preface)\\s*\$")
    private val mdHead = Regex("^#{1,6}\\s+(.+?)\\s*#*\$")
    private val mdBullet = Regex("^([-*+]|\\d+[.)])\\s+")
    private val mdImg = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
    private val mdLink = Regex("\\[([^\\]]*)\\]\\([^)]*\\)")
    private val mdUnder = Regex("(?<![A-Za-z0-9])_([^_]+)_(?![A-Za-z0-9])")
    private val htmlTag = Regex("<[^>]+>")

    private fun cleanMd(s: String): String {
        var t = mdImg.replace(s, "")
        t = mdLink.replace(t, "\$1")
        t = mdUnder.replace(t, "\$1")
        t = htmlTag.replace(t, "")
        t = t.replace("**", "").replace("__", "").replace("*", "").replace("`", "").replace("~~", "")
        t = t.replace(Regex("^>+\\s*"), "").replace("|", " ")
        return t
    }

    private suspend fun importText(file: File, md: Boolean, w: SectionWriter, prog: (Float) -> Unit) {
        val cs = detectCharset(file)
        val join = md || detectJoin(file, cs)
        val para = StringBuilder()
        var inFence = false

        fun flush() {
            if (para.isNotEmpty()) { w.addParagraph(para.toString()); para.setLength(0) }
        }

        fun onLine(raw: String) {
            val t = raw.trim()
            if (t.isEmpty()) { flush(); return }
            if (md) {
                if (t.startsWith("```")) { inFence = !inFence; flush(); return }
                if (inFence) { w.addParagraph(t); return }
                val h = mdHead.find(t)
                if (h != null) {
                    flush()
                    val title = cleanMd(h.groupValues[1]).trim()
                    w.startSection(title); w.addParagraph(title)
                    return
                }
                if (mdBullet.containsMatchIn(t)) {
                    flush(); w.addParagraph(cleanMd(mdBullet.replaceFirst(t, ""))); return
                }
            } else if (t.length <= 80 && (chapterRe.matches(t) || bareRe.matches(t))) {
                flush(); w.startSection(t); w.addParagraph(t); return
            }
            val text = if (md) cleanMd(t) else t
            if (join) {
                appendJoined(para, text)
                if (para.length > 3000) flush()
            } else {
                w.addParagraph(text)
            }
        }

        val splitter = LineSplitter(4000) { onLine(it) }
        val total = file.length().coerceAtLeast(1)
        FileInputStream(file).use { fis ->
            val reader = InputStreamReader(fis, cs)
            val buf = CharArray(16384)
            var iter = 0
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                currentCoroutineContext().ensureActive()
                splitter.feed(buf, n)
                if (++iter % 20 == 0) prog((fis.channel.position().toFloat() / total).coerceIn(0f, 1f))
            }
            splitter.finish()
        }
        flush()
    }

    // ------------------------------------------------------------------ HTML / EPUB
    private fun htmlLines(root: Element): List<String> {
        val c = Collector()
        NodeTraversor.traverse(c, root)
        return c.sb.toString().split('\n')
    }

    private fun feedLines(lines: List<String>, w: SectionWriter) {
        for (l in lines) {
            val t = l.trim()
            if (t.isEmpty()) continue
            if (t[0] == '\u0001') {
                val h = t.substring(1).trim()
                if (h.isEmpty()) continue
                w.startSection(h); w.addParagraph(h)
            } else {
                w.addParagraph(t)
            }
        }
    }

    private fun importHtml(file: File, w: SectionWriter) {
        val doc = Jsoup.parse(file, null)
        feedLines(htmlLines(doc.body()), w)
    }

    private suspend fun importEpub(file: File, w: SectionWriter, prog: (Float) -> Unit) {
        ZipFile(file).use { zip ->
            fun readText(path: String): String? =
                zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }

            val container = readText("META-INF/container.xml") ?: error("EPUB no válido")
            val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
                .getElementsByTag("rootfile").firstOrNull()?.attr("full-path") ?: error("EPUB no válido")
            val opf = Jsoup.parse(readText(opfPath) ?: error("EPUB no válido"), "", Parser.xmlParser())

            val manifest = HashMap<String, Pair<String, String>>()
            for (it in opf.getElementsByTag("item")) manifest[it.attr("id")] = Pair(it.attr("href"), it.attr("properties"))
            val spine = opf.getElementsByTag("itemref").map { it.attr("idref") }

            fun resolve(href: String): String = try {
                URI("/" + opfPath).resolve(URI(href)).path.trimStart('/')
            } catch (e: Exception) {
                val base = opfPath.substringBeforeLast('/', "")
                if (base.isEmpty()) href else "$base/$href"
            }

            for ((i, idref) in spine.withIndex()) {
                currentCoroutineContext().ensureActive()
                val item = manifest[idref] ?: continue
                if (item.second.contains("nav")) continue
                val txt = readText(resolve(item.first)) ?: continue
                val doc = Jsoup.parse(txt)
                val body = doc.body()
                w.startSection(null)
                feedLines(htmlLines(body), w)
                prog((i + 1f) / spine.size)
            }
        }
    }

    // ------------------------------------------------------------------ PDF (+OCR)
    private fun outlineMap(doc: PDDocument): Map<Int, String> {
        val map = HashMap<Int, String>()
        try {
            val outline = doc.documentCatalog.documentOutline ?: return map
            fun walk(node: PDOutlineNode, depth: Int) {
                var item = node.firstChild
                while (item != null) {
                    if (depth < 2) {
                        try {
                            val page = item.findDestinationPage(doc)
                            val title = item.title
                            if (page != null && title != null) {
                                val idx = doc.pages.indexOf(page)
                                if (idx >= 0) map.putIfAbsent(idx, title.trim())
                            }
                        } catch (e: Exception) { }
                        walk(item, depth + 1)
                    }
                    item = item.nextSibling
                }
            }
            walk(outline, 0)
        } catch (e: Throwable) { }
        return map
    }

    private fun addPdfText(text: String, w: SectionWriter) {
        val para = StringBuilder()
        for (line in text.split('\n')) {
            val t = line.trim()
            if (t.isEmpty()) {
                if (para.isNotEmpty()) { w.addParagraph(para.toString()); para.setLength(0) }
            } else appendJoined(para, t)
        }
        if (para.isNotEmpty()) w.addParagraph(para.toString())
    }

    private suspend fun ocrPage(r: PdfRenderer, index: Int, rec: TextRecognizer, w: SectionWriter) {
        var bmp: Bitmap? = null
        val page = r.openPage(index)
        try {
            val scale = (1800f / page.width).coerceIn(1f, 4f)
            val bw = (page.width * scale).toInt().coerceAtLeast(1)
            val bh = (page.height * scale).toInt().coerceAtLeast(1)
            val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            b.eraseColor(Color.WHITE)
            page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp = b
        } finally {
            page.close()
        }
        val image = bmp ?: return
        try {
            val res = rec.process(InputImage.fromBitmap(image, 0)).await()
            for (b in res.textBlocks) {
                val sb = StringBuilder()
                for (l in b.lines) appendJoined(sb, l.text.trim())
                w.addParagraph(sb.toString())
            }
        } finally {
            image.recycle()
        }
    }

    private suspend fun importPdf(file: File, w: SectionWriter, prog: (String, Float) -> Unit) {
        PDFBoxResourceLoader.init(ctx)
        val doc: PDDocument? = try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: Throwable) {
            null
        }
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var recognizer: TextRecognizer? = null
        try {
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
            } catch (e: Throwable) {
                renderer = null
            }
            val r = renderer
            val n = doc?.numberOfPages ?: r?.pageCount ?: throw IOException("No se puede abrir el PDF")
            val outline = if (doc != null) outlineMap(doc) else emptyMap()
            val stripper = PDFTextStripper()

            for (p in 0 until n) {
                currentCoroutineContext().ensureActive()
                val t = outline[p]
                if (t != null) w.startSection(t)
                else if (outline.isEmpty() && p % 10 == 0) w.startSection("Página ${p + 1}")

                prog("Leyendo página ${p + 1} de $n", p.toFloat() / n)
                var text = ""
                if (doc != null) {
                    try {
                        stripper.startPage = p + 1
                        stripper.endPage = p + 1
                        text = stripper.getText(doc)
                    } catch (e: Throwable) {
                        text = ""
                    }
                }
                if (text.trim().length >= 25) {
                    addPdfText(text, w)
                } else if (r != null) {
                    prog("OCR página ${p + 1} de $n", p.toFloat() / n)
                    val rec = recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
                    try {
                        ocrPage(r, p, rec, w)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // página ilegible: se omite
                    }
                }
            }
        } finally {
            recognizer?.close()
            try { renderer?.close() } catch (e: Exception) { }
            try { pfd?.close() } catch (e: Exception) { }
            try { doc?.close() } catch (e: Exception) { }
        }
    }
}
