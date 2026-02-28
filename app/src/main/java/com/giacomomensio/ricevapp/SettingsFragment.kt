package com.giacomomensio.ricevapp

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.materialswitch.MaterialSwitch

class SettingsFragment : Fragment() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var securityStatusText: TextView
    private lateinit var saveCredentialsSwitch: MaterialSwitch
    private lateinit var skipPageSwitch: MaterialSwitch
    private lateinit var clearDataButton: Button
    private lateinit var goToDocumentsButton: Button

    private val SHOULD_SAVE_KEY = "SHOULD_SAVE_KEY"
    private val SKIP_INTERMEDIATE_PAGE_KEY = "SKIP_INTERMEDIATE_PAGE_KEY"
    private val USERNAME_KEY = "USERNAME_KEY"
    private val PASSWORD_KEY = "PASSWORD_KEY"
    private val PIN_KEY = "PIN_KEY"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        sharedPreferences = EncryptedSharedPreferences.create(
            "secret_shared_prefs",
            masterKeyAlias,
            requireContext(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        securityStatusText = view.findViewById(R.id.security_status_text)
        saveCredentialsSwitch = view.findViewById(R.id.save_credentials_switch)
        skipPageSwitch = view.findViewById(R.id.skip_page_switch)
        clearDataButton = view.findViewById(R.id.clear_data_button)
        goToDocumentsButton = view.findViewById(R.id.go_to_documents_button)

        setupSecurityStatus()
        setupPreferences()
        setupButtons()
        
        syncSettings()
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
        
        val isSaveActive = sharedPreferences.getBoolean(SHOULD_SAVE_KEY, false)
        saveCredentialsSwitch.isChecked = isSaveActive
        
        val isSkipActive = sharedPreferences.getBoolean(SKIP_INTERMEDIATE_PAGE_KEY, false)
        skipPageSwitch.isChecked = isSkipActive
        skipPageSwitch.isVisible = isSkipActive
        
        setupSecurityStatus()
    }

    private fun setupSecurityStatus() {
        val biometricManager = BiometricManager.from(requireContext())
        val keyguardManager = requireContext().getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        
        val canAuthenticate = biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
        
        val status = when (canAuthenticate) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                if (keyguardManager.isDeviceSecure) {
                    "Protezione attiva: Biometria (Impronta/Viso) e Credenziali di sistema."
                } else {
                    "Biometria disponibile ma dispositivo non protetto."
                }
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "Nessuna biometria registrata. L'app userà il PIN/Sequenza del dispositivo."
            else -> {
                if (keyguardManager.isDeviceSecure) {
                    "Protezione attiva: PIN, Sequenza o Password di sistema."
                } else {
                    "ATTENZIONE: Dispositivo non protetto. Ti consigliamo di attivare un blocco schermo per la tua sicurezza."
                }
            }
        }
        securityStatusText.text = status
    }

    private fun setupPreferences() {
        saveCredentialsSwitch.setOnClickListener {
            val isChecked = (it as MaterialSwitch).isChecked
            sharedPreferences.edit().putBoolean(SHOULD_SAVE_KEY, isChecked).apply()
            if (!isChecked) {
                sharedPreferences.edit().remove(USERNAME_KEY).remove(PASSWORD_KEY).remove(PIN_KEY).apply()
                Toast.makeText(context, "Dati di login rimossi", Toast.LENGTH_SHORT).show()
            }
        }

        skipPageSwitch.setOnClickListener {
            val switch = it as MaterialSwitch
            if (!switch.isChecked) {
                // L'utente sta provando a disattivare
                AlertDialog.Builder(requireContext())
                    .setTitle("Disattiva Salto Pagina")
                    .setMessage("Se disattivi questa opzione, al prossimo login visualizzerai di nuovo la landing page del portale. Potrai riattivarla solo da quella pagina.")
                    .setPositiveButton("Disattiva") { _, _ ->
                        sharedPreferences.edit().putBoolean(SKIP_INTERMEDIATE_PAGE_KEY, false).apply()
                        switch.isVisible = false
                        Toast.makeText(context, "Redirect automatico disattivato", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Annulla") { _, _ ->
                        switch.isChecked = true // Ripristina lo stato
                    }
                    .setCancelable(false)
                    .show()
            } else {
                // Questo caso in teoria non dovrebbe essere raggiungibile dato che è invisibile se false,
                // ma per sicurezza lo gestiamo.
                sharedPreferences.edit().putBoolean(SKIP_INTERMEDIATE_PAGE_KEY, true).apply()
            }
        }
    }

    private fun setupButtons() {
        clearDataButton.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Cancellazione Totale")
                .setMessage("Sei sicuro di voler cancellare tutti i dati salvati, le preferenze e la cache della WebView? Dovrai rifare il login.")
                .setPositiveButton("Sì, cancella") { _, _ ->
                    sharedPreferences.edit().clear().apply()
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.removeAllCookies(null)
                    cookieManager.flush()
                    val webViewDummy = WebView(requireContext())
                    webViewDummy.clearCache(true)
                    webViewDummy.clearHistory()
                    webViewDummy.destroy()
                    
                    Toast.makeText(context, "Tutti i dati sono stati rimossi", Toast.LENGTH_LONG).show()
                    requireActivity().recreate()
                }
                .setNegativeButton("Annulla", null)
                .show()
        }

        goToDocumentsButton.setOnClickListener {
            val bottomNav = requireActivity().findViewById<BottomNavigationView>(R.id.bottom_navigation)
            bottomNav.selectedItemId = R.id.nav_home
        }
    }
}