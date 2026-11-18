package app.couchtv.launcher.home

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The home talking to its own TV's ADB on 127.0.0.1:5555 (fork addition, the on-TV app's way,
 * on-tv/.../Adb.kt, plain TCP only). For what only the debug shell may read: the XGIMI projector's
 * battery, which XGIMI's power service tells the shell but no ordinary app. The TV asks "Allow
 * debugging?" once for the home's own key; with "Always allow" ticked that holds across restarts.
 *
 * The key and certificate are made on first use and kept in the home's private files.
 */
object SelfAdb {
    private const val TAG = "SelfAdb"
    private val io = Executors.newCachedThreadPool()
    private val lock = Any()
    private var mgr: Manager? = null
    @Volatile var connected = false; private set

    /** Whether the household has allowed the home's key before (then no question is waiting). */
    fun everAllowed(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE).getBoolean("selfadb_ok", false)

    /** Connects, waiting up to [waitS] seconds for "Allow debugging?". */
    fun connect(ctx: Context, waitS: Long): Boolean = synchronized(lock) {
        if (connected) return true
        val m = mgr ?: try { Manager(ctx.applicationContext).also { mgr = it } } catch (e: Throwable) { Log.w(TAG, "no key: $e"); return false }
        if (!portOpen()) { Log.i(TAG, "127.0.0.1:5555 is closed"); return false }
        m.setTimeout(waitS, TimeUnit.SECONDS)
        val f = io.submit<Boolean> { m.connect("127.0.0.1", 5555) }
        val ok = try { f.get(waitS * 1000 + 10_000, TimeUnit.MILLISECONDS) } catch (e: Throwable) {
            Log.w(TAG, "connect: ${e.cause ?: e}"); f.cancel(true); runCatching { m.close() }; mgr = null; false
        }
        Log.i(TAG, "connect: $ok")
        if (ok) { connected = true; ctx.getSharedPreferences("home", Context.MODE_PRIVATE).edit().putBoolean("selfadb_ok", true).apply() }
        ok
    }

    private fun portOpen() = try { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", 5555), 1500) }; true } catch (e: Exception) { false }

    /** Runs a shell command and returns what it printed; null when not connected or it failed. */
    fun shell(cmd: String, timeoutMs: Long = 10_000): String? = synchronized(lock) {
        val m = mgr ?: return null
        if (!connected) return null
        try {
            val s = m.openStream("shell:$cmd")
            val out = ByteArrayOutputStream()
            val f = io.submit<String> {
                s.openInputStream().use { inp -> val b = ByteArray(4096); while (true) { val n = try { inp.read(b) } catch (e: Exception) { -1 }; if (n < 0) break; synchronized(out) { out.write(b, 0, n) } } }
                String(out.toByteArray(), Charsets.UTF_8)
            }
            try { f.get(timeoutMs, TimeUnit.MILLISECONDS) } catch (e: Exception) {
                runCatching { s.close() }
                Log.w(TAG, "shell timed out (${e.javaClass.simpleName}) after: " + synchronized(out) { String(out.toByteArray()) }.take(200).replace("\n", " | "))
                null
            }
        } catch (e: Exception) {
            // the connection went stale (the TV slept, adbd restarted): the next call connects again
            Log.w(TAG, "shell: $e"); connected = false; runCatching { m.close() }; mgr = null; null
        }
    }

    private class Manager(c: Context) : AbsAdbConnectionManager() {
        val key: PrivateKey
        val cert: Certificate
        init {
            setApi(Build.VERSION.SDK_INT)
            setThrowOnUnauthorised(false)   // wait for "Allow" on the TV instead of failing at once
            val dir = File(c.filesDir, "adb").apply { mkdirs() }
            val kf = File(dir, "key.pk8"); val cf = File(dir, "cert.der")
            if (kf.isFile && cf.isFile) {
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
                cert = CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream())
            } else {
                val kpg = KeyPairGenerator.getInstance("RSA"); kpg.initialize(2048, SecureRandom())
                val kp = kpg.generateKeyPair()
                val name = X500Name("CN=couch-tv home")
                val now = Date()
                val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(SecureRandom().nextLong() and Long.MAX_VALUE),
                    Date(now.time - 86_400_000L), Date(now.time + 20L * 365 * 86_400_000L), name, kp.public)
                    .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
                key = kp.private
                cert = JcaX509CertificateConverter().getCertificate(holder)
                kf.writeBytes(key.encoded); cf.writeBytes(cert.encoded)
            }
        }
        override fun getPrivateKey() = key
        override fun getCertificate() = cert
        override fun getDeviceName() = "couch-tv home"
    }
}

/**
 * The XGIMI battery as its Settings (Projector) page reads it: XGIMI's power service, asked through
 * the debug shell (SelfAdb). Transaction 5 is getBatteryLevel, 3 isAdapterPowered (from the
 * projector's own library, com.xgimi.api: xgimi.hardware.gmpf.IPowerManager).
 */
object XgimiShell {
    private const val SVC = "xgimi.hardware.gmpf.IPowerManager/default"
    private val word = Regex("""Parcel\(\s*([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})""")
    private const val GOOGLE_HOME = "com.google.android.apps.tv.launcherx"

    /** The level (0 to 100) and whether the adapter is in; null when it cannot be read. */
    fun read(): Pair<Int, Boolean>? {
        val out = SelfAdb.shell("service call $SVC 5; service call $SVC 3") ?: return null
        val m = word.findAll(out).toList()
        if (m.size < 2 || m[0].groupValues[1] != "00000000" || m[1].groupValues[1] != "00000000") return null
        val lv = m[0].groupValues[2].toLong(16).toInt() and 0xFF
        val ac = m[1].groupValues[2].toLong(16) != 0L
        return if (lv in 0..100) lv to ac else null
    }

    /** The Google TV home switched itself back on (seen after a restart): off again, as apply.sh left it. */
    fun keepGoogleHomeOff(ctx: Context) {
        val off = SelfAdb.shell("pm list packages -d $GOOGLE_HOME") ?: return
        if (off.contains("package:$GOOGLE_HOME")) return
        // only while couch-tv is the home the household chose
        val role = SelfAdb.shell("cmd role get-role-holders android.app.role.HOME") ?: return
        if (!role.contains(ctx.packageName)) return
        Log.w("XgimiShell", "the Google TV home was back on: switching it off again")
        SelfAdb.shell("pm disable-user --user 0 $GOOGLE_HOME")
    }
}
