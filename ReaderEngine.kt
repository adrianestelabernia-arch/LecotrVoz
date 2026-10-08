package com.lectorvoz.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/**
 * Motor único de lectura. Todo se ejecuta en el hilo principal.
 * Cada párrafo se encola como un enunciado TTS con id "gen|sección|párrafo|offset";
 * se mantiene una cola de varios párrafos por delante para que la lectura no se pare.
 */
object ReaderEngine {
    private const val BASE_CPS = 14.5f   // caracteres por segundo a 1x (estimación)
    private const val LOOKAHEAD = 3

    private lateinit var app: Context
    lateinit var repo: LibraryRepository
        private set
    private var initialized = false
    private val main = Handler(Looper.getMainLooper())

    val state = MutableStateFlow(ReaderUiState())

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPlay = false

    private var meta: DocMeta? = null

    private class SectionData(val paras: List<String>, val starts: IntArray)
    private val cache = HashMap<Int, SectionData>()

    private var gen = 0
    private var qSection = 0
    private var qPara = 0
    private var qOffset = 0
    private var qCount = 0
    private var errCount = 0
    private var lastSave = 0L
    private var resumeOnGain = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null

    private val prefs get() = app.getSharedPreferences("lector", Context.MODE_PRIVATE)

    fun init(ctx: Context) {
        if (initialized) return
        app = ctx.applicationContext
        repo = LibraryRepository(app)
        val r = prefs.getFloat("rate", 1f).coerceIn(0.5f, 3f)
        state.update { it.copy(rate = r) }
        initialized = true
    }

    // ---------------------------------------------------------------- documento
    fun open(docId: String) {
        if (!initialized) return
        if (state.value.docId == docId && meta != null) return
        if (state.value.playing) pause()
        saveNow(true)
        val m = repo.loadMeta(docId) ?: return
        meta = m
        cache.clear()
        val (s0, p0, o0) = repo.loadProgress(docId)
        val s = s0.coerceIn(0, (m.sectionCount - 1).coerceAtLeast(0))
        val size = section(s)?.paras?.size ?: 0
        val p = p0.coerceIn(0, (size - 1).coerceAtLeast(0))
        val o = o0.coerceAtLeast(0)
        state.update { it.copy(docId = docId, title = m.title, sectionTitles = m.sectionTitles, playing = false) }
        publish(s, p, o, o)
        ensureTts()
    }

    fun closeIfLoaded(id: String) {
        if (state.value.docId == id) {
            pause()
            meta = null
            cache.clear()
            state.value = ReaderUiState(rate = state.value.rate)
        }
    }

    private fun section(i: Int): SectionData? {
        val m = meta ?: return null
        if (i < 0 || i >= m.sectionCount) return null
        cache[i]?.let { return it }
        val lines = repo.readSection(m.id, i)
        val starts = IntArray(lines.size)
        var acc = 0
        for (k in lines.indices) { starts[k] = acc; acc += lines[k].length + 1 }
        val d = SectionData(lines, starts)
        if (cache.size > 5) cache.keys.filter { it < i - 1 || it > i + 2 }.forEach { cache.remove(it) }
        cache[i] = d
        return d
    }

    private fun publish(sec: Int, para: Int, ws: Int, we: Int) {
        val m = meta ?: return
        val data = section(sec)
        val paras = data?.paras ?: emptyList()
        val read = m.cum[sec] + (data?.starts?.getOrNull(para) ?: 0) + ws
        val total = m.total.coerceAtLeast(1)
        val frac = (read.toFloat() / total).coerceIn(0f, 1f)
        val rem = (total - read).coerceAtLeast(0)
        val rate = state.value.rate
        state.update {
            it.copy(
                section = sec, paragraphs = paras, para = para, wordStart = ws, wordEnd = we,
                percent = frac, remainingSec = (rem / (BASE_CPS * rate)).toLong()
            )
        }
    }

    // ---------------------------------------------------------------- TTS
    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}
        override fun onDone(utteranceId: String?) { main.post { errCount = 0; onUtteranceDone(utteranceId) } }
        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) { main.post { onUtteranceError(utteranceId) } }
        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            main.post { onRange(utteranceId, start, end) }
        }
    }

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(app) { status -> main.post { onTtsInit(status) } }
    }

    private fun onTtsInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            tts = null
            pendingPlay = false
            toast("No se pudo iniciar el motor de voz (TTS) del teléfono")
            return
        }
        ttsReady = true
        t.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        t.setOnUtteranceProgressListener(listener)
        t.setSpeechRate(state.value.rate)
        applyLocale()
        if (pendingPlay) { pendingPlay = false; startSpeaking() }
    }

    private fun applyLocale() {
        val t = tts ?: return
        val tag = localeTag()
        val loc = if (tag.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag)
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("La voz no tiene datos para ese idioma. Instálalos en Ajustes > Salida de texto a voz")
        }
    }

    fun localeTag(): String = prefs.getString("locale", "") ?: ""

    fun setLocale(tag: String) {
        prefs.edit().putString("locale", tag).apply()
        applyLocale()
        if (state.value.playing) startSpeaking()
    }

    fun availableLocales(): List<Locale> = try {
        tts?.availableLanguages?.toList()?.sortedBy { it.displayName } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    // ---------------------------------------------------------------- reproducción
    fun play() {
        if (meta == null || state.value.playing) return
        startSpeaking()
    }

    fun toggle() { if (state.value.playing) pause() else play() }

    private fun startSpeaking() {
        val t = tts
        if (!ttsReady || t == null) { pendingPlay = true; ensureTts(); return }
        if (meta == null) return
        val st = state.value
        gen++
        t.stop()
        t.setSpeechRate(st.rate)
        errCount = 0
        qSection = st.section
        qPara = st.para
        qOffset = snapToWordStart(st.paragraphs.getOrNull(st.para) ?: "", st.wordStart)
        qCount = 0
        resumeOnGain = false
        requestFocus()
        acquireWake()
        state.update { it.copy(playing = true) }
        fillQueue()
    }

    private fun fillQueue() {
        val t = tts ?: return
        var guard = 0
        while (qCount < LOOKAHEAD && guard++ < 50) {
            val data = section(qSection) ?: break
            if (qPara >= data.paras.size) { qSection++; qPara = 0; qOffset = 0; continue }
            val full = data.paras[qPara]
            val off = qOffset.coerceIn(0, full.length)
            val chunk = full.substring(off)
            val pid = qPara
            qPara++
            qOffset = 0
            if (chunk.isBlank()) continue
            t.speak(chunk, TextToSpeech.QUEUE_ADD, null, "$gen|$qSection|$pid|$off")
            qCount++
        }
        if (qCount == 0 && state.value.playing) endOfBook()
    }

    private class UttId(val gen: Int, val sec: Int, val para: Int, val off: Int)

    private fun parseId(id: String?): UttId? {
        val p = id?.split("|") ?: return null
        if (p.size != 4) return null
        return UttId(
            p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null,
            p[2].toIntOrNull() ?: return null, p[3].toIntOrNull() ?: return null
        )
    }

    private fun onUtteranceDone(id: String?) {
        val u = parseId(id) ?: return
        if (u.gen != gen || !state.value.playing) return
        qCount = (qCount - 1).coerceAtLeast(0)
        var s = u.sec
        var para = u.para + 1
        val data = section(s)
        if (data != null && para >= data.paras.size) { s++; para = 0 }
        if (section(s) != null) publish(s, para, 0, 0)
        fillQueue()
    }

    private fun onUtteranceError(id: String?) {
        errCount++
        if (errCount >= 5) {
            pause()
            toast("Error del motor de voz. Revisa el idioma/voz en los ajustes de lectura")
            return
        }
        onUtteranceDone(id)
    }

    private fun onRange(id: String?, start: Int, end: Int) {
        val u = parseId(id) ?: return
        if (u.gen != gen || !state.value.playing) return
        publish(u.sec, u.para, u.off + start, u.off + end)
        val now = System.currentTimeMillis()
        if (now - lastSave > 4000) { lastSave = now; saveNow() }
    }

    private fun endOfBook() {
        gen++
        tts?.stop()
        state.update { it.copy(playing = false, percent = 1f, remainingSec = 0) }
        releaseWake()
        abandonFocus()
        saveNow(true)
    }

    /** Pausa guardando la palabra actual: al reanudar sigue exactamente en ella. */
    fun pause(keepFocus: Boolean = false) {
        pendingPlay = false
        if (!state.value.playing) return
        gen++
        tts?.stop()
        state.update { it.copy(playing = false) }
        releaseWake()
        if (!keepFocus) { resumeOnGain = false; abandonFocus() }
        saveNow(true)
    }

    // ---------------------------------------------------------------- navegación
    private fun snapToWordStart(text: String, off: Int): Int {
        var i = off.coerceIn(0, text.length)
        while (i > 0 && i < text.length && text[i].isLetterOrDigit() && text[i - 1].isLetterOrDigit()) i--
        return i
    }

    fun seekTo(sec: Int, para: Int, off: Int, play: Boolean) {
        val m = meta ?: return
        val s = sec.coerceIn(0, (m.sectionCount - 1).coerceAtLeast(0))
        val data = section(s) ?: return
        val p = para.coerceIn(0, (data.paras.size - 1).coerceAtLeast(0))
        val o = snapToWordStart(data.paras.getOrNull(p) ?: "", off)
        val was = state.value.playing
        publish(s, p, o, o)
        if (was || play) startSpeaking() else saveNow()
    }

    fun jumpToSection(i: Int) = seekTo(i, 0, 0, false)

    fun skip(delta: Int) {
        val st = state.value
        val m = meta ?: return
        var s = st.section
        var p = st.para
        if (delta < 0 && st.wordStart > 25) { seekTo(s, p, 0, false); return }
        p += delta
        if (p < 0) {
            if (s == 0) p = 0 else { s--; p = (section(s)?.paras?.size ?: 1) - 1 }
        } else if (p >= st.paragraphs.size) {
            if (s >= m.sectionCount - 1) p = st.paragraphs.size - 1 else { s++; p = 0 }
        }
        seekTo(s, p.coerceAtLeast(0), 0, false)
    }

    fun seekToFraction(f: Float) {
        val m = meta ?: return
        val target = (f.coerceIn(0f, 1f) * m.total).toLong()
        var s = 0
        while (s < m.sectionCount - 1 && m.cum[s + 1] <= target) s++
        val data = section(s) ?: return
        val within = (target - m.cum[s]).toInt()
        var k = 0
        while (k < data.starts.size - 1 && data.starts[k + 1] <= within) k++
        seekTo(s, k, 0, false)
    }

    fun setRate(r: Float) {
        val v = r.coerceIn(0.5f, 3f)
        prefs.edit().putFloat("rate", v).apply()
        state.update { it.copy(rate = v) }
        tts?.setSpeechRate(v)
        val st = state.value
        publish(st.section, st.para, st.wordStart, st.wordEnd)
        if (st.playing) startSpeaking()
    }

    fun saveNow(sync: Boolean = false) {
        if (!initialized) return
        val st = state.value
        val id = st.docId ?: return
        repo.saveProgress(id, st.section, st.para, st.wordStart, st.percent, sync)
    }

    // ---------------------------------------------------------------- audio / energía
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        main.post {
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> pause()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (state.value.playing) {
                    pause(keepFocus = true)
                    resumeOnGain = true
                }
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) {
                    resumeOnGain = false
                    startSpeaking()
                }
            }
        }
    }

    private fun audioManager() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun requestFocus() {
        val req = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            )
            .setOnAudioFocusChangeListener(focusListener, main)
            .build().also { focusRequest = it }
        audioManager().requestAudioFocus(req)
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager().abandonAudioFocusRequest(it) }
    }

    private fun acquireWake() {
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = wakeLock ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lectorvoz:tts").also {
            it.setReferenceCounted(false)
            wakeLock = it
        }
        wl.acquire(6 * 60 * 60 * 1000L)
    }

    private fun releaseWake() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun toast(msg: String) = Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
}
