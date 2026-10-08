package app.borderless.data

import android.content.Context
import android.os.Build
import app.borderless.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The whole statistics database as a ZIP of CSV files (one per table) plus device / settings info,
 * for sending to the developer. No server names, addresses or links: servers appear only as their
 * internal ids, with group, protocol and country.
 */
object StatsExport {
    suspend fun build(context: Context): File = withContext(Dispatchers.IO) {
        StatsDb.flush()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val file = File(File(context.cacheDir, "share").apply { mkdirs() }, "borderless-stats-$stamp.zip")
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            suspend fun entry(name: String, write: suspend (OutputStreamWriter) -> Unit) {
                zip.putNextEntry(ZipEntry(name))
                // Not closed: that would close the ZIP; flushed instead.
                val w = OutputStreamWriter(zip, Charsets.UTF_8)
                write(w)
                w.flush()
                zip.closeEntry()
            }
            entry("info.txt") { it.write(info()) }
            entry("servers.csv") { w ->
                w.write("id,group,protocol,country,active,auto_pool\n")
                val pool = Repo.auto.value.pool
                Repo.servers.value.forEach { s ->
                    val group = Repo.groupName(Repo.group(Repo.groupOf(s))).replace(",", " ")
                    w.write("${s.id},$group,${s.protocol.replace(",", " ")},${s.country.orEmpty()},${Repo.isActive(s)},${s.id in pool}\n")
                }
            }
            for (t in StatsDb.TABLES) entry("$t.csv") { w -> StatsDb.dumpCsv(t, w) }
        }
        file
    }

    private fun info(): String {
        val s = Repo.settings.value
        return buildString {
            appendLine("Border(less) ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Exported: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())} (${TimeZone.getDefault().id})")
            appendLine()
            appendLine("strategy=${s.strategy} default=${s.defaultStrategy} autoActive=${s.autoActive} routing=${s.routingEnabled}/${s.routingMode} adFilter=${s.adFilter}")
            appendLine("healthIntervalSec=${s.healthIntervalSec} latencyCheckMin=${s.latencyCheckMin} rescanMin=${s.rescanIntervalMin} pauseScansScreenOff=${s.pauseScansScreenOff}")
            appendLine("probeTimeoutSec=${s.probeTimeoutSec} concurrency=${s.effectiveConcurrency} measureCurrent=${s.measureCurrent} statsDays=${s.statsDays}")
            appendLine()
            appendLine("Device: ${DeviceProfile.info.describe()}")
            appendLine("Energy model: CPU ${"%.3f".format(Energy.CPU_W)} W per busy core; radio ${"%.3f".format(Energy.CELL_W)} W on mobile data with ${Energy.CELL_TAIL_MS} ms tail, ")
            appendLine("${"%.3f".format(Energy.WIFI_W)} W on Wi-Fi with ${Energy.WIFI_TAIL_MS} ms tail; voltage and battery capacity from the phone (power.mv, power.cap_mah; ${Energy.VOLTAGE} V if unknown).")
            appendLine("activity.mj = estimated millijoules; measurements.measured_mj = from battery current (current.raw as reported by the phone).")
        }
    }
}
