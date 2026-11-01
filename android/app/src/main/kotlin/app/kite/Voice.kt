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
import app.kite.core.SpokenText
import java.util.Locale

/**
 * Kite's voice: the phone's text-to-speech in the driver's English, turning other audio down
 * while it talks and handing it back after (music comes back up between instructions).
 */
object Voice {
    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()
    private val am by lazy { KiteApp.app.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val main = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs).build()
    private var speaking = 0
    /** Screenshot runs: never a sound. */
    var silent = false

    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(KiteApp.app) { status ->
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            val t = tts ?: return@TextToSpeech
            t.setAudioAttributes(attrs)
            val lang = Locale.getDefault().takeIf { it.language == "en" } ?: if (Fmt.yards) Locale.UK else Locale.US
            if (t.setLanguage(lang) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale.UK)
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
            main.post {
                ready = true
                pending.forEach { say(it) }
                pending.clear()
            }
        }
    }

    private fun finished() {
        main.postDelayed({
            speaking = maxOf(0, speaking - 1)
            if (speaking == 0 && tts?.isSpeaking != true) am.abandonAudioFocusRequest(focus)
        }, 300)
    }

    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    /** Says [text]; [urgent] cuts off whatever is being said first. */
    fun say(text: String, urgent: Boolean = false) {
        if (silent) return
        val said = SpokenText.forVoice(text)
        Log.i("KiteVoice", "say: $said")
        val t = tts
        if (t == null || !ready) { warmUp(); pending.add(said); return }
        am.requestAudioFocus(focus)
        speaking++
        t.speak(said, if (urgent) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "kite${System.nanoTime()}")
    }

    fun stop() {
        pending.clear()
        tts?.stop()
        speaking = 0
        am.abandonAudioFocusRequest(focus)
    }

    /** A phone (or WhatsApp, Signal...) call is going on. */
    val onCall: Boolean
        get() = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION || am.mode == AudioManager.MODE_RINGTONE

    private val tone by lazy { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull() }

    /** A short tone before a camera warning, the way Waze does it. */
    fun chime() {
        if (silent) return
        am.requestAudioFocus(focus)
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 220)
        main.postDelayed({ if (!isSpeaking) am.abandonAudioFocusRequest(focus) }, 600)
    }

    /** A warning buzz, like the iPhone's. */
    fun buzz() {
        val v = KiteApp.app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        else v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 80, 40), -1))
    }
}
