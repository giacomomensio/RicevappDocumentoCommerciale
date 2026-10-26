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
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HomeFragment : Fragment() {

    lateinit var webView: WebView
    private lateinit var saveCredentialsCheckbox: CheckBox
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var fastTrackBannerContainer: View
    private lateinit var fastTrackButton: Button

    private var justLoggedIn = false
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
    private val HOME_PAGE_URL = "https://ivaservizi.agenziaentrate.gov.it/ser/documenticommercialionline"
    private val LOGIN_PAGE_URL = "https://iampe.agenziaentrate.gov.it/sam/UI/Login?realm=/agenziaentrate&goto=https%3A%2F%2Fportale.agenziaentrate.gov.it%3A443%2FPortaleWeb%2Fhome%3Fto%3DFATBTB"
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

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            syncSettings()
        }
    }

    override fun onResume() {
        super.onResume()
        syncSettings()
    }

    private fun syncSettings() {
        if (!::sharedPreferences.isInitialized) return
        
        val shouldSave = sharedPreferences.getBoolean(SHOULD_SAVE_KEY, false)
        if (saveCredentialsCheckbox.isChecked != shouldSave) {
            saveCredentialsCheckbox.isChecked = shouldSave
        }

        // Se il salvataggio è stato disattivato, puliamo i campi della WebView se siamo in pagina login
        if (!shouldSave) {
            clearWebViewCredentials()
        }
    }

    private fun clearWebViewCredentials() {
        val jsClear = """ 
            (function() { 
                let doc = document; 
                let clearInDoc = function(d) {
                    if (!d) return;
                    let u = d.getElementById('username-fo-ent') || d.getElementById('username') || d.querySelector("input[name='IDToken1']") || d.getElementById('username-sister');
                    let p = d.getElementById('password-fo-ent-1') || d.getElementById('password') || d.querySelector("input[name='IDToken2']") || d.getElementById('password-fo-sist');
                    let pin = d.getElementById('pin-fo-ent') || d.getElementById('pin') || d.querySelector("input[name='IDToken3']");
                    if (u) { u.value = ''; u.removeAttribute('list'); }
                    if (p) { p.value = ''; p.removeAttribute('list'); }
                    if (pin) { pin.value = ''; pin.removeAttribute('list'); }

                    ['ricevapp-u-list', 'ricevapp-p-list', 'ricevapp-pin-list'].forEach(function(id) {
                        let dl = d.getElementById(id);
                        if (dl && dl.parentNode) dl.parentNode.removeChild(dl);
                    });
                };
                clearInDoc(doc);
                const iframe = doc.getElementsByTagName('iframe')[0]; 
                if (iframe) { 
                    try { clearInDoc(iframe.contentDocument); } catch(e) {} 
                } 
            })(); 
        """
        webView.evaluateJavascript(jsClear, null)
    }

    private fun isOfficialFisconlineLoginPage(url: String?): Boolean {
        if (url == null) return false
        if (url.contains("sp.agenziaentrate.gov.it") || 
            url.contains("servizicie.interno.gov.it") ||
            url.contains("spid") ||
            url.contains("poste") ||
            url.contains("aruba") ||
            url.contains("namirial") ||
            url.contains("sielte") ||
            url.contains("lepida") ||
            url.contains("infocert") ||
            url.contains("tim.it")) {
            return false
        }
        return url == LOGIN_PAGE_URL || url.contains("iampe.agenziaentrate.gov.it") || url.startsWith(ALT_LOGIN_PAGE_URL) || url == ALT_LOGIN_PAGE_URL_2
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

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        saveCredentialsCheckbox.isChecked = sharedPreferences.getBoolean(SHOULD_SAVE_KEY, false)

        saveCredentialsCheckbox.setOnClickListener {
            val isChecked = (it as CheckBox).isChecked
            if (!isChecked) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Disattiva Salvataggio")
                    .setMessage("Sei sicuro di voler disattivare il salvataggio automatico? Le credenziali memorizzate verranno rimosse.")
                    .setPositiveButton("Disattiva") { _, _ ->
                        with(sharedPreferences.edit()) {
                            putBoolean(SHOULD_SAVE_KEY, false)
                            remove(USERNAME_KEY)
                            remove(PASSWORD_KEY)
                            remove(PIN_KEY)
                            apply()
                        }
                        clearWebViewCredentials()
                        Toast.makeText(requireContext(), "Salvataggio automatico disattivato", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Annulla") { _, _ ->
                        it.isChecked = true
                    }
                    .setCancelable(false)
                    .show()
            } else {
                sharedPreferences.edit().putBoolean(SHOULD_SAVE_KEY, true).apply()
            }
        }

        fastTrackButton.setOnClickListener {
            webView.loadUrl(HOME_PAGE_URL)
        }

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.loadWithOverviewMode = true
        webView.settings.useWideViewPort = true
        webView.addJavascriptInterface(WebAppInterface(), "Android")

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            handleDownload(url, userAgent, contentDisposition, mimetype)
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Conferma Uscita")
                    .setMessage(message)
                    .setPositiveButton("Sì") { _, _ ->
                        result?.confirm()
                        CookieManager.getInstance().removeAllCookies(null)
                        CookieManager.getInstance().flush()
                        view?.loadUrl("https://ivaservizi.agenziaentrate.gov.it/ser/documenticommercialionline/nonauth.html")
                    }
                    .setNegativeButton("No") { _, _ -> result?.cancel() }
                    .setCancelable(false)
                    .create()
                    .show()
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url
                if (url != null) {
                    val scheme = url.scheme
                    if (scheme != "http" && scheme != "https") {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, url)
                            startActivity(intent)
                        } catch (e: Exception) {
                        }
                        return true
                    }

                    val host = url.host
                    
                    val allowedDomains = listOf(
                        "agenziaentrate.gov.it",
                        "servizicie.interno.gov.it",
                        "spid.gov.it",
                        "sogei.it", "lepida.it", "register.it", "namirial.it", 
                        "intesigroup.com", "teamsystem.com", "infocamere.it", 
                        "infocert.it", "poste.it", "aruba.it", "sielte.it", "sieltecloud.it", 
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

                if (url == null) return

                val isLoginPageUrl = url == LOGIN_PAGE_URL ||
                        url.contains("iampe.agenziaentrate.gov.it") ||
                        url.startsWith(ALT_LOGIN_PAGE_URL) ||
                        url == ALT_LOGIN_PAGE_URL_2

                val isHomePage = url == HOME_PAGE_URL || url.startsWith(HOME_PAGE_URL) || url.contains("/documenticommercialionline")

                val isAnagrafeAdELogin = isOfficialFisconlineLoginPage(url)

                if (isHomePage) {
                    fastTrackBannerContainer.visibility = View.GONE
                    saveCredentialsCheckbox.visibility = View.GONE
                } else {
                    isLoginPage { onLoginPage ->
                        val isLogin = isLoginPageUrl || onLoginPage
                        if (isLogin) {
                            fastTrackBannerContainer.visibility = View.GONE
                            saveCredentialsCheckbox.visibility = if (isAnagrafeAdELogin) View.VISIBLE else View.GONE

                            if (isAnagrafeAdELogin) {
                                autofillCredentials(sharedPreferences)
                            }
                        } else {
                            val isOfficialAdEPage = url.contains("agenziaentrate.gov.it") &&
                                    !url.contains("sp.agenziaentrate.gov.it") &&
                                    !url.contains("servizicie.interno.gov.it") &&
                                    !url.contains("spid") &&
                                    !url.contains("poste") &&
                                    !url.contains("aruba") &&
                                    !url.contains("namirial") &&
                                    !url.contains("sielte") &&
                                    !url.contains("lepida") &&
                                    !url.contains("infocert") &&
                                    !url.contains("tim.it")

                            fastTrackBannerContainer.visibility = if (isOfficialAdEPage) View.VISIBLE else View.GONE
                            saveCredentialsCheckbox.visibility = View.GONE
                        }
                    }
                }

                if (url.contains("/generazione/wizard2")) {
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

                if (isAnagrafeAdELogin) {
                    val jsLoginButtonListener = """ 
                        (function() { 
                            let attachSaveListener = function(doc) {
                                if (!doc || doc.ricevappSaveListenerAttached) return;
                                doc.ricevappSaveListenerAttached = true;
                                
                                doc.addEventListener('click', function(e) { 
                                    try {
                                        let target = e.target;
                                        if (!target) return;
                                        let elem = (target.nodeType === 3) ? target.parentElement : target;
                                        if (!elem) return;

                                        let btn = elem.closest ? elem.closest("#login-button, button[type='submit'], input[type='submit'], .btn-primary") : null;
                                        if (btn) {
                                            let uField = doc.getElementById('username-fo-ent') || doc.getElementById('username') || doc.querySelector("input[name='IDToken1']") || doc.getElementById('username-sister');
                                            let pField = doc.getElementById('password-fo-ent-1') || doc.getElementById('password') || doc.querySelector("input[name='IDToken2']") || doc.getElementById('password-fo-sist');
                                            let pinField = doc.getElementById('pin-fo-ent') || doc.getElementById('pin') || doc.querySelector("input[name='IDToken3']");
                                            if (uField && pField) { 
                                                let uVal = uField.value;
                                                let pVal = pField.value;
                                                let pinVal = pinField ? pinField.value : '';
                                                if (uVal && pVal) {
                                                    Android.onLoginButtonClick(uVal, pVal, pinVal);
                                                }
                                            } 
                                        }
                                    } catch(err) {}
                                }); 
                            };

                            attachSaveListener(document);
                            const iframe = document.getElementsByTagName('iframe')[0];
                            if (iframe) {
                                try { attachSaveListener(iframe.contentDocument); } catch(e) {}
                            }
                        })(); 
                    """
                    view?.evaluateJavascript(jsLoginButtonListener, null)
                }

                if (url.contains("/scelta-utenza-lavoro")) {
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
                let findU = function(d) {
                    return d.getElementById('username-fo-ent') || d.getElementById('username') || d.querySelector("input[name='IDToken1']") || d.getElementById('username-sister') || d.querySelector("a[href='#tab-4']");
                };
                let uField = findU(doc);
                if (!uField) { 
                    const iframe = doc.getElementsByTagName('iframe')[0]; 
                    if (iframe) { 
                        try { doc = iframe.contentDocument; } catch(e) { return false; }
                        if (doc) uField = findU(doc);
                    } 
                } 
                return !!uField; 
            })();
        """
        webView.evaluateJavascript(jsCheckLogin) { result ->
            callback(result == "true")
        }
    }

    private fun autofillCredentials(sharedPreferences: SharedPreferences) {
        val shouldSave = sharedPreferences.getBoolean(SHOULD_SAVE_KEY, false)
        if (!shouldSave) {
            clearWebViewCredentials()
            return
        }

        val savedUsername = sharedPreferences.getString(USERNAME_KEY, "") ?: ""
        val savedPassword = sharedPreferences.getString(PASSWORD_KEY, "") ?: ""
        val savedPin = sharedPreferences.getString(PIN_KEY, "") ?: ""

        if (savedUsername.isNotBlank() && savedPassword.isNotBlank() && savedPin.isNotBlank()) {
            val jsonUsername = org.json.JSONObject.quote(savedUsername)
            val jsonPassword = org.json.JSONObject.quote(savedPassword)
            val jsonPin = org.json.JSONObject.quote(savedPin)

            val jsSetCredentials = """ 
                (function() { 
                    let setupTab4Fill = function(doc) {
                        if (!doc || doc.ricevappTab4FillDone) return;
                        doc.ricevappTab4FillDone = true;

                        let win = doc.defaultView || window;

                        let setVal = function(el, val) {
                            if (!el) return;
                            try {
                                let prototype = win.HTMLInputElement ? win.HTMLInputElement.prototype : Object.getPrototypeOf(el);
                                let descriptor = Object.getOwnPropertyDescriptor(prototype, 'value');
                                if (descriptor && descriptor.set) {
                                    descriptor.set.call(el, val);
                                } else {
                                    el.value = val;
                                }
                            } catch(e) {
                                el.value = val;
                            }
                            try {
                                let EventClass = win.Event || Event;
                                el.dispatchEvent(new EventClass('input', { bubbles: true }));
                                el.dispatchEvent(new EventClass('change', { bubbles: true }));
                            } catch(e) {}
                        };

                        let fillFields = function() {
                            let u = doc.getElementById('username-fo-ent') || doc.getElementById('username') || doc.querySelector("input[name='IDToken1']") || doc.getElementById('username-sister');
                            let p = doc.getElementById('password-fo-ent-1') || doc.getElementById('password') || doc.querySelector("input[name='IDToken2']") || doc.getElementById('password-fo-sist');
                            let pin = doc.getElementById('pin-fo-ent') || doc.getElementById('pin') || doc.querySelector("input[name='IDToken3']");

                            if (u) setVal(u, $jsonUsername);
                            if (p) setVal(p, $jsonPassword);
                            if (pin) setVal(pin, $jsonPin);
                        };

                        let tab4Pane = doc.getElementById('tab-4');
                        if (tab4Pane && (tab4Pane.classList.contains('active') || tab4Pane.classList.contains('show'))) {
                            fillFields();
                        }

                        doc.addEventListener('click', function(e) {
                            let target = e.target;
                            if (!target) return;
                            let elem = (target.nodeType === 3) ? target.parentElement : target;
                            if (!elem) return;

                            let tabLink = elem.closest ? elem.closest("a[href='#tab-4']") : null;
                            if (tabLink) {
                                setTimeout(fillFields, 150);
                            }
                        });
                    };

                    setupTab4Fill(document);
                    const iframe = document.getElementsByTagName('iframe')[0];
                    if (iframe) {
                        try { setupTab4Fill(iframe.contentDocument); } catch(e) {}
                    }
                })(); 
            """
            webView.post { webView.evaluateJavascript(jsSetCredentials, null) }
        }
    }
}