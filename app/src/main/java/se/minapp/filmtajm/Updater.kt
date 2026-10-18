package se.minapp.filmtajm

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** En ny version av appen, enligt versionslappen på GitHub. */
data class AppUpdate(val versionCode: Long, val versionName: String, val apkUrl: String, val notes: String)

/**
 * Självuppdatering: läser versionslappen i senaste GitHub-releasen, laddar ner
 * den nya installationsfilen och lämnar över till Androids installerare.
 *
 * Säkerhet: allt hämtas med HTTPS, bara från vårt eget releaseförråd, filen
 * kontrolleras (rätt app, rätt version) och Android installerar bara en
 * uppdatering som är signerad med samma nyckel som den installerade appen.
 *
 * Alla funktioner som hämtar något måste anropas från en bakgrundstråd.
 */
object Updater {
    /** Förrådet på GitHub som bara innehåller installationsfiler, ingen kod. */
    private const val REPO = "https://github.com/fredrikagren-duvvan/agrens-filmtajm-releases"
    private const val VERSION_URL = "$REPO/releases/latest/download/version.json"
    private const val RELEASES = "$REPO/releases/download/"

    /** Svaret från Android när installationen kräver att användaren godkänner. */
    const val ACTION_INSTALL_STATUS = "se.minapp.filmtajm.INSTALL_STATUS"

    /** Utvecklingsversionen (från Android Studio) letar inte efter uppdateringar. */
    fun isDebugBuild(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    fun currentVersionCode(context: Context) =
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Hämtar versionslappen. Returnerar uppdateringen om den är nyare, annars null. */
    fun check(context: Context): AppUpdate? {
        if (isDebugBuild(context)) return null
        val connection = (URL(VERSION_URL).openConnection() as HttpsURLConnection).apply {
            useCaches = false
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        val update = try {
            // 404 betyder att inget har publicerats än.
            if (connection.responseCode == 404) null
            else {
                val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                AppUpdate(
                    versionCode = json.getLong("versionCode"),
                    versionName = json.getString("versionName"),
                    apkUrl = json.getString("apkUrl"),
                    notes = json.optString("notes", ""),
                ).also { require(it.apkUrl.startsWith(RELEASES)) { "Okänd adress för installationsfilen" } }
                    .takeIf { it.versionCode > currentVersionCode(context) }
            }
        } finally {
            connection.disconnect()
        }
        return update
    }

    /** Laddar ner installationsfilen. [onProgress] får värden mellan 0 och 1. */
    fun download(context: Context, update: AppUpdate, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "AgrensFilmtajm-${update.versionName}.apk")
        val connection = (URL(update.apkUrl).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            val total = connection.contentLengthLong.takeIf { it > 0 }
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total != null) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        // Kontrollera att det verkligen är rätt app och rätt version innan
        // vi ber Android installera den.
        val info = context.packageManager.getPackageArchiveInfo(target.path, 0)
        if (info?.packageName != context.packageName || info.longVersionCode != update.versionCode) {
            target.delete()
            error("Den nedladdade filen var inte rätt version av Agréns filmtajm.")
        }
        return target
    }

    /** Har användaren gett appen lov att installera appar? */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Inställningssidan där man ger appen lov att installera appar. */
    fun installPermissionIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * Lämnar filen till Androids installerare. Android svarar till [InstallReceiver],
     * som sedan visar Androids egen ruta "Vill du uppdatera appen?".
     *
     * Svaret går till en mottagare och inte direkt till appens fönster: nyare
     * Android (15+) blockerar tyst ett fönster som öppnas den vägen.
     */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        params.setSize(apk.length())
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }
            val reply = Intent(context, InstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
            // Måste vara "mutable" så att Android kan lägga till sitt svar.
            val pending = PendingIntent.getBroadcast(
                context, sessionId, reply,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(pending.intentSender)
        }
    }
}

/** Tar emot Androids svar under installationen av en uppdatering. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            // Android vill att användaren godkänner: visa Androids egen ruta.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") // Den nyare varianten finns först i Android 13.
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> {} // Användaren tryckte Avbryt.
            else -> Toast.makeText(
                context,
                "Uppdateringen kunde inte installeras. " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}
