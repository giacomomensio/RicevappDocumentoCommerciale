package com.giacomomensio.ricevapp

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.URLUtil
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import android.content.res.Configuration
import android.view.LayoutInflater
import android.widget.TextView
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var saveCredentialsCheckbox: CheckBox
    private lateinit var disclaimerContainer: View
    private lateinit var sharedPreferences: SharedPreferences

    private var justLoggedIn = false

    private val USERNAME_KEY = "USERNAME_KEY"
    private val PASSWORD_KEY = "PASSWORD_KEY"
    private val PIN_KEY = "PIN_KEY"
    private val SHOULD_SAVE_KEY = "SHOULD_SAVE_KEY"
    private val DISCLAIMER_DISMISSED_KEY = "DISCLAIMER_DISMISSED_KEY"
    private val HOME_PAGE_URL = "https://ivaservizi.agenziaentrate.gov.it/ser/documenticommercialionline/#/home"
    private val NONAUTH_URL = "https://ivaservizi.agenziaentrate.gov.it/ser/documenticommercialionline/nonauth.html"
    private val LOGIN_PAGE_URL = "https://ivaservizi.agenziaentrate.gov.it/portale/web/guest/home"
    private val ALT_LOGIN_PAGE_URL = "https://ivaservizi.agenziaentrate.gov.it/portale/home"
    private val LOGIN_INFO_DISMISSED_KEY = "LOGIN_INFO_DISMISSED_KEY"


    inner class WebAppInterface {
        @JavascriptInterface
        fun onLoginButtonClick(username: String, password: String, pin: String) {
            if (saveCredentialsCheckbox.isChecked) {
                with(sharedPreferences.edit()) {
                    putString(USERNAME_KEY, username)
                    putString(PASSWORD_KEY, password)
                    putString(PIN_KEY, pin)
                    apply()
                }
            }
        }
    }

    private val onBackPressedCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            webView.goBack()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        webView = findViewById(R.id.webview)
        saveCredentialsCheckbox = findViewById(R.id.save_credentials_checkbox)
        disclaimerContainer = findViewById(R.id.disclaimer_container)

        setupApp()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            val intent = intent
            if (intent != null && intent.action == Intent.ACTION_VIEW && intent.data != null) {
                // App is launched from a link
                startApp(intent.dataString)
            } else {
                // Normal app start
                webView.visibility = View.INVISIBLE
                saveCredentialsCheckbox.visibility = View.GONE // Changed to GONE to avoid reserving space
                disclaimerContainer.visibility = View.GONE // Changed to GONE to avoid reserving space
                authenticateApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        if (intent != null && intent.action == Intent.ACTION_VIEW && intent.data != null) {
            intent.dataString?.let {
                webView.loadUrl(it)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val url = webView.url
        val isLoginPageUrl = url == LOGIN_PAGE_URL || url == ALT_LOGIN_PAGE_URL

        val js = if (isLoginPageUrl) {
            """
            (function() {
                var viewport = document.querySelector('meta[name="viewport"]');
                if (viewport) {
                    viewport.setAttribute('content', 'width=device-width, initial-scale=1.0');
                }
            })();
            """
        } else {
            """
            (function() {
                var viewport = document.querySelector('meta[name="viewport"]');
                if (viewport) {
                    viewport.setAttribute('content', 'width=device-width, initial-scale=1.0');
                    setTimeout(function() {
                        if (window.innerWidth < 600) {
                            viewport.setAttribute('content', 'width=600');
                        }
                    }, 300);
                }
            })();
            """
        }
        webView.evaluateJavascript(js, null)
    }

    private fun authenticateApp() {
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> showBiometricPrompt()
            else -> {
                showLoginInfoPopup()
                startApp()
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    showLoginInfoPopup()
                    startApp()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Autenticazione fallita", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        finish()
                    }
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Autenticazione richiesta")
            .setSubtitle("Sblocca per accedere all'app")
            .setNegativeButtonText("Annulla")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun showLoginInfoPopup() {
        if (sharedPreferences.getBoolean(LOGIN_INFO_DISMISSED_KEY, false)) {
            return
        }

        val builder = AlertDialog.Builder(this)
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_login_info, null)
        builder.setView(dialogView)

        val dialog = builder.create()

        val dontShowAgainCheckbox = dialogView.findViewById<CheckBox>(R.id.dont_show_again_checkbox)
        val messageTextView = dialogView.findViewById<TextView>(R.id.login_info_message)
        val okButton = dialogView.findViewById<Button>(R.id.dialog_button_ok)

        messageTextView.text = "Il login è possibile solo con credenziali Fisconline/Entratel, con SPID sto indagando se sarà possibile, per il momento non funziona.\n\nCon CIE non è possibile essendo un'app non ufficiale e con CNS non ho modo di provare non avendola.\n\nSi possono salvare localmente le credenziali Fisconline/Entratel per un accesso più rapido tramite l'apposita funzione che si trova in alto sulla pagina di Login."

        okButton.setOnClickListener {
            if (dontShowAgainCheckbox.isChecked) {
                sharedPreferences.edit().putBoolean(LOGIN_INFO_DISMISSED_KEY, true).apply()
            }
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        dialog.show()
    }

    private fun setupApp() {
        webView.visibility = View.VISIBLE

        CookieManager.getInstance().setAcceptCookie(true)

        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        sharedPreferences = EncryptedSharedPreferences.create(
            "secret_shared_prefs",
            masterKeyAlias,
            applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        if (!sharedPreferences.getBoolean(DISCLAIMER_DISMISSED_KEY, false)) {
            disclaimerContainer.visibility = View.VISIBLE
            val disclaimerButton: Button = findViewById(R.id.disclaimer_button)
            disclaimerButton.setOnClickListener {
                disclaimerContainer.visibility = View.GONE
                with(sharedPreferences.edit()) {
                    putBoolean(DISCLAIMER_DISMISSED_KEY, true)
                    apply()
                }
            }
        } else {
            disclaimerContainer.visibility = View.GONE
        }

        saveCredentialsCheckbox.isChecked = sharedPreferences.getBoolean(SHOULD_SAVE_KEY, false)

        saveCredentialsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            with(sharedPreferences.edit()) {
                putBoolean(SHOULD_SAVE_KEY, isChecked)
                if (!isChecked) {
                    remove(USERNAME_KEY)
                    remove(PASSWORD_KEY)
                    remove(PIN_KEY)
                    Toast.makeText(this@MainActivity, "Salvataggio automatico disattivato", Toast.LENGTH_SHORT).show()
                }
                apply()
            }
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            val request = DownloadManager.Request(Uri.parse(url))
            val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
            
            // Improved logic to fix .bin extension
            var finalFilename = filename
            if (finalFilename.endsWith(".bin", ignoreCase = true)) {
               finalFilename = finalFilename.dropLast(4) + ".pdf"
            }
            if (!finalFilename.endsWith(".pdf", ignoreCase = true)) {
               finalFilename += ".pdf"
            }

            request.setMimeType("application/pdf") // Force PDF mime type
            val cookies = CookieManager.getInstance().getCookie(url)
            request.addRequestHeader("Cookie", cookies)
            request.addRequestHeader("User-Agent", userAgent)
            request.setDescription("Downloading file...")
            request.setTitle(finalFilename)
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, finalFilename)
            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(applicationContext, "Download in corso...", Toast.LENGTH_LONG).show()
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Conferma Uscita")
                    .setMessage(message)
                    .setPositiveButton("Sì") { _, _ ->
                        result?.confirm()
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        view?.loadUrl("https://ivaservizi.agenziaentrate.gov.it/portale/logout")
                    }
                    .setNegativeButton("No") { _, _ -> result?.cancel() }
                    .create()
                    .show()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url
                if (url != null) {
                    val host = url.host
                    if ("ivaservizi.agenziaentrate.gov.it" == host || "idserver.servizicie.interno.gov.it" == host) {
                        return false // Don't override, let WebView load
                    }
                }
                // For any other host, or if url is null, try to launch an external app
                try {
                    val intent = Intent(Intent.ACTION_VIEW, url)
                    startActivity(intent)
                } catch (e: Exception) {
                    // Could not handle the url
                }
                return true // Prevent WebView from loading the URL
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onBackPressedCallback.isEnabled = view?.canGoBack() ?: false
                if (url == NONAUTH_URL) {
                    view?.loadUrl(LOGIN_PAGE_URL)
                    return
                }

                val isLoginPageUrl = url == LOGIN_PAGE_URL || url == ALT_LOGIN_PAGE_URL

                if (!isLoginPageUrl) {
                    view?.evaluateJavascript("""if (window.innerWidth < 600) { document.querySelector('meta[name="viewport"]').setAttribute('content', 'width=600'); }""", null)
                }

                if (isLoginPageUrl) {
                    saveCredentialsCheckbox.visibility = View.VISIBLE
                    val jsLoginButtonListener = """ 
                        (function() { 
                            let doc = document; 
                            const iframe = doc.getElementsByTagName('iframe')[0]; 
                            if (iframe) { 
                                try { doc = iframe.contentDocument; } catch(e) { return; } 
                            } 
                            const loginButton = doc.getElementById('login-button'); 
                            if (loginButton) { 
                                loginButton.addEventListener('click', function() { 
                                    const uField = doc.getElementById('username'); 
                                    const pField = doc.getElementById('password'); 
                                    const pinField = doc.getElementById('pin'); 
                                    if (uField && pField && pinField) { 
                                        Android.onLoginButtonClick(uField.value, pField.value, pinField.value);
                                    } 
                                }); 
                            } 
                        })(); 
                    """
                    view?.evaluateJavascript(jsLoginButtonListener, null)
                } else {
                    saveCredentialsCheckbox.visibility = View.GONE
                }

                if (url != null && url.startsWith("https://ivaservizi.agenziaentrate.gov.it/")) {
                    isLoginPage { onLoginPage ->
                        if (onLoginPage) {
                            justLoggedIn = true
                            autofillCredentials(sharedPreferences)
                        } else {
                            if (justLoggedIn) {
                                justLoggedIn = false
                                if (url != HOME_PAGE_URL) {
                                    view?.loadUrl(HOME_PAGE_URL)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startApp(url: String? = null) {
        webView.visibility = View.VISIBLE
        if (!sharedPreferences.getBoolean(DISCLAIMER_DISMISSED_KEY, false)) {
            disclaimerContainer.visibility = View.VISIBLE
        } else {
            disclaimerContainer.visibility = View.GONE
        }
        webView.loadUrl(url ?: HOME_PAGE_URL)
    }

    override fun onStop() {
        super.onStop()
        CookieManager.getInstance().flush()
    }

    private fun isLoginPage(callback: (Boolean) -> Unit) {
        val jsCheckLogin = """ 
            (function() { 
                let doc = document; 
                let uField = doc.getElementById('username'); 
                 if (!uField) { 
                    const iframe = document.getElementsByTagName('iframe')[0]; 
                    if (iframe) { 
                         try { doc = iframe.contentDocument; } catch(e) { return false; } 
                    } 
                } 
                return !!doc.getElementById('username'); 
            })(); 
        """
        webView.evaluateJavascript(jsCheckLogin) { result ->
            callback(result == "true")
        }
    }

    private fun autofillCredentials(sharedPreferences: SharedPreferences) {
        val savedUsername = sharedPreferences.getString(USERNAME_KEY, "") ?: ""
        val savedPassword = sharedPreferences.getString(PASSWORD_KEY, "") ?: ""
        val savedPin = sharedPreferences.getString(PIN_KEY, "") ?: ""

        if (savedUsername.isNotBlank() && savedPassword.isNotBlank() && savedPin.isNotBlank()) {
            val jsSetCredentials = """ 
                (function() { 
                    let doc = document; 
                    let uField = doc.getElementById('username'); 
                    if (!uField) {
                        const iframe = document.getElementsByTagName('iframe')[0]; 
                        if (iframe) { doc = iframe.contentDocument; } else { return; } 
                    } 
                    if(doc.getElementById('username')) { doc.getElementById('username').value = '$savedUsername'; } 
                    if(doc.getElementById('password')) { doc.getElementById('password').value = '$savedPassword'; } 
                    if(doc.getElementById('pin')) { doc.getElementById('pin').value = '$savedPin'; } 
                })(); 
            """
            webView.post { webView.evaluateJavascript(jsSetCredentials, null) }
        }
    }
}
