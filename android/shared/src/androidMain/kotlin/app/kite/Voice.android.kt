package app.kite

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/** Android's text-to-speech: other audio is turned down while it talks (music comes back up between instructions). */
actual object Speech {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()
    private val am by lazy { AndroidHost.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()
    private var speaking = 0
    private var report: (Boolean) -> Unit = {}

    actual fun warmUp(voiceMissing: (Boolean) -> Unit) {
        report = voiceMissing
        warmUp()
    }

    private fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(AndroidHost.app) { status ->
            if (status != TextToSpeech.SUCCESS) { noVoice(); return@TextToSpeech }
            val t = tts ?: return@TextToSpeech
            // Off the main thread: this callback comes on it, and asking the speech app for its voices
            // can take several seconds (Google's sends back a long list). Done here it froze Kite at launch
            // long enough for Android to report it as not responding.
            Thread { setUp(t) }.start()
        }
    }

    /** Language, best voice, speed and listener, on a background thread; then queued words are spoken. */
    private fun setUp(t: TextToSpeech) {
        runCatching {
            t.setAudioAttributes(attrs)
            val lang = Locale.getDefault().takeIf { it.language == "en" } ?: if (Fmt.yards) Locale.UK else Locale.US
            val ok = runCatching {
                t.setLanguage(lang) >= TextToSpeech.LANG_AVAILABLE || t.setLanguage(Locale.UK) >= TextToSpeech.LANG_AVAILABLE ||
                    t.setLanguage(Locale.US) >= TextToSpeech.LANG_AVAILABLE
            }.getOrDefault(false)
            if (!ok) { noVoice(); return }
            main.post { report(false) }
            // The best-sounding voice installed for that language that works offline.
            runCatching {
                t.voices?.filter { it.locale == t.voice?.locale && !it.isNetworkConnectionRequired && "notinstalled" !in it.features }
                    ?.maxByOrNull { it.quality }?.let { t.voice = it }
            }
            t.setSpeechRate(0.97f)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = finished()
                @Deprecated("old API") override fun onError(id: String?) = finished()
                override fun onStop(id: String?, interrupted: Boolean) = finished()
            })
        }.onFailure { noVoice(); return }
        main.post {
            // Kite may have given up on this one meanwhile (no voice found): only the current one counts.
            if (tts !== t) return@post
            ready = true
            pending.forEach { speak(it, false) }
            pending.clear()
        }
    }

    /** Nothing to speak with: say so on screen, and try again from scratch next time (one may be installed by then). */
    private fun noVoice() {
        main.post {
            Log.i("KiteVoice", "no text-to-speech voice")
            runCatching { tts?.shutdown() }
            tts = null
            ready = false
            pending.clear()
            report(true)
        }
    }

    /** The phone's own page for choosing a text-to-speech voice. */
    actual fun openSettings() {
        val c = AndroidHost.app
        val open = { action: String -> runCatching { c.startActivity(android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
        if (!open("com.android.settings.TTS_SETTINGS")) open(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    private fun finished() {
        main.postDelayed({
            speaking = maxOf(0, speaking - 1)
            if (speaking == 0 && tts?.isSpeaking != true) am.abandonAudioFocusRequest(focus)
        }, 300)
    }

    actual val isSpeaking: Boolean get() = tts?.isSpeaking == true

    actual fun speak(text: String, urgent: Boolean) {
        val said = text
        val t = tts
        if (t == null || !ready) { warmUp(); pending.add(said); return }
        am.requestAudioFocus(focus)
        speaking++
        t.speak(said, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "kite${System.nanoTime()}")
    }

    actual fun stop() {
        pending.clear()
        tts?.stop()
        speaking = 0
        am.abandonAudioFocusRequest(focus)
    }

    /** A phone (or WhatsApp, Signal...) call is going on. */
    actual val onCall: Boolean
        get() = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE

    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull() }

    /** A short tone before a camera warning, the way Waze does it. */
    actual fun chime() {
        am.requestAudioFocus(focus)
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
        main.postDelayed({ if (!isSpeaking) am.abandonAudioFocusRequest(focus) }, 600)
    }

    /** A warning buzz, like the iPhone's. */
    actual fun buzz() {
        val v = AndroidHost.app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        else v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1))
    }
}
