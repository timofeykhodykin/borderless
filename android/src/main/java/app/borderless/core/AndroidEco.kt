package app.borderless.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.core.content.ContextCompat

/** [Eco] on Android: follows the phone's battery saver while the process lives (checked at start as well). */
object AndroidEco {
    fun init(context: Context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = Eco.saverChanged()
        }
        ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        Eco.init()
    }
}
