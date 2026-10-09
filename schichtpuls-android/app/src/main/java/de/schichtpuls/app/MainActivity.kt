package de.schichtpuls.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import java.io.File

/**
 * Hosts the Schichtpuls page (assets/index.html) in a WebView.
 *
 * The page is served from https://appassets.androidplatform.net so it gets a normal https origin
 * (localStorage, fetch to the Anthropic API). The activity supplies what a WebView lacks on its own:
 * the photo picker and camera for <input type=file>, saving exports to Downloads, and opening
 * external links in the browser.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var cameraUri: Uri? = null

    private val pickImages =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)) { uris ->
            deliver(uris.toTypedArray())
        }
    private val takePicture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = cameraUri
            deliver(if (saved && uri != null) arrayOf(uri) else emptyArray())
        }
    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            deliver(if (uri != null) arrayOf(uri) else emptyArray())
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assets = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this).apply {
            setBackgroundColor(0xFF070B14.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = true
            // target=_blank links must reach shouldOverrideUrlLoading instead of a new window.
            settings.setSupportMultipleWindows(false)
            addJavascriptInterface(Bridge(), "SchichtpulsApp")
            webViewClient = object : WebViewClientCompat() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                    assets.shouldInterceptRequest(request.url)

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == APP_HOST) return false
                    openExternal(request.url)
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    params: FileChooserParams,
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    val accept = params.acceptTypes.joinToString(",").lowercase()
                    return try {
                        when {
                            params.isCaptureEnabled -> launchCamera()
                            accept.isBlank() || accept.contains("image") -> pickImages.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                            else -> openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                        }
                        true
                    } catch (e: ActivityNotFoundException) {
                        fileCallback = null
                        false
                    }
                }
            }
        }
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl("https://$APP_HOST/assets/index.html")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    private fun deliver(uris: Array<Uri>) {
        fileCallback?.onReceiveValue(uris)
        fileCallback = null
    }

    private fun launchCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the newest photo
        val file = File(dir, "plan_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        cameraUri = uri
        takePicture.launch(uri)
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Called from the page as window.SchichtpulsApp. */
    inner class Bridge {
        /** Saves an export into the phone's Downloads folder. Returns "ok", "shared" or "error". */
        @JavascriptInterface
        fun saveFile(name: String, mime: String, content: String): String {
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                        put(MediaStore.Downloads.MIME_TYPE, mime)
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: return "error"
                    contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) } ?: return "error"
                    "ok"
                } else {
                    // Android 8/9: no permission-free Downloads access, so hand the file to the share sheet.
                    val dir = File(cacheDir, "exports").apply { mkdirs() }
                    val file = File(dir, safeName).apply { writeText(content) }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runOnUiThread { startActivity(Intent.createChooser(send, safeName)) }
                    "shared"
                }
            } catch (e: Exception) {
                "error"
            }
        }
    }

    private companion object {
        const val APP_HOST = "appassets.androidplatform.net"
        const val MAX_PHOTOS = 4
    }
}
