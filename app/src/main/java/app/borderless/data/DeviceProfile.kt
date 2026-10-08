package app.borderless.data

import android.content.res.Resources
import android.os.Build
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * What the phone says about its own power use: Android's `power_profile` (the manufacturer's current
 * figures per component, the same table the system's battery statistics use) plus CPU cores and
 * frequencies. [Energy] takes its powers from here instead of generic values, and the spread inside
 * the table (slow core at a low frequency … fast core at full speed) gives the error margins.
 */
object DeviceProfile {
    /** A power estimate in watts with its plausible range. */
    data class Power(val low: Double, val mid: Double, val high: Double)

    data class Info(
        val soc: String,
        val cores: Int,
        val maxFreqMhz: List<Int>,
        val capacityMah: Int?,
        /** One busy CPU core. */
        val cpu: Power,
        /** Mobile radio while active. */
        val cell: Power,
        /** Wi-Fi radio while active. */
        val wifi: Power,
        /** "power_profile" when the phone's own table was usable, "generic" otherwise. */
        val source: String,
    ) {
        fun describe(): String = buildString {
            append("SoC $soc, $cores cores")
            if (maxFreqMhz.isNotEmpty()) append(" (max ${maxFreqMhz.distinct().sorted().joinToString("/")} MHz)")
            capacityMah?.let { append(", battery $it mAh") }
            append("; CPU %.2f W (%.2f–%.2f)".format(cpu.mid, cpu.low, cpu.high))
            append(", mobile radio %.2f W (%.2f–%.2f)".format(cell.mid, cell.low, cell.high))
            append(", Wi-Fi %.2f W (%.2f–%.2f)".format(wifi.mid, wifi.low, wifi.high))
            append(" [$source]")
        }
    }

    /** Typical values for a current phone, with wide margins (no usable table). */
    val GENERIC = Info(
        soc = "", cores = Runtime.getRuntime().availableProcessors(), maxFreqMhz = emptyList(), capacityMah = null,
        cpu = Power(0.2, 0.5, 1.2), cell = Power(0.6, 1.2, 2.2), wifi = Power(0.15, 0.35, 0.7), source = "generic",
    )

    @Volatile
    var info: Info = GENERIC
        private set

    private const val NOMINAL_V = 3.85

    /** [volts]: the battery's voltage right now if the phone reports it (the table's currents are at the battery). */
    fun load(volts: Double? = null) {
        info = runCatching { read(volts ?: NOMINAL_V) }.getOrNull() ?: GENERIC.copy(soc = soc(), maxFreqMhz = maxFreqs())
    }

    private fun soc(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE

    /** Max frequency of every core, from sysfs (readable on most phones; empty if not). */
    private fun maxFreqs(): List<Int> = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
        runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000 }.getOrNull()?.takeIf { it > 0 }
    }

    private fun read(volts: Double): Info? {
        val res = Resources.getSystem()
        val id = res.getIdentifier("power_profile", "xml", "android").takeIf { it != 0 } ?: return null
        val items = HashMap<String, Double>()
        val arrays = HashMap<String, MutableList<Double>>()
        res.getXml(id).use { p ->
            var array: String? = null
            var item: String? = null
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name) {
                        "item" -> item = p.getAttributeValue(null, "name")
                        "array" -> array = p.getAttributeValue(null, "name").also { arrays[it] = mutableListOf() }
                        "value" -> item = null
                    }
                    XmlPullParser.TEXT -> {
                        val v = p.text.trim().toDoubleOrNull()
                        if (v != null) {
                            val a = array
                            if (item != null) items[item!!] = v else if (a != null) arrays[a]?.add(v)
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name) {
                        "item" -> item = null
                        "array" -> array = null
                    }
                }
            }
        }
        return fromTable(items, arrays, soc(), Runtime.getRuntime().availableProcessors(), maxFreqs(), volts)
    }

    /**
     * Pure part (unit-tested): turns the table (currents in mA) into powers. CPU: per-core current
     * for each cluster and frequency step (plus a share of the cluster's own current); mid = middle
     * step averaged over clusters, low = slowest step of the most frugal cluster, high = top step of
     * the hungriest. Radios: average of receive / transmit currents, ±30 %.
     */
    fun fromTable(
        items: Map<String, Double>, arrays: Map<String, List<Double>>, soc: String, cores: Int, freqs: List<Int>,
        volts: Double = NOMINAL_V,
    ): Info? {
        fun w(mA: Double) = mA * volts / 1000
        val clusters = arrays.keys.filter { it.startsWith("cpu.core_power.cluster") }.sorted()
        val cpu = if (clusters.isNotEmpty()) {
            val steps = clusters.mapNotNull { c ->
                val core = arrays[c].orEmpty().filter { it > 0 }
                if (core.isEmpty()) null else {
                    val clusterMa = items["cpu.cluster_power." + c.substringAfter("cpu.core_power.")] ?: 0.0
                    core.map { it + clusterMa / 2 }
                }
            }
            if (steps.isEmpty()) null
            else Power(w(steps.minOf { it.first() }), w(steps.map { it[it.size / 2] }.average()), w(steps.maxOf { it.last() }))
        } else items["cpu.active"]?.let { Power(w(it) * 0.5, w(it), w(it) * 2) }
        val modemRx = items["modem.controller.rx"]
        val modemTx = arrays["modem.controller.tx"]?.filter { it > 0 }?.average()?.takeIf { !it.isNaN() } ?: items["modem.controller.tx"]
        val cellMa = when {
            modemRx != null && modemTx != null -> (modemRx + modemTx) / 2
            else -> items["radio.active"]
        }
        val wifiMa = items["wifi.controller.rx"]?.let { rx -> items["wifi.controller.tx"]?.let { (rx + it) / 2 } ?: rx } ?: items["wifi.active"]
        // Placeholders (AOSP defaults of 0.1 mA etc.) are useless: require plausible currents.
        fun plausible(p: Power?, lo: Double, hi: Double) = p?.takeIf { it.mid in lo..hi }
        val cpuP = plausible(cpu, 0.03, 4.0)
        val cellP = plausible(cellMa?.let { Power(w(it) * 0.7, w(it), w(it) * 1.3) }, 0.2, 4.0)
        val wifiP = plausible(wifiMa?.let { Power(w(it) * 0.7, w(it), w(it) * 1.3) }, 0.05, 2.0)
        if (cpuP == null && cellP == null && wifiP == null) return null
        return Info(
            soc = soc, cores = cores, maxFreqMhz = freqs,
            capacityMah = items["battery.capacity"]?.toInt()?.takeIf { it in 500..30_000 },
            cpu = cpuP ?: GENERIC.cpu, cell = cellP ?: GENERIC.cell, wifi = wifiP ?: GENERIC.wifi,
            source = "power_profile" + if (cpuP == null || cellP == null || wifiP == null) " (partly generic)" else "",
        )
    }
}
