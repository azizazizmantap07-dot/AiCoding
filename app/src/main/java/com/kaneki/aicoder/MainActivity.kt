package com.kaneki.aicoder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.kaneki.aicoder.data.local.AppPrefs
import com.kaneki.aicoder.ui.navigation.AppNavHost
import com.kaneki.aicoder.ui.theme.AiCoderTheme

/**
 * Entry point — tema M3 + Monet + Navigation Compose ([AppNavHost]).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appPrefs = AppPrefs.getInstance(this)

        setContent {
            val themeMode by appPrefs.themeMode.collectAsState()

            AiCoderTheme(
                themeMode = themeMode,
                dynamicColor = true
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavHost()
                }
            }
        }
    }
}
