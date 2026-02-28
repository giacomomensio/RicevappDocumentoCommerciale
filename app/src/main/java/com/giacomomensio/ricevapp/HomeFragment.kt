package com.giacomomensio.ricevapp

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

class HomeFragment : Fragment() {

    lateinit var webView: WebView
    private lateinit var saveCredentialsCheckbox: CheckBox
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var fastTrackBannerContainer: View
    private lateinit var fastTrackButton: Button
    private lateinit var skipIntermediatePageCheckbox: CheckBox

    private var justLoggedIn = false
    private var isInitialPageLoad = true
    private var isAuthenticated = false

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

    fun canWebViewGoBack(): Boolean {
        return ::webView.isInitialized && webView.canGoBack()
    }

    fun webViewGoBack() {
        if (::webView.isInitialized) {
            webView.goBack()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Prende l'istanza unica dalla MainActivity
        sharedPreferences = (requireActivity() as MainActivity).sharedPreferences

        webView = view.findViewById(R.id.webview)
        saveCredentialsCheckbox = view.findViewById(R.id.save_credentials_checkbox)
        fastTrackBannerContainer = view.findViewById(R.id.fast_track_banner_container)
        fastTrackButton = view.findViewById(R.id.fast_track_button)
        skipIntermediatePageCheckbox = view.findViewById(R.id.skip_intermediate_page_checkbox)

        setupApp()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            isAuthenticated = savedInstanceState.getBoolean("IS_AUTHENTICATED", false)
        } else {
            webView.visibility = View.INVISIBLE
            saveCredentialsCheckbox.visibility = View.GONE
            authenticateApp()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putBoolean("IS_AUTHENTICATED", isAuthenticated)
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
        val biometricManager = BiometricManager.from(requireContext())
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> showBiometricPrompt()
            else -> {
                isAuthenticated = true
                showLoginInfoPopup()
                startApp()
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(requireContext())
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isAuthenticated = true
                    showLoginInfoPopup()
                    startApp()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(requireContext(), "Autenticazione fallita", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON || errorCode == BiometricPrompt.ERROR_USER_CANCELED) {
                        (requireActivity() as MainActivity).resetToStartTab()
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

        val builder = AlertDialog.Builder(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_login_info, null)
        builder.setView(dialogView)

        val dialog = builder.create()

        val dontShowAgainCheckbox = dialogView.findViewById<CheckBox>(R.id.dont_show_again_checkbox)
        val messageTextView = dialogView.findViewById<TextView>(R.id.login_info_message)
        val okButton = dialogView.findViewById<Button>(R.id.dialog_button_ok)

        messageTextView.text = "Il metodo più rapido per il login è tramite credenziali Fisconline/Entratel: è l'unico che consente di salvare i dati per gli accessi futuri usando la funzione in alto.\n\nL'accesso con SPID o CIE funziona inserendo manualmente le credenziali (i link rapidi alle relative app non sono supportati).\n\nL'accesso con CNS non è stato testato.\n\nNota: L'app è pensata per la velocità. Grazie alla protezione all'avvio, non è necessario fare il logout manuale prima di chiuderla."

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

        saveCredentialsCheckbox.isChecked = sharedPreferences.getBoolean(SHOULD_SAVE_KEY, false)

        saveCredentialsCheckbox.setOnCheckedChangeListener { _, isChecked ->
            with(sharedPreferences.edit()) {
                putBoolean(SHOULD_SAVE_KEY, isChecked)
                if (!isChecked) {
                    remove(USERNAME_KEY)
                    remove(PASSWORD_KEY)
                    remove(PIN_KEY)
                    Toast.makeText(requireContext(), "Salvataggio automatico disattivato", Toast.LENGTH_SHORT).show()
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
                AlertDialog.Builder(requireContext())
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
                    
                    val allowedDomains = listOf(
                        "ivaservizi.agenziaentrate.gov.it",
                        "idserver.servizicie.interno.gov.it",
                        "sogei.it", "lepida.it", "register.it", "namirial.it", 
                        "intesigroup.com", "teamsystem.com", "infocamere.it", 
                        "infocert.it", "poste.it", "aruba.it", "sieltecloud.it", 
                        "eht.eu", "tim.it"
                    )

                    val isAllowed = if (host != null) {
                        allowedDomains.any { domain -> 
                            host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)
                        }
                    } else {
                        false
                    }

                    if (isAllowed) {
                        return false 
                    }
                }
                try {
                    val intent = Intent(Intent.ACTION_VIEW, url)
                    startActivity(intent)
                } catch (e: Exception) {
                }
                return true 
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                fastTrackBannerContainer.visibility = View.GONE
                saveCredentialsCheckbox.visibility = View.GONE

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

                if (url != null && url.startsWith("https://ivaservizi.agenziaentrate.gov.it/")) {
                    isLoginPage { onLoginPage ->
                        if (onLoginPage) {
                            if (isLoginPageUrl) {
                                saveCredentialsCheckbox.visibility = View.VISIBLE
                                val jsToInject = """
                                    (function() {
                                        setTimeout(function() {
                                            var viewport = document.querySelector('meta[name="viewport"]');
                                            var pageContent = document.getElementById('page-content');
                                            if (window.innerWidth < 768) {
                                                if (viewport) { viewport.setAttribute('content', 'width=768'); }
                                                if (pageContent) { pageContent.style.zoom = 1.9; }
                                            } else {
                                                if (viewport) { viewport.setAttribute('content', 'width=device-width, initial-scale=1.0'); }
                                                if (pageContent) { pageContent.style.zoom = 1.0; }
                                            }
                                        }, 300);
                                    })();
                                """
                                view?.evaluateJavascript(jsToInject, null)
                            }
                            autofillCredentials(sharedPreferences)
                        } else {
                            if (isLoginPageUrl) {
                                if (justLoggedIn) {
                                    justLoggedIn = false
                                    val shouldSkipPage = sharedPreferences.getBoolean(SKIP_INTERMEDIATE_PAGE_KEY, false)
                                    if (shouldSkipPage) {
                                        view?.loadUrl(HOME_PAGE_URL)
                                    } else {
                                        fastTrackBannerContainer.visibility = View.VISIBLE
                                    }
                                } else {
                                    fastTrackBannerContainer.visibility = View.VISIBLE
                                }
                            }
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
                                    
                                    document.documentElement.style.overflowX = 'hidden';
                                    document.body.style.overflowX = 'hidden';
                                    document.body.style.width = '100%';
                                    
                                    table.style.marginLeft = '-10px';
                                    table.style.marginRight = '-10px';
                                    const panelBody = table.querySelector('.panel-body');
                                    if (panelBody) {
                                        panelBody.style.paddingLeft = '0px';
                                        panelBody.style.paddingRight = '0px';
                                    }

                                    const td = targetElement.closest('td');
                                    if (td) {
                                        td.classList.add('table-widthed');
                                    }

                                    if (!document.getElementById('wizard2-style-fix')) {
                                        var style = document.createElement('style');
                                        style.id = 'wizard2-style-fix';
                                        style.type = 'text/css';
                                        style.innerHTML = '.table-widthed input[type="text"] { min-width: 60px; }';
                                        document.head.appendChild(style);
                                    }

                                    if (!document.getElementById('wizard2-style-header-fix')) {
                                        var style2 = document.createElement('style');
                                        style2.id = 'wizard2-style-header-fix';
                                        style2.type = 'text/css';
                                        style2.innerHTML = '.table-widthed thead th.txt:nth-child(3) { min-width: 210px; }';
                                        document.head.appendChild(style2);
                                    }

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
            }
        }
    }

    private fun handleDownload(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            downloadFile(url, userAgent, contentDisposition, mimetype)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (requireActivity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                isAwaitingPermissionResult = true
                ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION_CODE)
                downloadUrl = url
                downloadUserAgent = userAgent
                downloadContentDisposition = contentDisposition
                downloadMimetype = mimetype
            } else {
                if (!isAwaitingPermissionResult) {
                    downloadFile(url, userAgent, contentDisposition, mimetype)
                }
            }
        } else {
            downloadFile(url, userAgent, contentDisposition, mimetype)
        }
    }

    private fun downloadFile(url: String, userAgent: String, contentDisposition: String, mimetype: String) {
        val request = DownloadManager.Request(Uri.parse(url))
        val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)

        var finalFilename = filename
        if (finalFilename.endsWith(".bin", ignoreCase = true)) {
            finalFilename = finalFilename.dropLast(4) + ".pdf"
        }
        if (!finalFilename.endsWith(".pdf", ignoreCase = true)) {
            finalFilename += ".pdf"
        }

        request.setMimeType("application/pdf") 
        val cookies = CookieManager.getInstance().getCookie(url)
        request.addRequestHeader("Cookie", cookies)
        request.addRequestHeader("User-Agent", userAgent)
        request.setDescription("Downloading file...")
        request.setTitle(finalFilename)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, finalFilename)
        val downloadManager = requireContext().getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)
        Toast.makeText(requireContext(), "Download in corso...", Toast.LENGTH_LONG).show()
    }

    fun handlePermissionResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == STORAGE_PERMISSION_CODE) {
            isAwaitingPermissionResult = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if(downloadUrl != null) {
                    downloadFile(downloadUrl!!, downloadUserAgent!!, downloadContentDisposition!!, downloadMimetype!!)
                }
            } else {
                Toast.makeText(requireContext(), "Permission denied!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startApp(url: String? = null) {
        webView.visibility = View.VISIBLE
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