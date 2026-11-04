package com.jarvis.assistant.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.jarvis.assistant.databinding.ActivityPrivacyShieldBinding
import com.jarvis.assistant.util.ThemeManager

class PrivacyShieldActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacyShieldBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyShieldBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnJoinOfficialTelegram.setOnClickListener {
            openUrl("https://github.com/tiwwtiroshan121")
        }

        binding.btnJoinTelegram.setOnClickListener {
            openUrl("https://github.com/freeid2601-dotcom")
        }

        binding.btnJoinInstagram.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:tiwariroshan2601@gmail.com")
            }
            try {
                startActivity(intent)
            } catch (_: Exception) {
                openUrl("mailto:tiwariroshan2601@gmail.com")
            }
        }

        binding.btnJoinYoutube.setOnClickListener {
            android.widget.Toast.makeText(this, "YouTube channel is coming soon and will be updated here!", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (_: Exception) {
        }
    }
}
