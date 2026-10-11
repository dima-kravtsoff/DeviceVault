package com.devicevault.app

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.activity.result.ActivityResult
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * `window.Android` inside the page. Every method runs on the WebView's bridge thread, so UI work
 * is posted to the main thread. Files flow from JavaScript in base64 chunks so a big backup or a
 * 50 MB PDF never has to exist as one giant string.
 */
class Bridge(private val activity: MainActivity) {

    private class Transfer(val name: String, val mime: String, val action: String, val file: File, val out: FileOutputStream)

    private var transfer: Transfer? = null

    /** File waiting for the system "save as" picker to return a destination. */
    @Volatile
    var pendingSave: File? = null

    @JavascriptInterface
    fun openUrl(url: String) {
        activity.runOnUiThread { activity.openExternal(url) }
    }

    /** Starts a file transfer; [action] is "open" (hand to another app) or "save" (save-as picker). */
    @JavascriptInterface
    fun fileBegin(name: String, mime: String, action: String): Boolean {
        synchronized(this) {
            transfer?.let { runCatching { it.out.close() }; it.file.delete() }
            val dir = File(activity.cacheDir, "shared").apply { mkdirs() }
            val safe = name.replace(Regex("[^\\w.\\- ]+"), "_").trim().ifEmpty { "file" }
            val file = File(dir, safe)
            transfer = Transfer(safe, mime.ifBlank { "application/octet-stream" }, action, file, FileOutputStream(file))
            return true
        }
    }

    @JavascriptInterface
    fun fileChunk(base64: String) {
        synchronized(this) {
            val t = transfer ?: return
            t.out.write(Base64.decode(base64, Base64.DEFAULT))
        }
    }

    @JavascriptInterface
    fun fileEnd() {
        val t = synchronized(this) { transfer.also { transfer = null } } ?: return
        runCatching { t.out.close() }
        activity.runOnUiThread {
            if (t.action == "open") activity.openSharedFile(t.file, t.mime, t.name)
            else activity.saveViaDocumentPicker(t.file, t.mime, t.name)
        }
    }

    /** Result of the ACTION_CREATE_DOCUMENT picker started by [MainActivity.saveViaDocumentPicker]. */
    fun onCreateDocumentResult(result: ActivityResult) {
        val src = pendingSave ?: return
        pendingSave = null
        val target = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || target == null) {
            src.delete()
            return
        }
        Thread {
            try {
                activity.contentResolver.openOutputStream(target, "wt")!!.use { out -> src.inputStream().use { it.copyTo(out) } }
                Log.i(MainActivity.TAG, "saved ${src.name} -> $target")
                activity.toast(activity.getString(R.string.file_saved))
            } catch (e: Exception) {
                Log.e(MainActivity.TAG, "save failed", e)
                activity.toast(activity.getString(R.string.file_save_failed))
            } finally {
                src.delete()
            }
        }.start()
    }

    /** Asks Google Play services for a Drive access token; the answer arrives via window.__onNativeDriveToken. */
    @JavascriptInterface
    fun requestDriveToken(requestId: Int, interactive: Boolean) {
        activity.runOnUiThread { activity.driveAuth.request(requestId, interactive) }
    }

    /** SHA-1 of the signing certificate, needed when registering the Android OAuth client in Google Cloud. */
    @JavascriptInterface
    fun getSigningSha1(): String = signingSha1(activity)

    @JavascriptInterface
    fun getAppInfo(): String = JSONObject()
        .put("package", activity.packageName)
        .put("version", BuildConfig.VERSION_NAME)
        .put("sdk", android.os.Build.VERSION.SDK_INT)
        .put("debug", BuildConfig.DEBUG)
        .toString()

    companion object {
        fun signingSha1(activity: Activity): String = try {
            val info = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
            val cert = signers?.firstOrNull() ?: return ""
            MessageDigest.getInstance("SHA-1").digest(cert.toByteArray()).joinToString(":") { "%02X".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
