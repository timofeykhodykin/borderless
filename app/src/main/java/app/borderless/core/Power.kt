package app.borderless.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager

/**
 * What the phone's power situation allows. Background work (pings, scans) is spread out when the
 * screen is off, on mobile data (the radio stays awake for seconds after every request), in battery
 * saver, and tightened again while charging. Reads system state on demand; nothing is polled.
 */
object Power {
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
    }

    private val ready get() = ::app.isInitialized

    val screenOn: Boolean
        get() = !ready || runCatching { app.getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)

    val powerSave: Boolean
        get() = ready && runCatching { app.getSystemService(PowerManager::class.java).isPowerSaveMode }.getOrDefault(false)

    /** Mobile data or another metered network (with the tunnel up this reflects the network underneath). */
    val metered: Boolean
        get() = ready && runCatching { app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(false)

    /**
     * On external power. Being plugged in counts even when the phone isn't charging at that moment: with a
     * charge limit (battery protection at 80 % and the like) it reports "not charging" while running from the
     * charger, and that time must not count as running on the battery.
     */
    val charging: Boolean
        get() = ready && runCatching {
            val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            plugged != 0 || status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }.getOrDefault(false)

    /**
     * Full battery capacity in mAh, worked out on any phone from the charge left (µAh) and the level
     * (%): no per-model table. Null if the phone does not report the charge counter.
     */
    val capacityMah: Int?
        get() = if (!ready) null else runCatching {
            val bm = app.getSystemService(BatteryManager::class.java)
            val charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (charge <= 0 || charge == Int.MIN_VALUE || pct !in 5..100) null
            else (charge / 1000.0 / (pct / 100.0)).toInt().takeIf { it in 500..30_000 }
        }.getOrNull()

    /** Battery voltage in mV, or null. */
    val voltageMv: Int?
        get() = if (!ready) null else runCatching {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        }.getOrNull()?.takeIf { it in 2500..5000 }

    /** Battery level in percent, or null if unknown. */
    val batteryPercent: Int?
        get() = if (!ready) null else runCatching {
            app.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        }.getOrNull()

    /**
     * How much to stretch intervals of network work (pings, scans): 1 while charging; otherwise ×2 on
     * mobile data and ×3 in battery saver (×6 for both).
     */
    fun stretch(): Double {
        if (charging) return 1.0
        return (if (metered) 2.0 else 1.0) * (if (powerSave) 3.0 else 1.0)
    }

    /** Whether optional network work (probing hidden servers) is worth the battery right now. */
    val spareBattery: Boolean get() = charging || (!powerSave && screenOn)
}
