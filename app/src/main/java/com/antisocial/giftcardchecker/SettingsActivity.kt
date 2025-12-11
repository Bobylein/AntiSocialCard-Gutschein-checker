package com.antisocial.giftcardchecker

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.antisocial.giftcardchecker.databinding.ActivitySettingsBinding
import com.antisocial.giftcardchecker.settings.SettingsPreferences
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Settings activity for configuring app preferences.
 * Currently supports enabling/disabling automatic CAPTCHA solving.
 */
@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
    }

    private fun setupUI() {
        // Back button
        binding.btnBack.setOnClickListener {
            finish()
        }

        // Auto CAPTCHA toggle
        binding.switchAutoCaptcha.isChecked = settingsPreferences.autoCaptchaEnabled

        binding.switchAutoCaptcha.setOnCheckedChangeListener { _, isChecked ->
            settingsPreferences.autoCaptchaEnabled = isChecked
        }
    }
}
