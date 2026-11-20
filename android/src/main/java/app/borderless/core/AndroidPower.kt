package app.borderless.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager

/** [Power]'s readings on Android, read from the system on demand. */
class AndroidPower(context: Context) : Power.Source {
    private val app = context.applicationContext

    override val screenOn: Boolean
        get() = runCatching { app.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)

    override val powerSave: Boolean
        get() = runCatching { app.getSystemService(PowerManager::class.java).isPowerSaveMode }.getOrDefault(false)

    /** Mobile data or another metered network (with the tunnel up this reflects the network underneath). */
    override val metered: Boolean
        get() = runCatching { app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(false)

    /**
     * On external power. Being plugged in counts even when the phone isn't charging at that moment: with a
     * charge limit (battery protection at 80 % and the like) it reports "not charging" while running from the
     * charger, and that time must not count as running on the battery.
     */
    override val charging: Boolean
        get() = runCatching {
            val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }.getOrDefault(false)

    /**
     * Full battery capacity in mAh, worked out on any phone from the charge left (µAh) and the level
     * (%): no per-model table. Null if the phone does not report the charge counter.
     */
    override val capacityMah: Int?
        get() = runCatching {
            val bm = app.getSystemService(BatteryManager::class.java)
            val charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (charge <= 0 || charge == Int.MIN_VALUE || pct !in 5..100) null
            else (charge / 1000.0 / (pct / 100.0)).toInt().takeIf { it in 500..30_000 }
        }.getOrNull()

    /** Battery voltage in mV, or null. */
    override val voltageMv: Int?
        get() = runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        }.getOrNull()?.takeIf { it in 2500..5000 }

    /** Battery level in percent, or null if unknown. */
    override val batteryPercent: Int?
        get() = runCatching {
            app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        }.getOrNull()
}
