package com.giacomomensio.ricevapp

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {

    lateinit var sharedPreferences: SharedPreferences
    private lateinit var bottomNavigation: BottomNavigationView
    
    private val LAST_TAB_KEY = "LAST_TAB_KEY"
    private val DISCLAIMER_DISMISSED_KEY = "DISCLAIMER_DISMISSED_KEY"
    private var activeFragmentTag: String? = null

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val fragmentManager = supportFragmentManager
            val documenti = fragmentManager.findFragmentByTag("documenti") as? HomeFragment
            
            if (documenti != null && documenti.isVisible) {
                if (documenti.canWebViewGoBack()) {
                    documenti.webViewGoBack()
                    return
                }
            }
            
            if (bottomNavigation.selectedItemId != R.id.nav_settings) {
                bottomNavigation.selectedItemId = R.id.nav_settings
            } else {
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, systemBars.top, v.paddingRight, 0)
            insets
        }

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        sharedPreferences = EncryptedSharedPreferences.create(
            "secret_shared_prefs",
            masterKeyAlias,
            applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        bottomNavigation = findViewById(R.id.bottom_navigation)
        bottomNavigation.setOnItemSelectedListener { item ->
            navigateToTab(item.itemId)
            true
        }

        if (savedInstanceState == null) {
            val lastTabId = sharedPreferences.getInt(LAST_TAB_KEY, R.id.nav_settings)
            bottomNavigation.selectedItemId = lastTabId
            navigateToTab(lastTabId)
            
            showDisclaimerDialog()
        } else {
            activeFragmentTag = savedInstanceState.getString("ACTIVE_TAG")
        }
    }

    private fun showDisclaimerDialog() {
        if (sharedPreferences.getBoolean(DISCLAIMER_DISMISSED_KEY, false)) {
            return
        }

        val builder = AlertDialog.Builder(this)
        val inflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_disclaimer, null)
        builder.setView(dialogView)

        val dialog = builder.create()

        val dontShowAgainCheckbox = dialogView.findViewById<CheckBox>(R.id.dont_show_again_checkbox)
        val btnUnderstand = dialogView.findViewById<Button>(R.id.btn_understand)

        btnUnderstand.setOnClickListener {
            if (dontShowAgainCheckbox.isChecked) {
                sharedPreferences.edit().putBoolean(DISCLAIMER_DISMISSED_KEY, true).apply()
            }
            dialog.dismiss()
        }

        // Importante: Rimuoviamo lo sfondo trasparente della finestra del dialogo 
        // per lasciare che sia la CardView nel layout XML a gestire lo sfondo e i bordi.
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCancelable(false)
        dialog.show()
    }

    fun resetToStartTab() {
        bottomNavigation.selectedItemId = R.id.nav_settings
    }

    private fun navigateToTab(itemId: Int) {
        val newTag = when (itemId) {
            R.id.nav_settings -> "gestione"
            R.id.nav_home -> "documenti"
            R.id.nav_info -> "guida"
            else -> "gestione"
        }

        if (activeFragmentTag == newTag) return

        val fragmentManager = supportFragmentManager
        val transaction = fragmentManager.beginTransaction()

        activeFragmentTag?.let { tag ->
            fragmentManager.findFragmentByTag(tag)?.let { transaction.hide(it) }
        }

        var targetFragment = fragmentManager.findFragmentByTag(newTag)

        if (targetFragment == null) {
            targetFragment = when (itemId) {
                R.id.nav_settings -> SettingsFragment()
                R.id.nav_home -> HomeFragment()
                R.id.nav_info -> InfoFragment()
                else -> SettingsFragment()
            }
            transaction.add(R.id.fragment_container, targetFragment, newTag)
        } else {
            transaction.show(targetFragment)
        }

        activeFragmentTag = newTag
        transaction.commit()
        sharedPreferences.edit().putInt(LAST_TAB_KEY, itemId).apply()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("ACTIVE_TAG", activeFragmentTag)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val documenti = supportFragmentManager.findFragmentByTag("documenti") as? HomeFragment
        documenti?.handlePermissionResult(requestCode, grantResults)
    }
}