package app.borderless.ui

import app.borderless.data.Errors
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import app.borderless.core.TunnelState
import app.borderless.service.TunnelService

/**
 * Invisible activity used by the Quick Settings tile. It toggles the tunnel and,
 * on first use, shows the system permission dialog for the tunnel.
 */
class ToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (TunnelState.status.value.active) {
            TunnelService.stop()
            finish()
            return
        }
        val ok = Errors.guard("turning the connection on", false) {
            val prepare = VpnService.prepare(this)
            if (prepare == null) {
                TunnelService.start(this)
                finish()
            } else {
                @Suppress("DEPRECATION")
                startActivityForResult(prepare, REQUEST_PERMISSION)
            }
            true
        }
        if (!ok) finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PERMISSION && resultCode == RESULT_OK) TunnelService.start(this)
        finish()
    }

    companion object {
        private const val REQUEST_PERMISSION = 1
    }
}
