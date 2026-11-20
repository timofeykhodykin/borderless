package app.borderless.core

/**
 * What the device's power situation allows. Background work (pings, scans) is spread out when the
 * screen is off, on mobile data (the radio stays awake for seconds after every request), in battery
 * saver, and tightened again while charging. Reads system state on demand ([source], the platform's); nothing is
 * polled. Without a source (tests): screen on, nothing else known.
 */
object Power {
    /** The platform's readings (Android: PowerManager / BatteryManager; the Mac: displays, Low Power Mode, power source). */
    interface Source {
        val screenOn: Boolean
        val powerSave: Boolean
        /** Mobile data or another metered network (with the tunnel up this reflects the network underneath). */
        val metered: Boolean
        /** On external power (plugged in counts even when a charge limit keeps it from charging). */
        val charging: Boolean
        /** Full battery capacity in mAh, null if unknown or no battery. */
        val capacityMah: Int?
        /** Battery voltage in mV, or null. */
        val voltageMv: Int?
        /** Battery level in percent, or null if unknown. */
        val batteryPercent: Int?
    }

    var source: Source? = null

    val screenOn: Boolean get() = source?.screenOn ?: true
    val powerSave: Boolean get() = source?.powerSave ?: false
    val metered: Boolean get() = source?.metered ?: false
    val charging: Boolean get() = source?.charging ?: false
    val capacityMah: Int? get() = source?.capacityMah
    val voltageMv: Int? get() = source?.voltageMv
    val batteryPercent: Int? get() = source?.batteryPercent

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
