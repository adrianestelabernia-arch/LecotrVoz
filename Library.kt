package com.lectorvoz.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LibraryRepository(ctx: Context) {
    private val root = File(ctx.filesDir, "docs").apply { mkdirs() }
    private val prefs = ctx.getSharedPreferences("lector", Context.MODE_PRIVATE)

    fun dir(id: String) = File(root, id)

    fun listDocs(): List<DocMeta> =
        (root.listFiles() ?: emptyArray()).mapNotNull { loadMeta(it.name) }
            .sortedByDescending { it.addedAt }

    fun loadMeta(id: String): DocMeta? = try {
        val f = File(dir(id), "meta.json")
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val ta = o.getJSONArray("titles")
            val ca = o.getJSONArray("chars")
            DocMeta(
                id, o.getString("title"), o.getString("format"), o.getLong("added"), o.getLong("total"),
                List(ta.length()) { ta.getString(it) },
                IntArray(ca.length()) { ca.getInt(it) }
            )
        }
    } catch (e: Exception) {
        null
    }

    fun saveMeta(m: DocMeta) {
        val o = JSONObject()
        o.put("title", m.title)
        o.put("format", m.format)
        o.put("added", m.addedAt)
        o.put("total", m.total)
        o.put("titles", JSONArray(m.sectionTitles))
        val ca = JSONArray()
        m.sectionChars.forEach { ca.put(it) }
        o.put("chars", ca)
        File(dir(m.id), "meta.json").writeText(o.toString())
    }

    fun readSection(id: String, idx: Int): List<String> {
        val f = File(dir(id), "s$idx.txt")
        return if (f.exists()) f.readLines() else emptyList()
    }

    fun deleteDoc(id: String) {
        dir(id).deleteRecursively()
        prefs.edit().remove("prog_$id").remove("pct_$id").remove("folder_$id").apply()
    }

    // ---- carpetas ----
    fun folders(): List<String> = (prefs.getStringSet("folders", emptySet()) ?: emptySet()).sorted()

    fun addFolder(name: String) {
        val s = (prefs.getStringSet("folders", emptySet()) ?: emptySet()).toMutableSet()
        s.add(name)
        prefs.edit().putStringSet("folders", s).apply()
    }

    fun deleteFolder(name: String) {
        val s = (prefs.getStringSet("folders", emptySet()) ?: emptySet()).toMutableSet()
        s.remove(name)
        prefs.edit().putStringSet("folders", s).apply()
        (root.listFiles() ?: emptyArray()).forEach { if (folderOf(it.name) == name) setFolder(it.name, "") }
    }

    fun folderOf(id: String): String = prefs.getString("folder_$id", "") ?: ""
    fun setFolder(id: String, folder: String) = prefs.edit().putString("folder_$id", folder).apply()

    // ---- progreso ----
    fun saveProgress(id: String, section: Int, para: Int, offset: Int, pct: Float, sync: Boolean = false) {
        val e = prefs.edit().putString("prog_$id", "$section,$para,$offset").putFloat("pct_$id", pct)
        if (sync) e.commit() else e.apply()
    }

    fun loadProgress(id: String): Triple<Int, Int, Int> {
        val p = prefs.getString("prog_$id", null)?.split(",")
        if (p == null || p.size != 3) return Triple(0, 0, 0)
        return Triple(p[0].toIntOrNull() ?: 0, p[1].toIntOrNull() ?: 0, p[2].toIntOrNull() ?: 0)
    }

    fun percent(id: String): Float = prefs.getFloat("pct_$id", 0f)
}
