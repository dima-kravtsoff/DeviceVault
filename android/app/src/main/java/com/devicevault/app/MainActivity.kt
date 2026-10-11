package com.devicevault.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.MediaStore
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Native shell around the single-file web app. The page is served from the APK's assets on a
 * secure https origin (WebViewAssetLoader), so IndexedDB, localStorage and fetch() to the Gemini
 * and Drive APIs behave exactly as they do in a browser. Everything the page cannot do inside a
 * WebView (camera / file picker, opening links, saving files, Google sign-in, the back button)
 * is handled here and exposed to JavaScript through [Bridge] as `window.Android`.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val TAG = "DeviceVault"
        const val APP_URL = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}/assets/www/index.html"
        const val PROXY_PATH = "/proxy/"
    }

    lateinit var webView: WebView
        private set
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var bridge: Bridge
    lateinit var driveAuth: DriveAuth
        private set
    private lateinit var userAgent: String

    // ---- file chooser state ----
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraFile: File? = null
    private var cameraUri: Uri? = null

    private val fileChooser =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { deliverFiles(it) }
    private val createDocument =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { bridge.onCreateDocumentResult(it) }
    private val driveResolution =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { driveAuth.onResolutionResult(it) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val root = FrameLayout(this).apply { setBackgroundColor(getColor(R.color.page_bg)) }
        webView = WebView(this)
        root.addView(webView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)

        // Keep the page out from under the status bar, navigation bar and keyboard.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        bridge = Bridge(this)
        driveAuth = DriveAuth(this, driveResolution)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", ScriptAwareAssetHandler(WebViewAssetLoader.AssetsPathHandler(this)))
            .build()

        configureWebView()
        cleanOldCameraFiles()
        webView.loadUrl(APP_URL)

        // Hardware / gesture back: let the page close its own overlays and sub-screens first.
        onBackPressedDispatcher.addCallback(this) {
            webView.evaluateJavascript("(typeof dvBack==='function') && dvBack()===true") { result ->
                Log.i(TAG, "back -> page handled: $result")
                if (result != "true") finish()
            }
        }
    }

    private fun configureWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        userAgent = webView.settings.userAgentString.replace("; wv", "")
        applyDarkMode()
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.setBackgroundColor(getColor(R.color.page_bg))
        webView.addJavascriptInterface(bridge, "Android")
        webView.webViewClient = AppWebViewClient()
        webView.webChromeClient = AppChromeClient()
        // A plain link to a downloadable file (never used by the page itself, but harmless).
        webView.setDownloadListener { url, _, _, _, _ -> openExternal(url) }
    }

    /** Before Android 13 the WebView needs a nudge to honour the page's prefers-color-scheme rules. */
    @Suppress("DEPRECATION")
    private fun applyDarkMode() {
        if (Build.VERSION.SDK_INT >= 33) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) return
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        WebSettingsCompat.setForceDark(webView.settings, if (night) WebSettingsCompat.FORCE_DARK_ON else WebSettingsCompat.FORCE_DARK_OFF)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
            WebSettingsCompat.setForceDarkStrategy(webView.settings, WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY)
        }
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        webView.destroy()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ helpers used by Bridge

    fun callJs(js: String) = runOnUiThread { webView.evaluateJavascript(js, null) }

    fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }

    /** Opens an http(s) link in the user's browser (or the app registered for it). */
    fun openExternal(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme != "http" && uri.scheme != "https" && uri.scheme != "mailto" && uri.scheme != "tel") {
            Log.w(TAG, "Refusing to open non-web link: $url")
            return
        }
        Log.i(TAG, "openExternal $url")
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_browser))
        }
    }

    /** Hands a file the page produced (PDF, .ics) to another app via the share/open chooser. */
    fun openSharedFile(file: File, mime: String, name: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Log.i(TAG, "openFile $name ($mime, ${file.length()} bytes)")
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.open_with)))
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app_for_file))
            saveViaDocumentPicker(file, mime, name)
        }
    }

    /** Lets the user pick where to save a file (Downloads, Drive, …) through the system picker. */
    fun saveViaDocumentPicker(file: File, mime: String, name: String) {
        bridge.pendingSave = file
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mime)
            .putExtra(Intent.EXTRA_TITLE, name)
        Log.i(TAG, "saveFile $name ($mime, ${file.length()} bytes)")
        try {
            createDocument.launch(intent)
        } catch (e: ActivityNotFoundException) {
            bridge.pendingSave = null
            toast(getString(R.string.file_save_failed))
        }
    }

    // ------------------------------------------------------------------ file chooser

    private fun showFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        cameraFile = null
        cameraUri = null

        val accept = params.acceptTypes.filter { it.isNotBlank() }
        val wantsPdf = accept.any { it.contains("pdf", ignoreCase = true) }
        val wantsImages = accept.isEmpty() || accept.any { it.startsWith("image", ignoreCase = true) }
        val multiple = params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE

        val pick = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
        when {
            wantsPdf && !wantsImages -> pick.setType("application/pdf")
            wantsImages && !wantsPdf -> pick.setType("image/*")
            else -> pick.setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
        }
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)

        val extras = ArrayList<Intent>()
        if (wantsImages) {
            try {
                val dir = File(cacheDir, "camera").apply { mkdirs() }
                val file = File(dir, "IMG_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                val camera = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    .putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                camera.clipData = ClipData.newUri(contentResolver, "capture", uri)
                cameraFile = file
                cameraUri = uri
                extras.add(camera)
            } catch (e: Exception) {
                Log.w(TAG, "camera intent unavailable", e)
            }
        }

        val title = getString(if (wantsPdf && !wantsImages) R.string.choose_pdf else if (wantsImages && !wantsPdf) R.string.choose_photo else R.string.choose_file)
        val chooser = Intent.createChooser(pick, title)
        if (extras.isNotEmpty()) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, extras.toTypedArray())
        return try {
            fileChooser.launch(chooser)
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            toast(getString(R.string.choose_file))
            false
        }
    }

    private fun deliverFiles(result: ActivityResult) {
        val callback = fileCallback ?: return
        fileCallback = null
        val camFile = cameraFile
        val camUri = cameraUri
        cameraFile = null
        cameraUri = null

        if (result.resultCode != Activity.RESULT_OK) {
            camFile?.delete()
            callback.onReceiveValue(null)
            return
        }
        val data = result.data
        val uris = ArrayList<Uri>()
        data?.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        if (uris.isEmpty()) data?.data?.let(uris::add)
        if (uris.isEmpty() && camUri != null && camFile != null && camFile.length() > 0) uris.add(camUri)
        if (uris.isEmpty()) camFile?.delete()
        Log.i(TAG, "fileChooser -> ${uris.size} file(s)")
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private fun cleanOldCameraFiles() {
        val cutoff = System.currentTimeMillis() - 24L * 3600_000
        File(cacheDir, "camera").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        File(cacheDir, "shared").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ WebView clients

    private inner class AppWebViewClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN && url.path == PROXY_PATH) return proxyFetch(request)
            return assetLoader.shouldInterceptRequest(url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
            openExternal(url.toString())
            return true
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) Log.e(TAG, "page error ${error.errorCode}: ${error.description} (${request.url})")
        }
    }

    private inner class AppChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean = showFileChooser(filePathCallback, fileChooserParams)

        /**
         * target="_blank" links and window.open() arrive here. A throw-away WebView captures the
         * URL the popup would have navigated to, and it is opened in the browser instead.
         */
        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val popup = WebView(view.context)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                    openExternal(request.url.toString())
                    v.post { v.destroy() }
                    return true
                }
            }
            (resultMsg.obj as WebView.WebViewTransport).webView = popup
            resultMsg.sendToTarget()
            return true
        }

        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            val line = "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
            when (message.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Log.e("DVWeb", line)
                ConsoleMessage.MessageLevel.WARNING -> Log.w("DVWeb", line)
                else -> Log.d("DVWeb", line)
            }
            return true
        }
    }

    // ------------------------------------------------------------------ same-origin gateway

    /**
     * `https://appassets.androidplatform.net/proxy/?u=<https url>` — a GET gateway for the page.
     * fetch() in the page is bound by CORS like in any browser, which rules out reading search
     * results (DuckDuckGo) and downloading manuals from sites that do not send CORS headers.
     * Requests here are made natively with the WebView's own User-Agent and streamed back as a
     * same-origin response. Only https targets; only reachable from this WebView (the origin is
     * not routable). Runs on the WebView's IO thread, never on the UI thread.
     */
    private fun proxyFetch(request: WebResourceRequest): WebResourceResponse {
        if (request.method != "GET") return textResponse(405, "Method Not Allowed", "proxy: GET only")
        val target = request.url.getQueryParameter("u")
        val start = runCatching { URL(target) }.getOrNull()
        if (start == null || start.protocol != "https") return textResponse(400, "Bad Request", "proxy: an https URL is required")
        var conn: HttpURLConnection? = null
        return try {
            var url: URL = start
            var hops = 0
            while (true) {
                val c = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "text/html,application/xhtml+xml,application/pdf,*/*;q=0.8")
                    setRequestProperty("Accept-Language", "en-US,en;q=0.8")
                }
                conn = c
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location")?.let { URL(url, it) }
                    c.disconnect()
                    if (next == null || next.protocol != "https" || ++hops > 5) return textResponse(502, "Bad Gateway", "proxy: bad redirect")
                    url = next
                    continue
                }
                break
            }
            val c = conn!!
            val code = c.responseCode
            val contentType = c.contentType ?: "application/octet-stream"
            val mime = contentType.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
            val charset = Regex("charset=\"?([\\w-]+)", RegexOption.IGNORE_CASE).find(contentType)?.groupValues?.get(1)
            val body = (if (code >= 400) c.errorStream else c.inputStream) ?: ByteArrayInputStream(ByteArray(0))
            val headers = mutableMapOf("X-Final-Url" to url.toString(), "Cache-Control" to "no-store")
            c.getHeaderField("Content-Disposition")?.let { headers["Content-Disposition"] = it }
            Log.i(TAG, "proxy $code $mime $url")
            WebResourceResponse(mime, charset, code, reasonPhrase(code, c.responseMessage), headers, body)
        } catch (e: Exception) {
            conn?.disconnect()
            Log.w(TAG, "proxy failed for $target: $e")
            textResponse(502, "Bad Gateway", "proxy: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun reasonPhrase(code: Int, fromServer: String?): String =
        fromServer?.takeIf { it.isNotBlank() } ?: when (code) {
            200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            403 -> "Forbidden"; 404 -> "Not Found"; 429 -> "Too Many Requests"; 500 -> "Internal Server Error"
            502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "HTTP $code"
        }

    private fun textResponse(code: Int, reason: String, text: String): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", code, reason, mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(text.toByteArray()))

    /** Serves assets with a correct MIME type for ES modules (.mjs), which the stock handler guesses wrong. */
    private class ScriptAwareAssetHandler(private val inner: WebViewAssetLoader.PathHandler) : WebViewAssetLoader.PathHandler {
        override fun handle(path: String): WebResourceResponse? {
            val response = inner.handle(path) ?: return null
            when {
                path.endsWith(".mjs") || path.endsWith(".js") -> response.mimeType = "text/javascript"
                path.endsWith(".json") -> response.mimeType = "application/json"
                path.endsWith(".html") -> response.mimeType = "text/html"
            }
            return response
        }
    }
}
