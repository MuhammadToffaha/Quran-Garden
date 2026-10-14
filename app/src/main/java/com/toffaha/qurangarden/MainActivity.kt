package com.toffaha.qurangarden

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.util.Locale

/**
 * Quran Garden — a thin Android shell around the web app.
 * The app files live in assets/www and are served from
 * https://appassets.androidplatform.net so the page is a secure origin
 * (microphone, IndexedDB and Firebase sync all work like in Chrome).
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPermission: PermissionRequest? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val cb = fileCallback
            fileCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res.resultCode, res.data))
        }

    private val askMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val req = pendingPermission
            pendingPermission = null
            if (req != null) {
                if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else req.deny()
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen, kid friendly: hide the status/navigation bars (swipe to show them briefly).
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            textZoom = 100
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == APP_HOST) return false
                // Any outside link opens in the normal browser, never inside the kids' app.
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url)); true
                } catch (e: Exception) {
                    true
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        val has = ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (has) {
                            request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                        } else {
                            pendingPermission = request
                            askMic.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    } else {
                        request.deny()
                    }
                }
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    pickFile.launch(params.createIntent()); true
                } catch (e: Exception) {
                    fileCallback = null; false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "AndroidBridge")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.qgBack&&window.qgBack())?'1':'0'") { result ->
                    if (result == null || !result.contains("1")) finish()
                }
            }
        })

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("ar")
                tts?.setSpeechRate(0.9f)
                tts?.setPitch(1.1f)
                ttsReady = true
                pendingSpeech?.let { web.post { Bridge().speak(it) } }
                pendingSpeech = null
            }
        }

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState)
        } else {
            web.loadUrl(START_URL)
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    /** Small native helpers the page can call as window.AndroidBridge.* */
    inner class Bridge {
        @JavascriptInterface
        fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /** Saves a backup file into the device's Downloads folder. */
        @JavascriptInterface
        fun saveFile(name: String, mime: String, base64: String): Boolean {
            return try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return false
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: return false
                } else {
                    val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
                    File(dir, safeName).writeBytes(bytes)
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        /** Says a child's name out loud in Arabic (WebView has no speechSynthesis). */
        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                val engine = tts
                if (engine != null && ttsReady) {
                    val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f) }
                    engine.speak(text.take(60), TextToSpeech.QUEUE_FLUSH, params, "kid-name")
                } else {
                    pendingSpeech = text.take(60)
                }
            }
        }

        @JavascriptInterface
        fun isApp(): Boolean = true

        /** Installed version, compared with the latest GitHub release (tag v1.<versionCode>). */
        @JavascriptInterface
        fun versionCode(): Int = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
            else @Suppress("DEPRECATION") info.versionCode
        } catch (e: Exception) {
            0
        }

        /** Opens Android's share sheet (WhatsApp etc.) with a PNG made by the page, e.g. a certificate. */
        @JavascriptInterface
        fun shareImage(name: String, base64: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val dir = File(cacheDir, "share").apply { mkdirs() }
                val file = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                file.writeBytes(bytes)
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runOnUiThread { startActivity(Intent.createChooser(send, "مشاركة الشهادة")) }
            } catch (e: Exception) {
                runOnUiThread { notifyPage("shareerror") }
            }
        }

        /** Reads the daily nature picture list (Bing image of the day). Only bing.com is allowed. */
        @JavascriptInterface
        fun fetchText(url: String): String? {
            return try {
                val u = java.net.URL(url)
                if (u.protocol != "https" || u.host != "www.bing.com") return null
                val c = u.openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.inputStream.bufferedReader().use { it.readText().take(200_000) }
            } catch (e: Exception) {
                null
            }
        }

        /** Downloads the new APK from this app's own GitHub releases and opens the installer. */
        @JavascriptInterface
        fun installUpdate(url: String) {
            runOnUiThread { startUpdate(url) }
        }
    }

    // ---------- in-app update ----------
    private val handler = Handler(Looper.getMainLooper())
    private var downloadId = -1L

    private fun notifyPage(state: String, value: Int = 0) {
        web.evaluateJavascript("window.qgUpdateState&&window.qgUpdateState('$state',$value)", null)
    }

    private fun startUpdate(url: String) {
        // Only ever install APKs published by this project's own GitHub releases.
        if (!url.startsWith(UPDATE_PREFIX) || !url.endsWith(".apk")) {
            notifyPage("error"); return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            notifyPage("permission")
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                notifyPage("error")
            }
            return
        }
        try {
            val dir = File(getExternalFilesDir(null), "updates").apply { mkdirs() }
            val target = File(dir, "quran-garden-update.apk")
            if (target.exists()) target.delete()
            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(getString(R.string.app_name))
                .setDescription("تحديث جديد")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(target))
            downloadId = dm.enqueue(req)
            notifyPage("progress", 0)
            handler.post(object : Runnable {
                override fun run() {
                    val c = dm.query(DownloadManager.Query().setFilterById(downloadId))
                    if (c == null || !c.moveToFirst()) {
                        c?.close(); notifyPage("error"); return
                    }
                    val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    c.close()
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            notifyPage("ready", 100); openInstaller(target)
                        }
                        DownloadManager.STATUS_FAILED -> notifyPage("error")
                        else -> {
                            if (total > 0) notifyPage("progress", ((done * 100) / total).toInt())
                            handler.postDelayed(this, 500)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            notifyPage("error")
        }
    }

    private fun openInstaller(apk: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            notifyPage("error")
        }
    }

    companion object {
        private const val APP_HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$APP_HOST/assets/www/index.html"
        private const val UPDATE_PREFIX = "https://github.com/MuhammadToffaha/Quran-Garden/releases/download/"
    }
}
