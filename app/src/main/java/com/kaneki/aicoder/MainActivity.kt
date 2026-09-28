package com.kaneki.aicoder

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.kaneki.aicoder.data.local.AppPrefs
import com.kaneki.aicoder.data.local.ThemeMode
import com.kaneki.aicoder.ui.navigation.AppNavHost
import com.kaneki.aicoder.ui.theme.AiCoderTheme

/**
 * Entry point — tema M3 + Monet + Navigation Compose ([AppNavHost]).
 *
 * Edge-to-edge penuh: Surface berlatar warna tema menutupi SELURUH layar
 * (termasuk area di belakang status bar & bilah navigasi), jadi tidak ada
 * strip kosong. Inset sistem ditangani per layar (Scaffold di ChatScreen,
 * safeDrawingPadding di layar lain) — bukan di root — supaya scrim drawer
 * dan latar bisa sampai ke tepi layar.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appPrefs = AppPrefs.getInstance(this)

        setContent {
            val themeMode by appPrefs.themeMode.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> systemDark
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            // Ikon status bar / bilah navigasi mengikuti tema APLIKASI (bukan hanya
            // tema sistem), supaya tetap terbaca saat user memaksa Terang/Gelap.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        Color.TRANSPARENT,
                        Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        Color.argb(0xE6, 0xFF, 0xFF, 0xFF),
                        Color.argb(0x80, 0x1B, 0x1B, 0x1B)
                    ) { darkTheme }
                )
                onDispose {}
            }

            AiCoderTheme(
                themeMode = themeMode,
                dynamicColor = true
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Hanya sisi kiri/kanan dari notch yang dihindari di root
                    // (mode landscape); atas/bawah/keyboard diurus tiap layar.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(
                                WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
                            )
                    ) {
                        AppNavHost()
                    }
                }
            }
        }
    }
}
