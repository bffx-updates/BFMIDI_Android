package com.bffx.bfmidi

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wrapper WebView fullscreen do editor BFMIDI servido pelo pedal.
 *
 * O pedal serve a UI por HTTP local (sem HTTPS), o que impede a instalacao
 * como PWA no Android. Este app carrega a mesma UI direto num WebView — sem
 * barra de navegador e sem a exigencia de contexto seguro do PWA.
 *
 * Ao abrir, sonda os enderecos conhecidos em ordem (AP, depois mDNS) e
 * carrega o primeiro que responder. Se nenhum responder, mostra uma tela de
 * "tentar de novo".
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var errorView: View
    private lateinit var progressView: View

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    // Enderecos testados em ordem; o primeiro que responder e carregado.
    private val candidates = listOf("http://192.168.4.1", "http://bfmidi.local")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        webView = WebView(this)
        configureWebView()
        root.addView(webView, frame())

        progressView = buildProgress()
        root.addView(progressView, frame())

        errorView = buildError { probeAndLoad() }
        errorView.visibility = View.GONE
        root.addView(errorView, frame())

        setContentView(root)

        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val cb = filePathCallback
            filePathCallback = null
            cb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

        // Botao "voltar" navega no historico do WebView em vez de fechar o app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        probeAndLoad()
        checkForUpdate()
    }

    // ── Atualizador interno ────────────────────────────────────────────────
    // Ao abrir, consulta a release mais recente no GitHub e, se o build for
    // mais novo que o instalado, oferece baixar e instalar o APK. Substitui o
    // fluxo manual (baixar a release na mao) — "rodei o bat -> o app avisa e
    // atualiza". Silencioso quando offline (ex.: conectado no AP do pedal, sem
    // internet): nesse caso so checa quando o celular tiver acesso a internet.
    private val updateApiUrl =
        "https://api.github.com/repos/bffx-updates/BFMIDI_Android/releases/latest"
    private var pendingApkUrl: String? = null

    /** versionCode instalado = numero do build do CI (BF_VERSION_CODE). */
    private fun currentBuildNumber(): Int = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            pi.longVersionCode.toInt()
        else @Suppress("DEPRECATION") pi.versionCode
    } catch (e: Exception) { 0 }

    private fun checkForUpdate() {
        Thread {
            try {
                val conn = (URL(updateApiUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "BFMiDi-Android")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    connectTimeout = 5000
                    readTimeout = 5000
                }
                if (conn.responseCode != 200) { conn.disconnect(); return@Thread }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                val json = JSONObject(body)
                // tag = "build-<n>"; o <n> e o run_number == versionCode do build.
                val tag = json.optString("tag_name")
                val latest = tag.substringAfterLast('-').toIntOrNull() ?: return@Thread
                val assets = json.optJSONArray("assets") ?: return@Thread
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url"); break
                    }
                }
                val url = apkUrl ?: return@Thread
                if (latest > currentBuildNumber()) {
                    runOnUiThread { promptUpdate(latest, url) }
                }
            } catch (e: Exception) {
                // Offline / sem internet (ex.: AP do pedal) -> ignora silenciosamente.
            }
        }.start()
    }

    private fun promptUpdate(buildNum: Int, apkUrl: String) {
        if (isFinishing) return
        AlertDialog.Builder(this)
            .setTitle("Atualização disponível")
            .setMessage("Há uma versão nova do editor (build $buildNum). Atualizar agora?")
            .setPositiveButton("Atualizar") { _, _ -> ensureInstallPermissionThenDownload(apkUrl) }
            .setNegativeButton("Agora não", null)
            .setCancelable(true)
            .show()
    }

    /** Android 8+ exige permissao "instalar apps desconhecidos" por app. */
    private fun ensureInstallPermissionThenDownload(apkUrl: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingApkUrl = apkUrl
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                .setData(Uri.parse("package:$packageName"))
            try { startActivity(intent) }
            catch (e: Exception) {
                try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)) }
                catch (e2: Exception) {
                    Toast.makeText(this, "Habilite 'instalar apps desconhecidos' nas configuracoes.", Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        downloadAndInstall(apkUrl)
    }

    private fun downloadAndInstall(apkUrl: String) {
        Toast.makeText(this, "Baixando atualização…", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "BFMiDi-Android")
                    connectTimeout = 10000
                    readTimeout = 30000
                }
                val file = File(cacheDir, "update.apk")
                conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                runOnUiThread { launchInstaller(file) }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Falha ao baixar a atualização.", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun launchInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao abrir o instalador.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Retoma a instalacao depois que o usuario habilita "apps desconhecidos".
        val url = pendingApkUrl
        if (url != null &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                packageManager.canRequestPackageInstalls())
        ) {
            pendingApkUrl = null
            downloadAndInstall(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // localStorage (tema, idioma, IP fixado)
            databaseEnabled = true
            allowFileAccess = true
            // A UI vive nos assets do APK (file://) e fala com a API HTTP do pedal.
            // Esses flags permitem que a pagina file:// faca fetch cross-origin pro
            // http://<pedal> (deprecados mas necessarios aqui; app controlado, so LAN).
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            // LOAD_NO_CACHE: nunca serve recurso (app.js/app.css) do cache do
            // WebView — sempre le os assets atuais do APK. Junto com o
            // clearCache(true) no probeAndLoad, garante que, apos instalar um
            // APK novo, a UI nao apareca velha por causa de app.js cacheado.
            cacheMode = WebSettings.LOAD_NO_CACHE
            // O editor ja e responsivo — nao forcamos viewport.
        }

        webView.webViewClient = object : WebViewClient() {
            // Mantem toda navegacao dentro do WebView (a UI e single-origin).
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean = false

            override fun onPageFinished(view: WebView, url: String) {
                showWeb()
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, error: WebResourceError
            ) {
                // So tratamos falha do frame principal (recursos isolados nao
                // derrubam a UI inteira).
                if (request.isForMainFrame) showError()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Upload de imagem/icone do editor abre o seletor de arquivos.
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                return try {
                    fileChooserLauncher.launch(params?.createIntent())
                    true
                } catch (e: Exception) {
                    filePathCallback = null
                    false
                }
            }
        }
    }

    /**
     * Carrega a UI LOCAL (assets do APK) e aponta a API pro pedal.
     *
     * A sondagem so decide qual endereco passar no ?api= (AP 192.168.4.1 x mDNS
     * bfmidi.local). Se algum responder, passa ele no ?api=. Se NENHUM responder,
     * carrega SEM ?api= — assim o editor usa o IP que o usuario fixou antes
     * (localStorage) ou cai na sua propria tela de conexao. Nunca atropela o IP
     * salvo quando estamos offline.
     */
    private fun probeAndLoad() {
        showProgress()
        // Limpa o cache de recursos do WebView ANTES de carregar — assim a UI
        // sempre reflete os assets do APK atual (evita app.js/app.css velhos em
        // cache apos atualizar o APK). NAO mexe no localStorage (IP fixado,
        // tema, idioma): clearCache so apaga o cache HTTP/recursos, nao os dados.
        webView.clearCache(true)
        Thread {
            val apiHost = candidates.firstOrNull { reachable(it) }
            runOnUiThread {
                val url = if (apiHost != null)
                    "file:///android_asset/index.html?api=$apiHost"
                else
                    "file:///android_asset/index.html"
                webView.loadUrl(url)
            }
        }.start()
    }

    /** True se o host responder qualquer status HTTP dentro do timeout. */
    private fun reachable(base: String): Boolean = try {
        val c = (URL("$base/").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2500
            readTimeout = 2500
            requestMethod = "GET"
            instanceFollowRedirects = false
        }
        val code = c.responseCode
        c.disconnect()
        code in 100..599
    } catch (e: Exception) {
        false
    }

    // ── Troca de telas (WebView / progresso / erro) ─────────────────────
    private fun showWeb() {
        webView.visibility = View.VISIBLE
        progressView.visibility = View.GONE
        errorView.visibility = View.GONE
    }

    private fun showProgress() {
        progressView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
    }

    private fun showError() {
        errorView.visibility = View.VISIBLE
        progressView.visibility = View.GONE
    }

    // ── Construcao das telas auxiliares (sem XML de layout) ─────────────
    private fun frame() = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    private fun buildProgress(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val accent = Color.parseColor("#ff6a1f")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(48), 0, px(48), 0)
            // Fundo escuro com um leve brilho laranja no topo (gradiente radial).
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#1a130d"), Color.parseColor("#0a0a0c"))
            ).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = px(520).toFloat()
                setGradientCenter(0.5f, 0.32f)
            }
        }

        // Logo do BFMIDI.
        box.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
        }, LinearLayout.LayoutParams(px(132), px(132)))

        // Titulo da marca / status principal.
        box.addView(TextView(this).apply {
            text = getString(R.string.connecting)
            setTextColor(accent)
            textSize = 23f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            letterSpacing = 0.03f
            gravity = Gravity.CENTER
            setPadding(0, px(30), 0, 0)
        })

        // Dica secundaria.
        box.addView(TextView(this).apply {
            text = getString(R.string.connecting_hint)
            setTextColor(Color.parseColor("#8a8a92"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, px(10), 0, px(30))
        })

        // Spinner laranja (movimento).
        box.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accent)
        }, LinearLayout.LayoutParams(px(34), px(34)))

        return box
    }

    private fun buildError(onRetry: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0a0a0c"))
            setPadding(64, 0, 64, 0)
        }
        box.addView(TextView(this).apply {
            text = getString(R.string.err_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = getString(R.string.err_body)
            setTextColor(Color.parseColor("#9a9aa2"))
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 36)
        })
        box.addView(Button(this).apply {
            text = getString(R.string.err_retry)
            setOnClickListener { onRetry() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        return box
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
