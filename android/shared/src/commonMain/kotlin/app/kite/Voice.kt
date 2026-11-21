package app.kite

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.kite.core.SpokenText

/**
 * The phone's speech engine (Android's text-to-speech, Apple's speech synthesizer), turning other
 * audio down while it talks and handing it back after.
 */
expect object Speech {
    /** Gets the engine ready; [voiceMissing] says whether the phone has an English voice to speak with. */
    fun warmUp(voiceMissing: (Boolean) -> Unit)
    fun speak(text: String, urgent: Boolean)
    fun stop()
    val isSpeaking: Boolean
    /** A phone (or WhatsApp, Signal...) call is going on. */
    val onCall: Boolean
    /** A short tone before a camera warning, the way Waze does it. */
    fun chime()
    /** A warning buzz. */
    fun buzz()
    /** The phone's own page for choosing a voice. */
    fun openSettings()
}

/** Kite's voice, in the driver's English. */
object Voice {
    /** Screenshot runs: never a sound. */
    var silent = false
    /**
     * The phone has nothing to speak with: no text-to-speech app, or none with an English voice.
     * GrapheneOS can come like that. The drive screen says so, instead of a drive in silence.
     */
    var missing by mutableStateOf(false)

    fun warmUp() = Speech.warmUp { missing = it }

    /** Says [text]; [urgent] cuts off whatever is being said first. */
    fun say(text: String, urgent: Boolean = false) {
        val said = SpokenText.forVoice(text)
        // Logged even when silent, so a test drive can be checked afterwards.
        klog("KiteVoice", "say: $said")
        if (silent) return
        Speech.speak(said, urgent)
    }

    fun stop() = Speech.stop()
    val isSpeaking: Boolean get() = Speech.isSpeaking
    val onCall: Boolean get() = Speech.onCall
    fun chime() { if (!silent) Speech.chime() }
    fun buzz() = Speech.buzz()
    fun openSettings() = Speech.openSettings()
}
