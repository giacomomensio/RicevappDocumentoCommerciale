package com.giacomomensio.ricevapp

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.core.app.ActivityCompat
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
    private lateinit var fastTrackBannerContainer: View
    private lateinit var fastTrackButton: Button
    private lateinit var skipIntermediatePageCheckbox: CheckBox

    private var justLoggedIn = false
    private var isInitialPageLoad = true

    private var downloadUrl: String? = null
    private var downloadUserAgent: String? = null
    private var downloadContentDisposition: String? = null
    private var downloadMimetype: String? = null
    private var isAwaitingPermissionResult = false

    private val STORAGE_PERMISSION_CODE = 1000

    private val USERNAME_KEY = "USERNAME_KEY"
    private val PASSWORD_KEY = "PASSWORD_KEY"
    private val PIN_KEY = "PIN_KEY"
    private val SHOULD_SAVE_KEY = "SHOULD_SAVE_KEY"
    private val DISCLAIMER_DISMISSED_KEY = "DISCLAIMER_DISMISSED_KEY"
    private val SKIP_INTERMEDIATE_PAGE_KEY = "SKIP_INTERMEDIATE_PAGE_KEY"
    private val HOME_PAGE_URL = "https://ivaservizi.agenziaentrate.gov.it/ser/documenticommercialionline/#/home"
    private val NONAUTH_URL = "https://ivaservizi.agenziaentrate.gov.it/ser/documenticommercialionline/nonauth.html"
    private val LOGIN_PAGE_URL = "https://ivaservizi.agenziaentrate.gov.it/portale/web/guest/home"
    private val ALT_LOGIN_PAGE_URL = "https://ivaservizi.agenziaentrate.gov.it/portale/home"
    private val ALT_LOGIN_PAGE_URL_2 = "https://ivaservizi.agenziaentrate.gov.it/portale/"
    private val LOGIN_INFO_DISMISSED_KEY = "LOGIN_INFO_DISMISSED_KEY"


    inner class WebAppInterface {
        @JavascriptInterface
        fun onLoginButtonClick(username: String, password: String, pin: String) {
            justLoggedIn = true
            if (saveCredentialsCheckbox.isChecked) {
                with(sharedPreferences.edit()) {
                    putString(USERNAME_KEY, username)
                    putString(PASSWORD_KEY, password)
                    putString(PIN_KEY, pin)
                    apply()
                }
            }
        }

        @JavascriptInterface
        fun onExternalLoginClick() {
            justLoggedIn = true
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
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        webView = findViewById(R.id.webview)
        saveCredentialsCheckbox = findViewById(R.id.save_credentials_checkbox)
        disclaimerContainer = findViewById(R.id.disclaimer_container)
        fastTrackBannerContainer = findViewById(R.id.fast_track_banner_container)
        fastTrackButton = findViewById(R.id.fast_track_button)
        skipIntermediatePageCheckbox = findViewById(R.id.skip_intermediate_page_checkbox)

        setupApp()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            // Normal app start
            webView.visibility = View.INVISIBLE
            saveCredentialsCheckbox.visibility = View.GONE // Changed to GONE to avoid reserving space
            disclaimerContainer.visibility = View.GONE // Changed to GONE to avoid reserving space
            authenticateApp()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // Update the activity's intent
        // No longer handling ACTION_VIEW intents
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val url = webView.url
        val isLoginPageUrl = url == LOGIN_PAGE_URL || (url != null && url.startsWith(ALT_LOGIN_PAGE_URL)) || url == ALT_LOGIN_PAGE_URL_2

        val js = if (isLoginPageUrl) {
            """
            (function() {
                setTimeout(function() {
                    var viewport = document.querySelector('meta[name="viewport"]');
                    var pageContent = document.getElementById('page-content');
                    if (window.innerWidth < 768) {
                        if (viewport) {
                            viewport.setAttribute('content', 'width=768');
                        }
                        if (pageContent) {
                            pageContent.style.zoom = 1.9;
                        }
                    } else {
                        if (viewport) {
                            viewport.setAttribute('content', 'width=device-width, initial-scale=1.0');
                        }
                        if (pageContent) {
                            pageContent.style.zoom = 1.0;
                        }
                    }
                }, 300);
            })();
            """
        } else {
            """
            (function() {
                var viewport = document.querySelector('meta[name="viewport"]');
                if (viewport) {
                    viewport.setAttribute('content', 'width=device-width, initial-scale=1.0');
                }
            })();
            """
        }
        webView.evaluateJavascript(js, null)
    }

    private fun authenticateApp() {
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
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
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
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

        messageTextView.text = "Il metodo più rapido per il login è tramite credenziali Fisconline/Entratel: è l'unico che consente di salvare i dati per gli accessi futuri usando la funzione in alto.\n\nL'accesso con SPID o CIE funziona inserendo manualmente le credenziali (i link rapidi alle relative app non sono supportati).\n\nL'accesso con CNS non è stato testato.\n\nNota: L'app è pensata per la velocità. Grazie alla protezione all\'avvio, non è necessario fare il logout manuale prima di chiuderla."

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

        skipIntermediatePageCheckbox.isChecked = sharedPreferences.getBoolean(SKIP_INTERMEDIATE_PAGE_KEY, false)
        skipIntermediatePageCheckbox.setOnCheckedChangeListener { _, isChecked ->
            with(sharedPreferences.edit()) {
                putBoolean(SKIP_INTERMEDIATE_PAGE_KEY, isChecked)
                apply()
            }
        }

        fastTrackButton.setOnClickListener {
            webView.loadUrl(HOME_PAGE_URL)
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
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
                        sharedPreferences.edit().putBoolean(SKIP_INTERMEDIATE_PAGE_KEY, false).apply()
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
                    
                    // List of allowed domains
                    val allowedDomains = listOf(
                        "ivaservizi.agenziaentrate.gov.it",
                        "idserver.servizicie.interno.gov.it",
                        "sogei.it", "lepida.it", "register.it", "namirial.it", 
                        "intesigroup.com", "teamsystem.com", "infocamere.it", 
                        "infocert.it", "poste.it", "aruba.it", "sieltecloud.it", 
                        "eht.eu", "tim.it"
                    )

                    // Check if the host ends with any of the allowed domains
                    val isAllowed = if (host != null) {
                        allowedDomains.any { domain -> 
                            host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)
                        }
                    } else {
                        false
                    }

                    if (isAllowed) {
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
                fastTrackBannerContainer.visibility = View.GONE

                if (isInitialPageLoad) {
                    isInitialPageLoad = false
                    val isPortalPage = url == LOGIN_PAGE_URL || url == ALT_LOGIN_PAGE_URL || url == ALT_LOGIN_PAGE_URL_2
                    if (isPortalPage) {
                        isLoginPage { onLoginPage ->
                            if (!onLoginPage) {
                                view?.loadUrl(HOME_PAGE_URL)
                            }
                        }
                    }
                }

                val isLoginPageUrl = url == LOGIN_PAGE_URL || (url != null && url.startsWith(ALT_LOGIN_PAGE_URL)) || url == ALT_LOGIN_PAGE_URL_2

                if (isLoginPageUrl) {
                    isLoginPage { onLoginPage ->
                        if (onLoginPage) {
                            saveCredentialsCheckbox.visibility = View.VISIBLE
                            val jsToInject = """
                                (function() {
                                    setTimeout(function() {
                                        var viewport = document.querySelector('meta[name="viewport"]');
                                        var pageContent = document.getElementById('page-content');
                                        if (window.innerWidth < 768) {
                                            if (viewport) {
                                                viewport.setAttribute('content', 'width=768');
                                            }
                                            if (pageContent) {
                                                pageContent.style.zoom = 1.9;
                                            }
                                        } else {
                                            if (viewport) {
                                                viewport.setAttribute('content', 'width=device-width, initial-scale=1.0');
                                            }
                                            if (pageContent) {
                                                pageContent.style.zoom = 1.0;
                                            }
                                        }
                                    }, 300);
                                })();
                            """
                            view?.evaluateJavascript(jsToInject, null)
                        } else {
                            // This is the intermediate page. Show the banner.
                            saveCredentialsCheckbox.visibility = View.GONE
                            fastTrackBannerContainer.visibility = View.VISIBLE
                        }
                    }
                }

                if (url != null && url.contains("/generazione/wizard2")) {
                    val wizard2Js = """
                        (function() {
                            const interval = setInterval(function() {
                                const table = document.getElementById('table');
                                const targetElement = document.getElementById('i2_2_1_r0');
                                
                                if (table && targetElement) {
                                    clearInterval(interval);
                                    
                                    // Existing working styles
                                    table.style.marginLeft = '-10px';
                                    table.style.marginRight = '-10px';
                                    const panelBody = table.querySelector('.panel-body');
                                    if (panelBody) {
                                        panelBody.style.paddingLeft = '0px';
                                        panelBody.style.paddingRight = '0px';
                                    }

                                    // Add class to parent TD for styling
                                    const td = targetElement.closest('td');
                                    if (td) {
                                        td.classList.add('table-widthed');
                                    }

                                    // Inject the CSS rule if it doesn't exist
                                    if (!document.getElementById('wizard2-style-fix')) {
                                        var style = document.createElement('style');
                                        style.id = 'wizard2-style-fix';
                                        style.type = 'text/css';
                                        style.innerHTML = '.table-widthed input[type="text"] { min-width: 60px; }';
                                        document.head.appendChild(style);
                                    }

                                    // Inject the new CSS rule for the table header
                                    if (!document.getElementById('wizard2-style-header-fix')) {
                                        var style2 = document.createElement('style');
                                        style2.id = 'wizard2-style-header-fix';
                                        style2.type = 'text/css';
                                        style2.innerHTML = '.table-widthed thead th.txt:nth-child(3) { min-width: 210px; }';
                                        document.head.appendChild(style2);
                                    }

                                    // Inject the new CSS rule for the table header
                                    if (!document.getElementById('wizard2-style-header-fix-2')) {
                                        var style3 = document.createElement('style');
                                        style3.id = 'wizard2-style-header-fix-2';
                                        style3.type = 'text/css';
                                        style3.innerHTML = '.table-widthed thead th.val:nth-child(4) { padding-left: 0px; padding-right: 0px; }';
                                        document.head.appendChild(style3);
                                    }
                                }
                            }, 100);
                        })();
                    """
                    view?.evaluateJavascript(wizard2Js, null)
                }

                if (isLoginPageUrl) {
                    val jsLoginButtonListener = """ 
                        (function() { 
                            let doc = document; 
                            const iframe = doc.getElementsByTagName('iframe')[0]; 
                            if (iframe) { 
                                try { doc = iframe.contentDocument; } catch(e) { return; } 
                            } 
                            
                            if (doc.ricevappListenerAttached) return;
                            doc.ricevappListenerAttached = true;
                            
                            doc.addEventListener('click', function(e) { 
                                let target = e.target;
                                while (target && target !== doc) {
                                    if (target.id === 'login-button') {
                                        const uField = doc.getElementById('username'); 
                                        const pField = doc.getElementById('password'); 
                                        const pinField = doc.getElementById('pin'); 
                                        if (uField && pField && pinField) { 
                                            Android.onLoginButtonClick(uField.value, pField.value, pinField.value);
                                        } 
                                        break;
                                    }
                                    const text = (target.textContent || target.innerText || '').toLowerCase();
                                    if (text.includes('spid') || text.includes('cie')) {
                                        Android.onExternalLoginClick();
                                        break;
                                    }
                                    target = target.parentNode;
                                }
                            }); 
                        })(); 
                    """
                    view?.evaluateJavascript(jsLoginButtonListener, null)
                } else {
                    if (url != null && url.contains("/scelta-utenza-lavoro")) {
                        val jsLogoutHandler = """ 
                        (function() { 
                            if (document.body.hasAttribute('data-logout-listener')) return; 
                            document.body.setAttribute('data-logout-listener', 'true'); 
 
                            const keywords = ['esci', 'logout', 'esci dal servizio']; 
 
                            function isLogoutElement(element) { 
                                if (!element) return false; 
                                const text = (element.textContent || element.innerText || '').trim().toLowerCase(); 
                                const href = (element.href || '').toLowerCase(); 
 
                                if (keywords.some(kw => text.includes(kw))) { 
                                    return true; 
                                } 
                                if (href.includes('logout')) { 
                                    return true; 
                                } 
                                return false; 
                            } 
 
                            document.body.addEventListener('click', function(event) { 
                                let target = event.target; 
                                for (let i = 0; i < 5 && target && target !== document.body; i++, target = target.parentNode) { 
                                    if (isLogoutElement(target)) { 
                                        event.preventDefault(); 
                                        event.stopPropagation(); 
                                        window.confirm('Sei sicuro di voler uscire?'); 
                                        return; 
                                    } 
                                } 
                            }, true); 
                        })(); 
                    """
                        view?.evaluateJavascript(jsLogoutHandler, null)
                    }
                }

                if (url != null && url.startsWith("https://ivaservizi.agenziaentrate.gov.it/")) {
                    isLoginPage { onLoginPage ->
                        if (onLoginPage) {
                            autofillCredentials(sharedPreferences)
                        } else {
                            if (justLoggedIn) {
                                justLoggedIn = false
                                if (url == LOGIN_PAGE_URL || url == ALT_LOGIN_PAGE_URL || url == ALT_LOGIN_PAGE_URL_2) {
                                    val shouldSkipPage = sharedPreferences.getBoolean(SKIP_INTERMEDIATE_PAGE_KEY, false)
                                    if (shouldSkipPage) {
                                        view?.loadUrl(HOME_PAGE_URL)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // For Android 10 (API 29) and above, storage permission is not required for DownloadManager to save in public Downloads.
            downloadFile(url, userAgent, contentDisposition, mimetype)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                isAwaitingPermissionResult = true
                // Permission denied, request it.
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
                // Store download data to use after permission is granted.
                downloadUrl = url
                downloadUserAgent = userAgent
                downloadContentDisposition = contentDisposition
                downloadMimetype = mimetype
            } else {
                // Permission already granted, handle download.
                if (!isAwaitingPermissionResult) {
                    downloadFile(url, userAgent, contentDisposition, mimetype)
                }
            }
        } else {
            // System OS is less than Marshmallow, no runtime permission needed.
            downloadFile(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            isAwaitingPermissionResult = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                //permission granted from popup, handle download
                if(downloadUrl != null) {
                    downloadFile(downloadUrl!!, downloadUserAgent!!, downloadContentDisposition!!, downloadMimetype!!)
                }
            } else {
                //permission denied from popup, show error message
                Toast.makeText(this, "Permission denied!", Toast.LENGTH_SHORT).show()
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
        webView.loadUrl(url ?: LOGIN_PAGE_URL)
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
