package com.example.navigation

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * A simple class that uses Android's TextToSpeech engine to speak texts.
 */
class VoiceAssistant(context: Context) {
    
    companion object {
        private val TAG = VoiceAssistant::class.java.simpleName
    }
    
    private val textToSpeech: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        if (status == TextToSpeech.ERROR) {
            Log.d(TAG, "ERROR: Initialization of Android's TextToSpeech failed.")
        }
    }
    
    private var utteranceId: String? = null
    private var messageId: Int = 0
    
    fun isLanguageAvailable(locale: Locale): Boolean {
        return textToSpeech.isLanguageAvailable(locale) == TextToSpeech.LANG_AVAILABLE
    }
    
    fun setLanguage(locale: Locale): Boolean {
        val isLanguageSet = textToSpeech.setLanguage(locale) == TextToSpeech.LANG_AVAILABLE
        return isLanguageSet
    }
    
    fun speak(speechMessage: String) {
        Log.d(TAG, "Voice message: $speechMessage")
        
        // No engine specific params used for this example
        val engineParams: Bundle? = null
        utteranceId = TAG + messageId++
        
        // QUEUE_FLUSH interrupts already speaking messages
        val error = textToSpeech.speak(speechMessage, TextToSpeech.QUEUE_FLUSH, engineParams, utteranceId)
        if (error != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Error when speaking using Android's TextToSpeech: $error")
        }
    }
}
