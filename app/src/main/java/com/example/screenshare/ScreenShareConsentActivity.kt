package com.example.screenshare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Lightweight translucent Activity that requests Android MediaProjection screen-share
 * consent cleanly when triggered from the Floating Overlay or a voice command.
 */
class ScreenShareConsentActivity : ComponentActivity() {

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, ScreenShareConsentActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenShareService.startScreenShare(
                context = applicationContext,
                resultCode = result.resultCode,
                data = data
            )
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectionLauncher.launch(ScreenShareService.createCaptureIntent(this))
    }
}
