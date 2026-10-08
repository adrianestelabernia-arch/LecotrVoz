package com.lectorvoz.app

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

/**
 * Escribe el texto en secciones (s0.txt, s1.txt...) con un párrafo por línea.
 * Así se puede procesar cualquier tamaño sin cargar todo en memoria.
 */
class SectionWriter(private val dir: File) {
    companion object {
        const val MAX_SECTION = 20000
        const val MAX_PARA = 700
        const val MIN_SECTION = 300
        private val WS = Regex("[\\s\\u00A0\\u2007\\u202F\\u0001\\uFEFF]+")

        fun clean(s: String): String =
            WS.replace(s.replace("\u00AD", "").replace("\u200B", ""), " ").trim()
    }

    val titles = ArrayList<String>()
    val counts = ArrayList<Int>()
    var total = 0L
        private set

    private var writer: BufferedWriter? = null
    private var curTitle = ""
    private var curBase: String? = null
    private var curChars = 0

    private fun open(title: String?, base: String?) {
        val idx = titles.size
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(File(dir, "s$idx.txt")), Charsets.UTF_8), 32768)
        curTitle = title ?: "Parte ${idx + 1}"
        curBase = base ?: title
        curChars = 0
    }

    private fun close() {
        val w = writer ?: return
        w.close()
        if (curChars > 0) {
            titles.add(curTitle)
            counts.add(curChars)
        }
        writer = null
    }

    fun startSection(title: String?) {
        val t = title?.let { clean(it).take(120) }?.takeIf { it.isNotEmpty() }
        if (writer != null && curChars >= MIN_SECTION) close()
        if (writer == null) {
            open(t, null)
        } else if (t != null) {
            curTitle = t
            curBase = t
        }
    }

    fun addParagraph(raw: String) {
        val text = clean(raw)
        if (text.none { it.isLetterOrDigit() }) return
        for (chunk in splitLong(text)) {
            if (writer == null) {
                open(null, null)
            } else if (curChars > 0 && curChars + chunk.length > MAX_SECTION) {
                val base = curBase
                close()
                open(base?.let { "$it (cont.)" }, base)
            }
            val w = writer!!
            w.write(chunk)
            w.write("\n")
            curChars += chunk.length + 1
            total += chunk.length + 1
        }
    }

    fun finish() = close()

    private fun isSentenceEnd(text: String, i: Int): Boolean {
        // i = índice (exclusivo) donde termina el trozo
        val c = text[i - 1]
        if (c == '.' || c == '!' || c == '?' || c == '…' || c == ';') return true
        if ((c == '"' || c == '”' || c == '»' || c == ')') && i >= 2) {
            val d = text[i - 2]
            return d == '.' || d == '!' || d == '?' || d == '…'
        }
        return false
    }

    private fun splitLong(text: String): List<String> {
        if (text.length <= MAX_PARA) return listOf(text)
        val out = ArrayList<String>()
        var start = 0
        while (text.length - start > MAX_PARA) {
            val limit = start + MAX_PARA
            var end = -1
            var next = -1
            var i = limit
            while (i > start + MAX_PARA / 3) {
                if (text[i] == ' ' && isSentenceEnd(text, i)) { end = i; next = i + 1; break }
                i--
            }
            if (end < 0) {
                i = limit
                while (i > start + MAX_PARA / 3) {
                    if (text[i] == ' ') { end = i; next = i + 1; break }
                    i--
                }
            }
            if (end < 0) { end = limit; next = limit }
            out.add(text.substring(start, end))
            start = next
        }
        if (start < text.length) out.add(text.substring(start))
        return out
    }
}
