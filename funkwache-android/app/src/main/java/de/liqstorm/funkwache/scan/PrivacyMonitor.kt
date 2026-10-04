package de.liqstorm.funkwache.scan

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import de.liqstorm.funkwache.core.Hub
import de.liqstorm.funkwache.model.Alert
import de.liqstorm.funkwache.model.Severity
import de.liqstorm.funkwache.model.Source
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Logs every time any app switches on a camera or the microphone. Android tells every app when a
 * camera becomes busy and when recordings start, but not which app is responsible – that is shown
 * by Android's own privacy dashboard. Funkwache itself never uses camera or microphone, so every
 * entry here comes from another app.
 */
object PrivacyMonitor {
    private const val TAG = "PrivacyMonitor"
    private const val HOTWORD_SOURCE = 1999 // MediaRecorder.AudioSource.HOTWORD ("Hey Google"), hidden constant

    data class Usage(
        val kind: String,          // "Kamera" or "Mikrofon"
        val detail: String,        // e.g. "Rückkamera", "Sprachaufnahme"
        val start: Long,
        val end: Long?,            // null while in use
        val screenOff: Boolean,
        val inCall: Boolean,
    ) {
        val active get() = end == null
    }

    val log = MutableStateFlow<List<Usage>>(emptyList())
    val cameraInUse = MutableStateFlow(false)
    val micInUse = MutableStateFlow(false)

    private var started = false
    private val openCameras = HashMap<String, Long>()
    private var micSince = 0L
    private lateinit var app: Context

    fun start(ctx: Context) {
        if (started) return
        app = ctx.applicationContext
        load()
        val handler = Handler(Looper.getMainLooper())
        try {
            app.getSystemService(CameraManager::class.java)?.registerAvailabilityCallback(cameraCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "camera callback", e)
        }
        try {
            app.getSystemService(AudioManager::class.java)?.registerAudioRecordingCallback(audioCallback, handler)
        } catch (e: Exception) {
            Log.w(TAG, "audio callback", e)
        }
        started = true
    }

    private fun screenOff() = !(app.getSystemService(PowerManager::class.java)?.isInteractive ?: true)

    private fun inCall(): Boolean {
        val mode = app.getSystemService(AudioManager::class.java)?.mode ?: return false
        return mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
    }

    private fun cameraName(id: String): String = try {
        val cm = app.getSystemService(CameraManager::class.java)
        when (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "Frontkamera"
            CameraCharacteristics.LENS_FACING_BACK -> "Rückkamera"
            else -> "Kamera $id"
        }
    } catch (e: Exception) {
        "Kamera $id"
    }

    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        // Called on registration with the current state, too – only real transitions are logged.
        private val known = HashSet<String>()

        override fun onCameraUnavailable(cameraId: String) {
            val first = known.add(cameraId)
            if (first && !initialDone) return
            if (openCameras.containsKey(cameraId)) return
            val now = System.currentTimeMillis()
            openCameras[cameraId] = now
            cameraInUse.value = true
            begin("Kamera", cameraName(cameraId), now)
        }

        override fun onCameraAvailable(cameraId: String) {
            known.add(cameraId)
            if (openCameras.remove(cameraId) != null) {
                cameraInUse.value = openCameras.isNotEmpty()
                finish("Kamera", cameraName(cameraId))
            }
        }
    }

    // Initial callbacks arrive synchronously-ish right after registration; give them a moment.
    private val initialDone get() = started && System.currentTimeMillis() - startedAt > 3_000
    private val startedAt = System.currentTimeMillis()

    private val audioCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val real = configs.filter { it.clientAudioSource != HOTWORD_SOURCE }
            val now = System.currentTimeMillis()
            if (real.isNotEmpty() && micSince == 0L) {
                micSince = now
                micInUse.value = true
                begin("Mikrofon", sourceName(real.first().clientAudioSource), now)
            } else if (real.isEmpty() && micSince != 0L) {
                micSince = 0L
                micInUse.value = false
                finish("Mikrofon", null)
            }
        }
    }

    private fun sourceName(src: Int) = when (src) {
        0 -> "Aufnahme"
        1 -> "Mikrofon"
        5 -> "Videoaufnahme"
        6 -> "Spracherkennung"
        7 -> "Telefonie/VoIP"
        9 -> "Rohaufnahme"
        10 -> "Sprachaufnahme"
        else -> "Aufnahme ($src)"
    }

    private fun begin(kind: String, detail: String, now: Long) {
        val off = screenOff()
        val call = inCall()
        val u = Usage(kind, detail, now, null, off, call)
        log.value = (listOf(u) + log.value).take(150)
        save()
        if (off && !call) {
            Hub.raise(
                Alert(
                    "privacy-$kind-${now / 600_000}", now, Severity.HIGH, Source.SYSTEM,
                    "$kind bei ausgeschaltetem Bildschirm benutzt",
                    "Eine App hat die $detail eingeschaltet, während das Display aus war. Normale Apps dürfen das " +
                        "nicht unbemerkt. Sofort prüfen: Einstellungen › Sicherheit & Datenschutz › Datenschutz-Dashboard › $kind."
                ), dedupeMs = 10 * 60_000L
            )
        }
    }

    private fun finish(kind: String, detail: String?) {
        val now = System.currentTimeMillis()
        val list = log.value.toMutableList()
        val i = list.indexOfFirst { it.kind == kind && it.active && (detail == null || it.detail == detail) }
        if (i >= 0) {
            list[i] = list[i].copy(end = now)
            log.value = list
            save()
        }
    }

    fun clear() {
        log.value = log.value.filter { it.active }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        log.value.forEach {
            arr.put(
                JSONObject().put("k", it.kind).put("d", it.detail).put("s", it.start).put("e", it.end ?: -1L)
                    .put("off", it.screenOff).put("call", it.inCall)
            )
        }
        Hub.prefs().edit().putString("privacy_log", arr.toString()).apply()
    }

    private fun load() = try {
        val arr = JSONArray(Hub.prefs().getString("privacy_log", "[]"))
        log.value = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val end = o.getLong("e")
            // entries still "active" from a previous process ended at an unknown time
            Usage(o.getString("k"), o.getString("d"), o.getLong("s"), if (end < 0) o.getLong("s") else end,
                o.getBoolean("off"), o.getBoolean("call"))
        }
    } catch (e: Exception) {
        log.value = emptyList()
    }
}
