package com.zzy.llmreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zzy.llmreader.ui.ReaderApp
import com.zzy.llmreader.ui.theme.LlmReaderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LlmReaderTheme {
                ReaderApp(initialUrl = incomingUrl(intent))
            }
        }
    }

    private fun incomingUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        return intent.dataString
    }
}
