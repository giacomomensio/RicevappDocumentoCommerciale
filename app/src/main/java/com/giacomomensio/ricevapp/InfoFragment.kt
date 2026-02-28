package com.giacomomensio.ricevapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment

class InfoFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_info, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val versionText = view.findViewById<TextView>(R.id.app_version_text)
        val privacyButton = view.findViewById<Button>(R.id.privacy_policy_button)

        // Imposta la versione dinamicamente
        try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            val version = pInfo.versionName
            versionText.text = "Versione: $version"
        } catch (e: Exception) {
            versionText.text = "Versione: 1.6.0"
        }

        privacyButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("https://github.com/giacomomensio/RicevappDocumentoCommerciale/blob/main/PRIVACY_POLICY.md")
            startActivity(intent)
        }
    }
}