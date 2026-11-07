package id.altair.musevpn

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Browser satu situs + tunnel WireGuard per-aplikasi.
 *
 * Aturan keselamatan:
 *  - WebView hanya memuat BuildConfig.SITE_HOST (https) dan subdomainnya.
 *  - Selama tunnel belum UP, semua permintaan http(s) diblokir (kill switch).
 *  - Tunnel turun di tengah jalan -> halaman dihentikan, bukan lanjut pakai IP asli.
 */
class MainActivity : ComponentActivity() {

    private lateinit var statusView: TextView
    private lateinit var webView: WebView
    private lateinit var fullscreenLayer: FrameLayout

    private var tunnelUp = false
    private var customView: View? = null

    private val confFile: File by lazy { File(filesDir, "profile.conf.enc") }

    private val importConf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importAndConnect(uri)
    }

    private val vpnConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) connectNow()
        else setStatus(getString(R.string.consent_denied))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TunnelManager.init(this)
        buildUi()
        observeTunnel()
        askNotifications()
        if (confFile.exists()) requestConsent() else setStatus(getString(R.string.need_conf))
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        val root = FrameLayout(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(10))
        }

        statusView = TextView(this).apply {
            textSize = 14f
            text = getString(R.string.connecting)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        row.addView(button(R.string.btn_import) { pickConf() }, weight())
        row.addView(button(R.string.btn_connect) { requestConsent() }, weight())
        row.addView(button(R.string.btn_exit) { exitApp() }, weight())

        topBar.addView(statusView)
        topBar.addView(row)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.setSupportMultipleWindows(false)
            visibility = View.GONE
            webViewClient = LockedClient()
            webChromeClient = FullscreenChrome()
        }
        CookieManager.getInstance().setAcceptCookie(true)

        column.addView(topBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(webView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        fullscreenLayer = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
        }

        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(fullscreenLayer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideFullscreen()
                    webView.canGoBack() -> webView.goBack()
                    else -> setStatus(getString(R.string.back_hint))
                }
            }
        })
    }

    private fun button(labelRes: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            text = getString(labelRes)
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun setStatus(text: String) {
        statusView.text = text
    }

    // ------------------------------------------------------- state tunnel

    private fun observeTunnel() {
        lifecycleScope.launch {
            TunnelManager.state.collect { state ->
                tunnelUp = state == Tunnel.State.UP
                if (tunnelUp) {
                    setStatus(getString(R.string.vpn_up))
                    webView.visibility = View.VISIBLE
                    TunnelFgs.start(this@MainActivity)
                    if (webView.url == null) webView.loadUrl(BuildConfig.SITE_URL)
                } else {
                    lockBrowser()
                }
            }
        }
    }

    /** Kill switch aplikasi: tunnel turun => semua halaman dihentikan. */
    private fun lockBrowser() {
        hideFullscreen()
        webView.stopLoading()
        webView.loadUrl("about:blank")
        webView.visibility = View.GONE
        TunnelFgs.stop(this)
        setStatus(getString(R.string.vpn_down))
    }

    // --------------------------------------------------------- konfigurasi

    private fun pickConf() {
        importConf.launch(arrayOf("*/*"))
    }

    private fun importAndConnect(uri: Uri) {
        lifecycleScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                        ?: throw IllegalStateException("file tidak bisa dibaca")
                }
                // Validasi dulu supaya file salah tidak tersimpan.
                withContext(Dispatchers.IO) { WgConfigLoader.loadAppOnly(text, packageName) }
                withContext(Dispatchers.IO) {
                    SecureStore.save(confFile, text.toByteArray(Charsets.UTF_8))
                }
                setStatus(getString(R.string.conf_saved))
                requestConsent()
            } catch (e: Exception) {
                setStatus("Gagal impor: ${e.message ?: "kesalahan tidak dikenal"}")
            }
        }
    }

    private fun requestConsent() {
        if (!confFile.exists()) {
            setStatus(getString(R.string.need_conf))
            return
        }
        val prepare = TunnelManager.prepareIntent(this)
        if (prepare != null) vpnConsent.launch(prepare) else connectNow()
    }

    private fun connectNow() {
        lifecycleScope.launch {
            setStatus(getString(R.string.connecting))
            val text = withContext(Dispatchers.IO) {
                SecureStore.load(confFile)?.toString(Charsets.UTF_8)
            }
            if (text.isNullOrBlank()) {
                setStatus(getString(R.string.need_conf))
                return@launch
            }
            try {
                val config = withContext(Dispatchers.IO) {
                    WgConfigLoader.loadAppOnly(text, packageName)
                }
                withContext(Dispatchers.IO) { TunnelManager.connect(this@MainActivity, config) }
            } catch (e: Exception) {
                setStatus("Gagal menyambung: ${e.message ?: "periksa konfigurasi"}")
            }
        }
    }

    private fun exitApp() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { TunnelManager.disconnect() }
            TunnelFgs.stop(this@MainActivity)
            finishAndRemoveTask()
        }
    }

    // ------------------------------------------------------------- WebView

    private fun allowed(uri: Uri?): Boolean {
        if (uri == null) return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == BuildConfig.SITE_HOST || host.endsWith(".${BuildConfig.SITE_HOST}")
    }

    private inner class LockedClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            if (!tunnelUp) return true
            val ok = allowed(request?.url)
            if (!ok) setStatus(getString(R.string.blocked_link))
            return !ok
        }

        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val uri = request?.url
            if (!tunnelUp && uri != null && (uri.scheme == "http" || uri.scheme == "https")) {
                // Blokir total saat tunnel mati: tidak ada satu byte pun keluar lewat IP asli.
                return WebResourceResponse(
                    "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                )
            }
            return super.shouldInterceptRequest(view, request)
        }
    }

    private inner class FullscreenChrome : WebChromeClient() {

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (view == null || callback == null) return
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            fullscreenLayer.addView(view, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fullscreenLayer.visibility = View.VISIBLE
        }

        override fun onHideCustomView() = hideFullscreen()

        override fun onPermissionRequest(request: PermissionRequest?) {
            request?.deny()
        }
    }

    private fun hideFullscreen() {
        fullscreenLayer.removeAllViews()
        fullscreenLayer.visibility = View.GONE
        customView = null
    }

    // -------------------------------------------------------------- lain

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }
}
