package com.lectorvoz.app

data class DocMeta(
    val id: String,
    val title: String,
    val format: String,
    val addedAt: Long,
    val total: Long,
    val sectionTitles: List<String>,
    val sectionChars: IntArray
) {
    val sectionCount: Int get() = sectionTitles.size

    /** cum[i] = caracteres antes de la sección i; cum[size] = total */
    val cum: LongArray by lazy {
        val a = LongArray(sectionChars.size + 1)
        for (i in sectionChars.indices) a[i + 1] = a[i] + sectionChars[i]
        a
    }
}

data class ReaderUiState(
    val docId: String? = null,
    val title: String = "",
    val sectionTitles: List<String> = emptyList(),
    val section: Int = 0,
    val paragraphs: List<String> = emptyList(),
    val para: Int = 0,
    val wordStart: Int = 0,
    val wordEnd: Int = 0,
    val playing: Boolean = false,
    val rate: Float = 1f,
    val percent: Float = 0f,
    val remainingSec: Long = 0L
)

data class DocItem(
    val id: String,
    val title: String,
    val format: String,
    val folder: String,
    val percent: Float,
    val total: Long
)

data class ImportStatus(val message: String, val progress: Float)
