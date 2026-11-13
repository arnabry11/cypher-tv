package tv.cypher.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import tv.cypher.core.AppInfo
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Downloads a newer APK, checks its SHA-256 and hands it to Android's installer. */
object Updater {
    fun download(ctx: Context, info: AppInfo, onProgress: (percent: Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val apk = File(dir, "cypher-tv.apk")
        Net.download(info.apkUrl, apk) { done, total -> if (total > 0) onProgress((done * 100 / total).toInt()) }
        val actual = sha256(apk)
        if (!actual.equals(info.sha256, ignoreCase = true)) {
            apk.delete()
            throw IOException("Checksum mismatch")
        }
        return apk
    }

    /** Returns false when Android first needs the user to allow installs from this app. */
    fun install(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(32 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
