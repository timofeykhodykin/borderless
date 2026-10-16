package app.borderless.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.borderless.R
import app.borderless.data.Repo

/** The phone has a screen lock (PIN, pattern, password), so it can be asked for. */
fun canConfirm(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

/**
 * Asks for the phone's screen lock (fingerprint, face or PIN) before something that hands out server credentials —
 * sharing servers and subscriptions, the statistics export (SECURITY.md S13). Only with
 * [app.borderless.data.AppSettings.confirmShare] on and a screen lock set; otherwise `ok` runs at once.
 * Returns `(ok, cancel) -> Unit`.
 */
@Composable
fun rememberUnlock(): (ok: () -> Unit, cancel: () -> Unit) -> Unit {
    val context = LocalContext.current
    val title = stringResource(R.string.unlock_title)
    val pending = remember { arrayOfNulls<Pair<() -> Unit, () -> Unit>>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        pending[0]?.let { (ok, cancel) -> if (r.resultCode == Activity.RESULT_OK) ok() else cancel() }
        pending[0] = null
    }
    return remember(context, title) {
        { ok, cancel ->
            if (!Repo.settings.value.confirmShare || !canConfirm(context)) ok()
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val callback = object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = ok()
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = cancel()
                }
                val builder = BiometricPrompt.Builder(context).setTitle(title)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                } else {
                    @Suppress("DEPRECATION") builder.setDeviceCredentialAllowed(true)
                }
                builder.build().authenticate(CancellationSignal(), context.mainExecutor, callback)
            } else {
                @Suppress("DEPRECATION")
                val intent = context.getSystemService(KeyguardManager::class.java)?.createConfirmDeviceCredentialIntent(title, null)
                if (intent == null) ok() else {
                    pending[0] = ok to cancel
                    launcher.launch(intent)
                }
            }
        }
    }
}
