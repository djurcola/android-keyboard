package org.futo.inputmethod.latin.uix.actions

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import org.futo.inputmethod.latin.R

/** Private client-side record for the authenticated Offline Voice Input bridge. */
object OfflineVoiceBridgePairing {
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

    internal fun isExpectedPairingIntent(activity: Activity, intent: Intent?): Boolean = try {
        if (intent == null || intent.action != PAIR_ACTION ||
            activity.callingPackage != OVI_PACKAGE ||
            activity.callingActivity?.packageName != OVI_PACKAGE ||
            !intent.categories.isNullOrEmpty() || intent.data != null || intent.clipData != null ||
            intent.type != null
        ) false else {
            val extras = intent.extras
            extras != null && extras.keySet() == setOf(EXTRA_CAPABILITY) &&
                isValidCapability(intent.getStringExtra(EXTRA_CAPABILITY))
        }
    } catch (_: Throwable) {
        false
    }

    internal fun approve(context: Context, capability: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CAPABILITY, capability).apply()
    }
}

/** Explicit, result-bearing consent endpoint used only by Offline Voice Input. */
class OfflineVoiceBridgePairingActivity : ComponentActivity() {
    private var capability: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OfflineVoiceBridgePairing.isExpectedPairingIntent(this, intent)) {
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
