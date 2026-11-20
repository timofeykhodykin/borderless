package app.borderless.core

import android.content.Context
import android.util.Base64
import app.borderless.data.AppLog
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import java.io.File
import java.security.SecureRandom

/** [XrayHost] on Android: libv2ray (gomobile) inside the app's own process. */
object AndroidXray : XrayHost {
    @Volatile
    private var ready = false
    private lateinit var appContext: Context

    fun attach(context: Context) {
        appContext = context.applicationContext
        CoreEnv.xray = this
    }

    override fun ensure() {
        if (!ready) init(appContext)
    }

    /** Copies geo databases out of the APK and points Xray at them. Safe to call repeatedly. */
    @Synchronized
    fun init(context: Context) {
        if (ready) return
        attach(context)
        val dir = File(context.filesDir, "geo").apply { mkdirs() }
        // The lists are downloaded (GeoLists); until then the files are there but empty.
        GeoLists.install(dir.path)
        app.borderless.data.GeoFile.dir = dir.path
        val prefs = context.getSharedPreferences("core", Context.MODE_PRIVATE)
        val key = prefs.getString("xudp", null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
            .let { Base64.encodeToString(it, Base64.NO_PADDING or Base64.URL_SAFE or Base64.NO_WRAP) }
            .also { prefs.edit().putString("xudp", it).apply() }
        Libv2ray.initCoreEnv(dir.absolutePath, key)
        app.borderless.data.AndroidLog.captureStdout()
        ready = true
        AppLog.debug("core", "Xray ${Libv2ray.checkVersionX()}")
    }

    override fun version(): String = Libv2ray.checkVersionX()

    override fun measureOutbound(config: String, url: String): Long = Libv2ray.measureOutboundDelay(config, url)

    override fun controller(log: (String) -> Unit): XrayHost.Controller = object : XrayHost.Controller {
        private val c = Libv2ray.newCoreController(object : CoreCallbackHandler {
            override fun startup(): Long = 0
            override fun shutdown(): Long = 0
            override fun onEmitStatus(p0: Long, p1: String?): Long {
                if (p1 != null) log(p1)
                return 0
            }
        })

        override val isRunning: Boolean get() = c.isRunning
        override fun start(config: String, tunFd: Int) = c.startLoop(config, tunFd)
        override fun stop() = c.stopLoop()
        override fun queryTraffic(): String = c.queryAllOutboundTrafficStats()
        override fun measureDelay(url: String): Long = c.measureDelay(url)
    }
}
