package org.futo.inputmethod.latin.uix.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import dev.notune.transcribe.IOfflineVoiceBridge
import dev.notune.transcribe.IOfflineVoiceBridgeCallback
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

private val OfflineVoiceBridgeService = ComponentName(
    OfflineVoiceBridgePairing.OVI_PACKAGE,
    "${OfflineVoiceBridgePairing.OVI_PACKAGE}.OfflineVoiceBridgeService"
)

private class SystemVoiceInputPersistentState(
    private val manager: KeyboardManagerForAction
) : PersistentActionState {
    private enum class State { Idle, Starting, Listening, Processing, Stopping }

    private val context = manager.getContext()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var state = State.Idle
    private var bridge: IOfflineVoiceBridge? = null
    private var bound = false
    private var bridgeSessionStarted = false
    private var stopRequested = false
    private var inputTransaction: org.futo.inputmethod.latin.uix.ActionInputTransaction? = null
    private var sessionId = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            try {
                bridge = IOfflineVoiceBridge.Stub.asInterface(service)
                service.linkToDeath(bridgeDied, 0)
                onBridgeConnected(sessionId)
            } catch (_: Throwable) {
                unavailable(sessionId)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = onBridgeDied()
        override fun onBindingDied(name: ComponentName) = onBridgeDied()
        override fun onNullBinding(name: ComponentName) = unavailable(sessionId)
    }

    private val bridgeDied = IBinder.DeathRecipient { mainHandler.post { onBridgeDied() } }

    fun toggle() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { toggle() }
            return
        }
        when (state) {
            State.Idle -> start()
            State.Processing, State.Stopping -> feedback(R.string.action_system_voice_input_processing)
            State.Starting, State.Listening -> stop()
        }
    }

    private fun start() {
        val capability = OfflineVoiceBridgePairing.capability(context)
        if (capability == null) {
            // There is no pairing to authorize, so retain the normal system voice-input route.
            manager.triggerSystemVoiceInput()
            return
        }
        val id = ++sessionId
        state = State.Starting
        stopRequested = false
        feedback(R.string.action_system_voice_input_starting)
        try {
            bound = context.bindService(
                Intent().setComponent(OfflineVoiceBridgeService),
                connection,
                // Pass the visible IME's while-in-use microphone capability to OVI.
                // Required by Android 11+ for a bound background app to use it.
                Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES
            )
            if (!bound) unavailable(id)
        } catch (_: Throwable) {
            unavailable(id)
        }
    }

    private fun onBridgeConnected(id: Int) {
        if (id != sessionId || state == State.Idle) return
        val service = bridge ?: run { unavailable(id); return }
        val capability = OfflineVoiceBridgePairing.capability(context) ?: run {
            fail(id); return
        }
        try {
            // Authorization failures deliberately do not fall back to SpeechRecognizer.
            if (!service.isPaired(capability)) {
                fail(id, R.string.action_system_voice_input_failed)
                return
            }
            inputTransaction = manager.createInputTransaction()
            service.start(capability, callbackFor(id))
            bridgeSessionStarted = true
            if (stopRequested) stop()
        } catch (_: Throwable) {
            fail(id, R.string.action_system_voice_input_failed)
        }
    }

    private fun stop() {
        stopRequested = true
        state = State.Stopping
        feedback(R.string.action_system_voice_input_processing)
        if (!bridgeSessionStarted) return
        try {
            val capability = OfflineVoiceBridgePairing.capability(context) ?: run {
                fail(sessionId)
                return
            }
            bridge?.stop(capability)
        } catch (_: Throwable) {
            fail(sessionId)
        }
    }

    private fun callbackFor(id: Int) = object : IOfflineVoiceBridgeCallback.Stub() {
        private fun onMain(block: () -> Unit) = mainHandler.post {
            if (id == sessionId && state != State.Idle) block()
        }

        override fun onState(bridgeState: Int) = onMain {
            when (bridgeState) {
                BRIDGE_STATE_LISTENING -> {
                    state = State.Listening
                    feedback(R.string.action_system_voice_input_listening)
                }
                BRIDGE_STATE_PROCESSING -> {
                    state = State.Processing
                    feedback(R.string.action_system_voice_input_processing)
                }
            }
        }

        override fun onResult(text: String?) = onMain {
            if (text.isNullOrBlank()) fail(id) else commit(id, text)
        }

        override fun onError(code: Int, userMessage: String?) = onMain {
            fail(id, message = userMessage?.takeIf { it.isNotBlank() })
        }
    }

    private fun commit(id: Int, text: String) {
        if (id != sessionId) return
        val transaction = inputTransaction
        inputTransaction = null
        try {
            if (transaction == null) throw IllegalStateException("Missing voice input transaction")
            transaction.commit(ModelOutputSanitizer.sanitize(text, transaction.textContext))
            finishBinding(cancelBridge = false)
            state = State.Idle
            feedback(R.string.action_system_voice_input_completed)
        } catch (_: Throwable) {
            transaction?.cancel()
            finishBinding(cancelBridge = true)
            state = State.Idle
            feedback(R.string.action_system_voice_input_failed)
        }
    }

    private fun unavailable(id: Int) {
        if (id != sessionId || bridgeSessionStarted) return
        finishBinding(cancelBridge = false)
        state = State.Idle
        feedback(R.string.action_system_voice_input_offline_unavailable)
        manager.triggerSystemVoiceInput()
    }

    private fun fail(id: Int, message: Int = R.string.action_system_voice_input_failed) {
        if (id != sessionId) return
        inputTransaction?.cancel()
        inputTransaction = null
        finishBinding(cancelBridge = bridgeSessionStarted)
        state = State.Idle
        feedback(message)
    }

    private fun fail(id: Int, message: String?) {
        if (id != sessionId) return
        inputTransaction?.cancel()
        inputTransaction = null
        finishBinding(cancelBridge = bridgeSessionStarted)
        state = State.Idle
        if (message == null) feedback(R.string.action_system_voice_input_failed) else feedback(message)
    }

    private fun onBridgeDied() {
        if (state == State.Idle) return
        inputTransaction?.cancel()
        inputTransaction = null
        finishBinding(cancelBridge = false)
        state = State.Idle
        feedback(R.string.action_system_voice_input_failed)
    }

    private fun finishBinding(cancelBridge: Boolean) {
        val service = bridge
        val capability = OfflineVoiceBridgePairing.capability(context)
        if (cancelBridge && bridgeSessionStarted && service != null && capability != null) {
            try { service.cancel(capability) } catch (_: Throwable) { }
        }
        service?.asBinder()?.let {
            try { it.unlinkToDeath(bridgeDied, 0) } catch (_: Throwable) { }
        }
        bridge = null
        bridgeSessionStarted = false
        stopRequested = false
        if (bound) {
            try { context.unbindService(connection) } catch (_: Throwable) { }
            bound = false
        }
    }

    private fun tearDown() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { tearDown() }
            return
        }
        ++sessionId
        inputTransaction?.cancel()
        inputTransaction = null
        finishBinding(cancelBridge = bridgeSessionStarted)
        state = State.Idle
    }

    private fun feedback(message: Int) = feedback(context.getString(message))
    private fun feedback(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        manager.announce(message)
    }

    override suspend fun cleanUp() = tearDown()
    override fun close() = tearDown()
}

private const val BRIDGE_STATE_LISTENING = 2
private const val BRIDGE_STATE_PROCESSING = 3

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