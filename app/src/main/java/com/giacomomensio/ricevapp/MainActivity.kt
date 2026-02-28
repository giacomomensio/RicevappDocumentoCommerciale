package com.giacomomensio.ricevapp

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors

class MainActivity : AppCompatActivity() {

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var bottomNavigation: BottomNavigationView
    
    private val LAST_TAB_KEY = "LAST_TAB_KEY"
    private var activeFragmentTag: String? = null

    private val onBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val fragmentManager = supportFragmentManager
            val home = fragmentManager.findFragmentByTag("home") as? HomeFragment
            
            if (home != null && home.isVisible) {
                if (home.canWebViewGoBack()) {
                    home.webViewGoBack()
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
        } else {
            activeFragmentTag = savedInstanceState.getString("ACTIVE_TAG")
        }
    }

    private fun navigateToTab(itemId: Int) {
        val newTag = when (itemId) {
            R.id.nav_settings -> "settings"
            R.id.nav_home -> "home"
            R.id.nav_info -> "info"
            else -> "settings"
        }

        if (activeFragmentTag == newTag) return

        val fragmentManager = supportFragmentManager
        val transaction = fragmentManager.beginTransaction()

        // 1. Nascondi il frammento attivo precedente (se esiste)
        activeFragmentTag?.let { tag ->
            fragmentManager.findFragmentByTag(tag)?.let { transaction.hide(it) }
        }

        // 2. Cerca il nuovo frammento
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
        val home = supportFragmentManager.findFragmentByTag("home") as? HomeFragment
        home?.handlePermissionResult(requestCode, grantResults)
    }
}