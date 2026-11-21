package app.kite

import platform.AVFAudio.*
import platform.AudioToolbox.*
import platform.Foundation.*
import platform.UIKit.*
import platform.darwin.NSObject

/** Apple's speech synthesizer: other audio is turned down while it talks, and handed back after. */
actual object Speech {
    private val synth = AVSpeechSynthesizer()
    private var speaking = 0

    private val delegate = object : NSObject(), AVSpeechSynthesizerDelegateProtocol {
        @kotlinx.cinterop.ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) = finished()
        @kotlinx.cinterop.ObjCSignatureOverride
        override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) = finished()
    }

    init { synth.delegate = delegate }

    private fun language(): String {
        val pref = NSLocale.preferredLanguages.firstOrNull() as? String ?: "en-GB"
        return if (pref.startsWith("en")) pref else if (Fmt.yards) "en-GB" else "en-US"
    }

    private fun voice(): AVSpeechSynthesisVoice? {
        val lang = language()
        // The best-sounding voice installed for that language.
        val all = AVSpeechSynthesisVoice.speechVoices().mapNotNull { it as? AVSpeechSynthesisVoice }.filter { it.language == lang }
        return all.maxByOrNull { it.quality } ?: AVSpeechSynthesisVoice.voiceWithLanguage(lang)
    }

    actual fun warmUp(voiceMissing: (Boolean) -> Unit) {
        voiceMissing(AVSpeechSynthesisVoice.speechVoices().none { (it as? AVSpeechSynthesisVoice)?.language?.startsWith("en") == true })
    }

    private fun session(active: Boolean) {
        val s = AVAudioSession.sharedInstance()
        runCatching {
            if (active) {
                s.setCategory(AVAudioSessionCategoryPlayback, AVAudioSessionModeVoicePrompt,
                    AVAudioSessionCategoryOptionDuckOthers or AVAudioSessionCategoryOptionInterruptSpokenAudioAndMixWithOthers, null)
                s.setActive(true, null)
            } else s.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
        }
    }

    actual fun speak(text: String, urgent: Boolean) {
        if (urgent) synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        session(true)
        speaking++
        val u = AVSpeechUtterance.speechUtteranceWithString(text)
        u.voice = voice()
        u.rate = AVSpeechUtteranceDefaultSpeechRate * 0.97f
        synth.speakUtterance(u)
    }

    private fun finished() {
        speaking = maxOf(0, speaking - 1)
        if (speaking == 0) NSOperationQueue.mainQueue.addOperationWithBlock {
            if (!synth.speaking) session(false)
        }
    }

    actual fun stop() {
        synth.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        speaking = 0
        session(false)
    }

    actual val isSpeaking: Boolean get() = synth.speaking

    actual val onCall: Boolean
        get() = AVAudioSession.sharedInstance().secondaryAudioShouldBeSilencedHint &&
            AVAudioSession.sharedInstance().currentRoute.outputs.any { (it as? AVAudioSessionPortDescription)?.portType == AVAudioSessionPortBuiltInReceiver }

    actual fun chime() {
        session(true)
        AudioServicesPlaySystemSound(1057u)
    }

    actual fun buzz() {
        UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
    }

    actual fun openSettings() {
        NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let { UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null) }
    }
}
