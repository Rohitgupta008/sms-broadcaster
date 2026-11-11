package app.gstreminder.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import app.gstreminder.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val build: Int, val apkUrl: String, val sizeBytes: Long, val title: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val percent: Int) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Error(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Checks the GitHub "latest release" of this repo. CI tags each release `build-N`, where N is the
 * same number the APK carries as its versionCode, so a higher N means a newer app.
 */
object Updater {
    private const val LATEST_URL = "https://api.github.com/repos/Rohitgupta008/sms-broadcaster/releases/latest"

    val currentBuild: Int get() = BuildConfig.VERSION_CODE
    val state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    suspend fun check(): UpdateState {
        if (state.value is UpdateState.Downloading || state.value is UpdateState.Installing) return state.value
        state.value = UpdateState.Checking
        val result = runCatching { fetchLatest() }.fold(
            onSuccess = { info -> if (info != null && info.build > currentBuild) UpdateState.Available(info) else UpdateState.UpToDate },
            onFailure = { UpdateState.Error("Could not check for updates: ${it.message ?: "no internet?"}") },
        )
        state.value = result
        return result
    }

    private suspend fun fetchLatest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "GST-Reminder-App")
        }
        try {
            if (conn.responseCode == 404) return@withContext null // no release yet
            if (conn.responseCode != 200) error("GitHub answered ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val build = Regex("""build-(\d+)""").find(json.optString("tag_name"))?.groupValues?.get(1)?.toInt()
                ?: return@withContext null
            val assets = json.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return@withContext UpdateInfo(build, a.getString("browser_download_url"), a.optLong("size"), json.optString("name"))
                }
            }
            null
        } finally {
            conn.disconnect()
        }
    }

    /** Android requires the user to allow "Install unknown apps" for this app once. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun downloadAndInstall(context: Context, info: UpdateInfo) {
        try {
            state.value = UpdateState.Downloading(info, 0)
            val apk = download(context, info)
            state.value = UpdateState.Installing(info)
            withContext(Dispatchers.IO) { install(context, apk) }
        } catch (e: Exception) {
            state.value = UpdateState.Error("Update failed: ${e.message}", info)
        }
    }

    private suspend fun download(context: Context, info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "GST-Reminder-App")
        }
        try {
            if (conn.responseCode != 200) error("download answered ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) state.value = UpdateState.Downloading(info, (done * 100 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (file.length() == 0L) error("downloaded file is empty")
        file
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Always show Android's "Update this app?" confirmation.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("GST-Reminder.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(context, id, Intent(context, InstallResultReceiver::class.java), flags)
            session.commit(callback.intentSender)
        }
    }
}

/** Receives the installer's progress: shows Android's confirmation screen, or reports an error. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val current = Updater.state.value
        val info = (current as? UpdateState.Installing)?.info
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.state.value = UpdateState.UpToDate
            else -> {
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Update cancelled."
                    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                        "This build is signed with a different key. Uninstall the app once, then install it again from the download link."
                    PackageInstaller.STATUS_FAILURE_BLOCKED ->
                        "Blocked by the phone (Play Protect?). Turn off \"Scan apps with Play Protect\" for a minute and try again."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage."
                    else -> intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed (code $status)."
                }
                Updater.state.value = UpdateState.Error(reason, info)
            }
        }
    }
}
