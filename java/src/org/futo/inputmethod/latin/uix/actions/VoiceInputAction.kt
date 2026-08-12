package org.futo.inputmethod.latin.uix.actions

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.CloseResult
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.KeyboardManagerForAction
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.PersistentActionState
import org.futo.inputmethod.latin.uix.ResourceHelper
import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.VERBOSE_PROGRESS
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingsActivity
import org.futo.inputmethod.latin.uix.utils.ModelOutputSanitizer
import org.futo.inputmethod.latin.xlm.UserDictionaryObserver
import org.futo.inputmethod.updates.openURI
import org.futo.voiceinput.shared.ModelDoesNotExistException
import org.futo.voiceinput.shared.RecognizerView
import org.futo.voiceinput.shared.RecognizerViewListener
import org.futo.voiceinput.shared.RecognizerViewSettings
import org.futo.voiceinput.shared.RecordingSettings
import org.futo.voiceinput.shared.SoundPlayer
import org.futo.voiceinput.shared.types.Language
import org.futo.voiceinput.shared.types.ModelLoader
import org.futo.voiceinput.shared.types.getLanguageFromWhisperString
import org.futo.voiceinput.shared.ui.MicrophoneDeviceState
import org.futo.voiceinput.shared.whisper.DecodingConfiguration
import org.futo.voiceinput.shared.whisper.ModelManager
import org.futo.voiceinput.shared.whisper.MultiModelRunConfiguration
import java.util.Locale

private const val OFFLINE_VOICE_PACKAGE = "dev.notune.transcribe"
private val OfflineVoiceRecognitionService = ComponentName(
    OFFLINE_VOICE_PACKAGE, "$OFFLINE_VOICE_PACKAGE.VoiceRecognitionService"
)

private class SystemVoiceInputPersistentState(
    private val manager: KeyboardManagerForAction
) : PersistentActionState {
    private enum class State { Idle, Starting, Listening, Processing, Stopping }

    private val context = manager.getContext()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = State.Idle
    private var recognizer: SpeechRecognizer? = null
    private var inputTransaction: org.futo.inputmethod.latin.uix.ActionInputTransaction? = null
    private var sessionId = 0

    fun toggle() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { toggle() }
            return
        }

        when (state) {
            State.Idle -> start()
            State.Stopping -> feedback(R.string.action_system_voice_input_processing)
            else -> stop()
        }
    }

    private fun isOfflineServiceAvailable(): Boolean = context.packageManager.resolveService(
        Intent(RecognitionService.SERVICE_INTERFACE).setComponent(OfflineVoiceRecognitionService),
        0
    ) != null

    private fun start() {
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            feedback(R.string.action_system_voice_input_futo_microphone_permission)
            return
        }

        if (!isOfflineServiceAvailable()) {
            feedback(R.string.action_system_voice_input_offline_unavailable)
            manager.triggerSystemVoiceInput()
            return
        }

        val id = ++sessionId
        state = State.Starting
        feedback(R.string.action_system_voice_input_starting)
        try {
            inputTransaction = manager.createInputTransaction()
            val createdRecognizer = SpeechRecognizer.createSpeechRecognizer(
                context, OfflineVoiceRecognitionService
            )
            recognizer = createdRecognizer
            createdRecognizer.setRecognitionListener(listenerFor(id))
            createdRecognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH))
        } catch (_: Throwable) {
            fail(id)
        }
    }

    private fun stop() {
        val activeRecognizer = recognizer ?: return
        state = State.Stopping
        feedback(R.string.action_system_voice_input_processing)
        try {
            activeRecognizer.stopListening()
        } catch (_: Throwable) {
            fail(sessionId)
        }
    }

    private fun listenerFor(id: Int) = object : RecognitionListener {
        private fun onMain(block: () -> Unit) = mainHandler.post {
            if (id == sessionId && state != State.Idle) block()
        }

        override fun onReadyForSpeech(params: Bundle?) {
            onMain {
                if (state == State.Starting) {
                    state = State.Listening
                    feedback(R.string.action_system_voice_input_listening)
                }
            }
        }

        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            onMain {
                state = State.Processing
                feedback(R.string.action_system_voice_input_processing)
            }
        }

        override fun onError(error: Int) {
            onMain { fail(id, recognitionErrorMessage(error)) }
        }

        override fun onResults(results: Bundle?) {
            onMain {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull { it.isNotBlank() }
                if (text == null) {
                    fail(id)
                } else {
                    commit(id, text)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun commit(id: Int, text: String) {
        if (id != sessionId) return
        val transaction = inputTransaction
        inputTransaction = null
        var committed = false
        try {
            if (transaction == null) throw IllegalStateException("Missing voice input transaction")
            transaction.commit(ModelOutputSanitizer.sanitize(text, transaction.textContext))
            committed = true
            releaseRecognizer()
            state = State.Idle
            feedback(R.string.action_system_voice_input_completed)
        } catch (_: Throwable) {
            if (!committed) transaction?.cancel()
            releaseRecognizer()
            state = State.Idle
            feedback(R.string.action_system_voice_input_failed)
        }
    }

    private fun fail(id: Int, message: Int = R.string.action_system_voice_input_failed) {
        if (id != sessionId) return
        inputTransaction?.cancel()
        inputTransaction = null
        releaseRecognizer()
        state = State.Idle
        feedback(message)
    }

    private fun recognitionErrorMessage(error: Int): Int = when (error) {
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            R.string.action_system_voice_input_offline_microphone_permission
        SpeechRecognizer.ERROR_AUDIO -> R.string.action_system_voice_input_error_audio
        SpeechRecognizer.ERROR_CLIENT -> R.string.action_system_voice_input_error_client
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            R.string.action_system_voice_input_error_no_speech
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> R.string.action_system_voice_input_error_busy
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER -> R.string.action_system_voice_input_error_service
        else -> R.string.action_system_voice_input_failed
    }

    private fun releaseRecognizer() {
        val activeRecognizer = recognizer ?: return
        recognizer = null
        try {
            activeRecognizer.destroy()
        } catch (_: Throwable) {
            // The service may already have disconnected; it still must not be destroyed twice.
        }
    }

    private fun tearDown() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { tearDown() }
            return
        }
        ++sessionId // Ignore callbacks from the recognizer being released.
        inputTransaction?.cancel()
        inputTransaction = null
        state = State.Idle
        recognizer?.let {
            try { it.cancel() } catch (_: Throwable) { }
        }
        releaseRecognizer()
    }

    private fun feedback(message: Int) {
        val text = context.getString(message)
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        manager.announce(text)
    }

    override suspend fun cleanUp() = tearDown()
    override fun close() = tearDown()
}

val SystemVoiceInputAction = Action(
    icon = R.drawable.mic_fill,
    name = R.string.action_system_voice_input_title,
    simplePressImpl = { _, state ->
        (state as SystemVoiceInputPersistentState).toggle()
    },
    persistentState = { SystemVoiceInputPersistentState(it) },
    windowImpl = null,
    shownInEditor = false
)


@Composable
fun NoModelInstalled(locale: Locale) {
    val context = LocalContext.current
    Box(modifier = Modifier
        .fillMaxSize()
        .clickable(
            enabled = true,
            onClickLabel = null,
            onClick = {
                context.openURI("https://keyboard.futo.tech/voice-input-models", true)
            },
            role = null,
            indication = null,
            interactionSource = remember { MutableInteractionSource() })) {
        Text(
            stringResource(
                R.string.action_voice_input_no_model_for_language_x_installed,
                locale.getDisplayName(locale)
            ), modifier = Modifier
                .align(Alignment.Center)
                .padding(8.dp), textAlign = TextAlign.Center)
    }
}

class VoiceInputPersistentState(val manager: KeyboardManagerForAction) : PersistentActionState {
    val modelManager = ModelManager(manager.getContext())
    val soundPlayer = SoundPlayer(manager.getContext())
    val userDictionaryObserver = UserDictionaryObserver(manager.getContext())

    override suspend fun cleanUp() {
        modelManager.cleanUp()
    }

    override fun close() {
        runBlocking { modelManager.cleanUp() }
        userDictionaryObserver.unregister()
    }
}

private class VoiceInputActionWindow(
    val manager: KeyboardManagerForAction, val state: VoiceInputPersistentState,
    val model: ModelLoader, val locales: List<Locale>
) : ActionWindow(), RecognizerViewListener {
    val context = manager.getContext()

    private var shouldPlaySounds: Boolean = false
    private fun loadSettings(): RecognizerViewSettings {
        val enableSound = context.getSetting(ENABLE_SOUND)
        val verboseFeedback = false//context.getSetting(VERBOSE_PROGRESS)
        val disallowSymbols = context.getSetting(DISALLOW_SYMBOLS)
        val useBluetoothAudio = context.getSetting(PREFER_BLUETOOTH)
        val requestAudioFocus = context.getSetting(AUDIO_FOCUS)
        val canExpandSpace = context.getSetting(CAN_EXPAND_SPACE)
        val useVAD = context.getSetting(USE_VAD_AUTOSTOP)
        val usePersonalDict = context.getSetting(USE_PERSONAL_DICT)
        val animateBubble = context.getSetting(ANIMATE_BUBBLE)

        val primaryModel = model
        val languageSpecificModels = mutableMapOf<Language, ModelLoader>()
        val allowedLanguages = locales.mapNotNull { getLanguageFromWhisperString(it.language) }.toSet()
        val glossary = if(usePersonalDict) {
            state.userDictionaryObserver.getWords(locales).filter { it.shortcut.isNullOrEmpty() }.map { it.word }
        } else {
            emptyList()
        }

        shouldPlaySounds = enableSound

        return RecognizerViewSettings(
            shouldShowInlinePartialResult = false,
            shouldShowVerboseFeedback = verboseFeedback,
            shouldAnimateBubble = animateBubble,
            modelRunConfiguration = MultiModelRunConfiguration(
                primaryModel = primaryModel,
                languageSpecificModels = languageSpecificModels
            ),
            decodingConfiguration = DecodingConfiguration(
                glossary = glossary,
                languages = allowedLanguages,
                suppressSymbols = disallowSymbols
            ),
            recordingConfiguration = RecordingSettings(
                preferBluetoothMic = useBluetoothAudio,
                requestAudioFocus = requestAudioFocus,
                canExpandSpace = canExpandSpace,
                useVADAutoStop = useVAD
            )
        )
    }

    private var recognizerView: MutableState<RecognizerView?> = mutableStateOf(null)
    private var modelException: MutableState<ModelDoesNotExistException?> = mutableStateOf(null)

    private val initJob = manager.getLifecycleScope().launch(Dispatchers.Default) {
        yield()
        val settings = loadSettings()

        yield()
        val recognizerView = try {
            RecognizerView(
                context = manager.getContext(),
                listener = this@VoiceInputActionWindow,
                settings = settings,
                lifecycleScope = manager.getLifecycleScope(),
                modelManager = state.modelManager
            )
        } catch(e: ModelDoesNotExistException) {
            modelException.value = e
            return@launch
        }

        this@VoiceInputActionWindow.recognizerView.value = recognizerView

        //yield()
        recognizerView.reset()

        //yield()
        recognizerView.start()
    }

    private var inputTransaction = manager.createInputTransaction()

    @Composable
    private fun ModelDownloader(modelException: ModelDoesNotExistException) {
        NoModelInstalled(locales.firstOrNull() ?: Locale.ROOT)
    }

    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        Box(modifier = Modifier
            .fillMaxSize()
            .clickable(
                enabled = true,
                onClickLabel = null,
                onClick = { recognizerView.value?.finish() },
                role = null,
                indication = null,
                interactionSource = remember { MutableInteractionSource() })
            .semantics(mergeDescendants = true) {
                traversalIndex = -1.0f
            }) {
            Box(modifier = Modifier.align(Alignment.Center)) {
                when {
                    modelException.value != null -> ModelDownloader(modelException.value!!)
                    recognizerView.value != null -> recognizerView.value!!.Content()
                }
            }
        }
    }

    override fun close(): CloseResult {
        inputTransaction.cancel()
        runBlocking { initJob.cancelAndJoin() }
        recognizerView.value?.cancel()
        state.modelManager.cancelAll()
        return CloseResult.Default
    }

    private var wasFinished = false
    private var cancelPlayed = false
    override fun cancelled() {
        if (!wasFinished) {
            if (shouldPlaySounds && !cancelPlayed) {
                state.soundPlayer.playCancelSound()
                cancelPlayed = true
            }
            inputTransaction.cancel()
        }
    }

    override fun recordingStarted(device: MicrophoneDeviceState) {
        if (shouldPlaySounds) {
            state.soundPlayer.playStartSound()
        }

        // Only set the setting if bluetooth is available, else it would reset the setting
        // every time it's used without a bluetooth device connected.
        if(device.bluetoothAvailable) {
            manager.getLifecycleScope().launch {
                context.setSetting(PREFER_BLUETOOTH, device.bluetoothActive)
            }
        }
    }

    override fun finished(result: String) {
        wasFinished = true

        manager.getLifecycleScope().launch(Dispatchers.Main) {
            val sanitized = ModelOutputSanitizer.sanitize(result, inputTransaction.textContext)
            inputTransaction.commit(sanitized)
            manager.announce(result)
            manager.closeActionWindow()
        }
    }

    override fun partialResult(result: String) {
        manager.getLifecycleScope().launch(Dispatchers.Main) {
            val sanitized = ModelOutputSanitizer.sanitize(result, inputTransaction.textContext)
            inputTransaction.updatePartial(sanitized)
        }
    }

    override fun requestPermission(onGranted: () -> Unit, onRejected: () -> Unit): Boolean {
        return false
    }

    override fun openSettings() {
        SettingsActivity.openToNavDest(context, "languages")
    }
}

private class VoiceInputNoModelWindow(val locale: Locale) : ActionWindow() {
    @Composable
    override fun windowName(): String {
        return stringResource(R.string.action_voice_input_title)
    }

    @Composable
    override fun WindowContents(keyboardShown: Boolean) {
        NoModelInstalled(locale)
    }
}

val VoiceInputAction = Action(icon = R.drawable.mic_fill,
    name = R.string.action_voice_input_title,
    simplePressImpl = null,
    keepScreenAwake = true,
    persistentState = { VoiceInputPersistentState(it) },
    windowImpl = { manager, persistentState ->
        val locales = manager.getActiveLocales()

        val model = ResourceHelper.tryFindingVoiceInputModelForLocale(manager.getContext(), locales.firstOrNull() ?: Locale.ROOT)

        if(model == null) {
            VoiceInputNoModelWindow(locales.firstOrNull() ?: Locale.ROOT)
        } else {
            VoiceInputActionWindow(
                manager = manager, state = persistentState as VoiceInputPersistentState,
                locales = locales, model = model
            )
        }
    }
)