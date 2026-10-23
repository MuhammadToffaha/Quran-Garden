package com.toffaha.qurangarden

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.pm.PackageInstaller
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
import java.util.zip.ZipInputStream
import org.json.JSONObject

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

        // Silent web updates: a newer copy of the page (downloaded in the background)
        // is served from internal storage first; anything missing falls back to the APK assets.
        applyPendingWeb()
        val webDir = File(filesDir, "web/current").apply { mkdirs() }
        // InternalStoragePathHandler answers an EMPTY response for a missing file (-> ERR_INVALID_RESPONSE),
        // so only use it when the file really exists; otherwise fall through to the APK's own assets.
        val internal = WebViewAssetLoader.InternalStoragePathHandler(this, webDir)
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/www/") { path ->
                val f = File(webDir, path)
                if (f.isFile && f.canonicalPath.startsWith(webDir.canonicalPath + File.separator)) internal.handle(path) else null
            }
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

            // the page itself failed to load -> look for a fixed version right away (ignores the daily limit)
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) checkSilent(true)
            }

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

    override fun onResume() {
        super.onResume()
        // Away for a while and a new version is waiting → switch to it now (the login screen shows anyway).
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 5 * 60_000 &&
            prefs.getInt("nextVer", 0) > 0) {
            applyPendingWeb(); web.reload()
        }
        stoppedAt = 0
        checkSilent(false)
    }

    override fun onStop() {
        super.onStop()
        stoppedAt = System.currentTimeMillis()
        // The app is in the background: a good moment to install a downloaded APK silently.
        installReadyApkSilently()
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

        /** Version the kid is really running: the APK, or a newer page downloaded silently. */
        @JavascriptInterface
        fun webVersion(): Int = maxOf(ownVersion(), prefs.getInt("webVer", 0))

        /** "on" = updates install by themselves, "confirm" = needs one tap, "off" = turned off by the parent. */
        @JavascriptInterface
        fun silentMode(): String = when {
            !prefs.getBoolean("auto", true) -> "off"
            prefs.getBoolean("confirm", false) -> "confirm"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls() &&
                prefs.getString("pendingKind", "") == "apk" -> "confirm"
            else -> "on"
        }

        /** Waiting update: version number, 0 if none. Applied next time the app opens. */
        @JavascriptInterface
        fun pendingVersion(): Int = maxOf(prefs.getInt("nextVer", 0), prefs.getInt("apkVer", 0))

        @JavascriptInterface
        fun setAutoUpdate(on: Boolean) {
            prefs.edit().putBoolean("auto", on).apply()
            if (on) checkSilent(true)
        }

        @JavascriptInterface
        fun checkSilentNow() = checkSilent(true)

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

    // ---------- silent update ----------
    private val prefs by lazy { getSharedPreferences("qg-update", Context.MODE_PRIVATE) }
    private var stoppedAt = 0L
    @Volatile private var checking = false

    private fun ownVersion(): Int = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (e: Exception) { 0 }

    /** Moves a downloaded page into place, and drops copies that are older than the installed APK. */
    private fun applyPendingWeb() {
        val root = File(filesDir, "web")
        val cur = File(root, "current")
        val next = File(root, "next")
        val nextVer = prefs.getInt("nextVer", 0)
        val ed = prefs.edit()
        if (nextVer > 0 && File(next, "index.html").exists()) {
            cur.deleteRecursively()
            if (next.renameTo(cur)) ed.putInt("webVer", nextVer)
        }
        ed.remove("nextVer")
        if (prefs.getInt("webVer", 0) <= ownVersion()) {
            File(cur, "index.html").delete(); cur.deleteRecursively(); cur.mkdirs(); ed.remove("webVer")
        }
        if (prefs.getInt("apkVer", 0) <= ownVersion()) {
            File(getExternalFilesDir(null), "updates").deleteRecursively()
            ed.remove("apkVer").remove("apkPath").remove("confirm").remove("pendingKind")
        }
        ed.apply()
    }

    private fun http(url: String, accept: String): java.net.HttpURLConnection {
        var u = url
        repeat(6) {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000
            c.readTimeout = 60000
            c.setRequestProperty("User-Agent", "QuranGarden-Android")
            c.setRequestProperty("Accept", accept)
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw Exception("redirect")
                c.disconnect()
                if (!loc.startsWith("https://")) throw Exception("insecure redirect")
                u = loc
            } else if (code in 200..299) return c
            else { c.disconnect(); throw Exception("HTTP $code") }
        }
        throw Exception("too many redirects")
    }

    /** Background check: page-only changes are downloaded and applied silently; native changes download the APK. */
    private fun checkSilent(force: Boolean) {
        if (!prefs.getBoolean("auto", true) || checking) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("lastCheck", 0) < 24 * 3600_000L) return   // once a day
        checking = true
        Thread {
            try {
                val c = http(API_LATEST, "application/vnd.github+json")
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                prefs.edit().putLong("lastCheck", now).apply()
                val n = Regex("^v?\\d+\\.(\\d+)$").find(j.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val have = maxOf(ownVersion(), prefs.getInt("webVer", 0), prefs.getInt("nextVer", 0))
                if (n <= have) return@Thread
                val assets = j.optJSONArray("assets")
                val urls = HashMap<String, String>()
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    urls[a.optString("name")] = a.optString("browser_download_url")
                }
                val nativeUrl = urls["native.txt"]
                val releaseNative = nativeUrl?.let {
                    http(it, "*/*").inputStream.bufferedReader().use { r -> r.readText().trim() }
                } ?: ""
                val zipUrl = urls["www.zip"]
                if (zipUrl != null && releaseNative.isNotEmpty() && releaseNative == BuildConfig.NATIVE_HASH) {
                    downloadWeb(zipUrl, n)
                } else if (n > prefs.getInt("apkVer", 0)) {
                    val apkUrl = urls["quran-garden.apk"] ?: return@Thread
                    if (!apkUrl.startsWith(UPDATE_PREFIX)) return@Thread
                    downloadApk(apkUrl, n)
                }
            } catch (e: Exception) {
                // offline or rate-limited: try again later
            } finally {
                checking = false
            }
        }.start()
    }

    private fun downloadWeb(url: String, ver: Int) {
        val root = File(filesDir, "web")
        val tmp = File(root, "next.tmp")
        tmp.deleteRecursively(); tmp.mkdirs()
        val base = tmp.canonicalPath + File.separator
        ZipInputStream(http(url, "*/*").inputStream.buffered()).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                val out = File(tmp, e.name)
                if (!out.canonicalPath.startsWith(base)) throw Exception("bad zip entry")
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it) }
                }
            }
        }
        if (!File(tmp, "index.html").exists()) { tmp.deleteRecursively(); return }
        val next = File(root, "next")
        next.deleteRecursively()
        if (tmp.renameTo(next)) {
            prefs.edit().putInt("nextVer", ver).putString("pendingKind", "web").apply()
            runOnUiThread { notifyPage("webready", ver) }
        }
    }

    private fun downloadApk(url: String, ver: Int) {
        val dir = File(getExternalFilesDir(null), "updates").apply { mkdirs() }
        val part = File(dir, "silent-$ver.part")
        val apk = File(dir, "silent-$ver.apk")
        http(url, "application/octet-stream").inputStream.use { inp -> part.outputStream().use { inp.copyTo(it) } }
        if (part.length() < 100_000 || !part.renameTo(apk)) { part.delete(); return }
        prefs.edit().putInt("apkVer", ver).putString("apkPath", apk.absolutePath)
            .putString("pendingKind", "apk").remove("confirm").apply()
        runOnUiThread { notifyPage("apkready", ver) }
    }

    /** Android 12+: an app that installed itself can update itself without a prompt. */
    private fun installReadyApkSilently() {
        if (!prefs.getBoolean("auto", true) || prefs.getBoolean("confirm", false)) return
        val ver = prefs.getInt("apkVer", 0)
        val apk = File(prefs.getString("apkPath", "") ?: "")
        if (ver <= ownVersion() || !apk.exists()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) { prefs.edit().putBoolean("confirm", true).apply(); return }
        if (!packageManager.canRequestPackageInstalls()) return
        try {
            val pi = packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(packageName)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val pending = PendingIntent.getBroadcast(
                    this, id, Intent(this, InstallReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                s.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            prefs.edit().putBoolean("confirm", true).apply()
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
        private const val API_LATEST = "https://api.github.com/repos/MuhammadToffaha/Quran-Garden/releases/latest"
        private const val UPDATE_PREFIX = "https://github.com/MuhammadToffaha/Quran-Garden/releases/download/"
    }
}

/** Result of a silent install. If Android still wants a tap, fall back to the normal update button. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status != PackageInstaller.STATUS_SUCCESS) {
            context.getSharedPreferences("qg-update", Context.MODE_PRIVATE).edit().putBoolean("confirm", true).apply()
        }
    }
}
