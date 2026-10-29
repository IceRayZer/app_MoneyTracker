package com.icerayzer.moneytracker

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.window.OnBackInvokedDispatcher
import org.json.JSONObject

/** Une seule activité : une WebView qui affiche l'interface embarquée dans assets/. Aucun accès réseau. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExport: String? = null

    // Gardé en champ : Android ne garde qu'une référence faible vers l'écouteur.
    private val paymentsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        js("window.onPaymentsChanged && onPaymentsChanged()")
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val root = FrameLayout(this).apply { addView(web) }
        setContentView(root)
        drawBehindSystemBars(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // localStorage : les données de l'app
            allowFileAccess = false // assets/ reste accessible
            allowContentAccess = false
        }
        web.addJavascriptInterface(Bridge(this), "Bridge")
        web.webViewClient = object : WebViewClient() {
            // Aucune navigation hors de la page embarquée.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                return start(intent, REQ_IMPORT) || run { fileCallback = null; false }
            }
        }
        web.loadUrl("file:///android_asset/index.html")

        PaymentStore.prefs(this).registerOnSharedPreferenceChangeListener(paymentsListener)
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
    }

    override fun onDestroy() {
        PaymentStore.prefs(this).unregisterOnSharedPreferenceChangeListener(paymentsListener)
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Remplacé par OnBackInvokedCallback à partir d'Android 13")
    override fun onBackPressed() = back()

    /** L'interface ferme d'abord une fiche ou revient à l'accueil ; sinon on quitte. */
    private fun back() = web.evaluateJavascript("window.onBack ? onBack() : false") { if (it != "true") finish() }

    // Android 15+ impose l'affichage sous les barres système : on l'active partout et on décale le contenu.
    private fun drawBehindSystemBars(root: FrameLayout) {
        if (Build.VERSION.SDK_INT < 30) return
        @Suppress("DEPRECATION") // sans effet à partir d'Android 15, où c'est le comportement imposé
        window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (night) 0 else lightBars, lightBars)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsets.CONSUMED
        }
    }

    fun exportFile(name: String, content: String) {
        pendingExport = content
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(Intent.EXTRA_TITLE, name)
        if (!start(intent, REQ_EXPORT)) pendingExport = null
    }

    fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ex. : loyer 800 sur N26")
        if (!start(intent, REQ_SPEECH)) js("onSpeechError('Reconnaissance vocale indisponible : tape la phrase.')")
    }

    @Deprecated("API Activity sans AndroidX, volontairement")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_IMPORT -> {
                fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
                fileCallback = null
            }
            REQ_EXPORT -> {
                val json = pendingExport
                pendingExport = null
                val uri = data?.data
                if (resultCode != RESULT_OK || uri == null || json == null) return
                runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) } }
                    .onSuccess { js("feedback('Sauvegarde enregistrée')") }
                    .onFailure { js("feedback('Échec de la sauvegarde : réessaie.', true)") }
            }
            REQ_SPEECH -> {
                val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                if (resultCode == RESULT_OK && text != null) js("onSpeech(${JSONObject.quote(text)})")
                else js("onSpeechError('Rien entendu. Réessaie ou tape la phrase.')")
            }
        }
    }

    private fun start(intent: Intent, request: Int): Boolean = try {
        @Suppress("DEPRECATION")
        startActivityForResult(intent, request)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private fun js(code: String) = web.evaluateJavascript(code, null)

    private companion object {
        const val REQ_IMPORT = 1
        const val REQ_EXPORT = 2
        const val REQ_SPEECH = 3
    }
}
