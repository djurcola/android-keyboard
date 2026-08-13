package org.futo.inputmethod.latin.uix.actions

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import dev.notune.transcribe.IOfflineVoiceBridge
import org.futo.inputmethod.latin.R

/** Private client-side record for the authenticated Offline Voice Input bridge. */
object OfflineVoiceBridgePairing {
    private const val TAG = "OfflineVoiceBridge"
    const val OVI_PACKAGE = "dev.notune.transcribe"
    const val PAIR_ACTION = "org.futo.inputmethod.latin.action.PAIR_OFFLINE_VOICE_BRIDGE"
    const val EXTRA_CAPABILITY = "dev.notune.transcribe.extra.PAIRING_CAPABILITY"
    const val EXTRA_ACCEPTED = "dev.notune.transcribe.extra.PAIRING_ACCEPTED"
    private const val PREFS = "offline_voice_bridge_client"
    private const val CAPABILITY = "capability"
    private val capabilityPattern = Regex("[A-Za-z0-9_-]{43}")

    fun capability(context: Context): String? = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(CAPABILITY, null)
        ?.takeIf(::isValidCapability)

    fun revoke(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    internal fun isValidCapability(value: String?): Boolean =
        value != null && capabilityPattern.matches(value)

    /**
     * Android does not consistently preserve activity caller metadata for this cross-app
     * result hand-off. The random capability is instead the pairing authority: a caller
     * that did not obtain it from OVI cannot later use OVI's Binder endpoint. Referrer is
     * optional because Samsung may strip it before delivery.
     */
    internal fun isExpectedPairingIntent(intent: Intent?): Boolean = try {
        if (intent == null || intent.action != PAIR_ACTION ||
            !intent.categories.isNullOrEmpty() || intent.data != null || intent.clipData != null ||
            intent.type != null
        ) false else {
            val extras = intent.extras
            extras != null && extras.keySet().all {
                it == EXTRA_CAPABILITY || it == Intent.EXTRA_REFERRER
            } &&
                isValidCapability(intent.getStringExtra(EXTRA_CAPABILITY))
        }
    } catch (_: Throwable) {
        false
    }

    internal fun approve(context: Context, capability: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CAPABILITY, capability).apply()
    }

    /** Pairs via the explicit OVI Binder service, avoiding cross-app activity-result quirks. */
    fun requestPairing(context: Context, completed: (Boolean) -> Unit) {
        var bound = false
        var finished = false
        lateinit var connection: ServiceConnection
        fun finish(success: Boolean) {
            if (finished) return
            finished = true
            if (bound) {
                try { context.unbindService(connection) } catch (_: Throwable) { }
            }
            completed(success)
        }
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try {
                    val capability = IOfflineVoiceBridge.Stub.asInterface(service).pair()
                    if (isValidCapability(capability)) {
                        Log.i(TAG, "Pairing capability received")
                        approve(context, capability)
                        finish(true)
                    } else {
                        Log.w(TAG, "Pairing returned an invalid capability")
                        finish(false)
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Pairing Binder call failed: ${error.javaClass.simpleName}")
                    finish(false)
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                Log.w(TAG, "Pairing service disconnected")
                finish(false)
            }
            override fun onBindingDied(name: ComponentName) {
                Log.w(TAG, "Pairing service binding died")
                finish(false)
            }
            override fun onNullBinding(name: ComponentName) {
                Log.w(TAG, "Pairing service returned a null binding")
                finish(false)
            }
        }
        try {
            bound = context.bindService(Intent().setComponent(ComponentName(
                OVI_PACKAGE, "$OVI_PACKAGE.OfflineVoiceBridgeService"
            )), connection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                Log.w(TAG, "Pairing bindService returned false")
                finish(false)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Pairing bindService failed: ${error.javaClass.simpleName}")
            finish(false)
        }
    }
}

/** Explicit, result-bearing consent endpoint used only by Offline Voice Input. */
class OfflineVoiceBridgePairingActivity : ComponentActivity() {
    private var capability: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OfflineVoiceBridgePairing.isExpectedPairingIntent(intent)) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        capability = intent.getStringExtra(OfflineVoiceBridgePairing.EXTRA_CAPABILITY)
        showConsent()
    }

    private fun showConsent() {
        AlertDialog.Builder(this)
            .setTitle(R.string.offline_voice_bridge_pairing_title)
            .setMessage(R.string.offline_voice_bridge_pairing_message)
            .setNegativeButton(android.R.string.cancel) { _, _ -> reject() }
            .setPositiveButton(R.string.offline_voice_bridge_pairing_allow) { _, _ -> approve() }
            .setOnCancelListener { reject() }
            .show()
            .setCanceledOnTouchOutside(false)
    }

    private fun approve() {
        val value = capability
        if (value == null || !OfflineVoiceBridgePairing.isValidCapability(value)) {
            reject()
            return
        }
        OfflineVoiceBridgePairing.approve(this, value)
        setResult(Activity.RESULT_OK, Intent().putExtra(OfflineVoiceBridgePairing.EXTRA_ACCEPTED, true))
        finish()
    }

    private fun reject() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
