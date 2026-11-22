package app.loocal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import app.loocal.ui.ChatViewModel
import app.loocal.ui.LoocalApp

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Monochrome, light and dark, like the iOS app.
            val colors = if (isSystemInDarkTheme())
                darkColorScheme(primary = Color.White, onPrimary = Color.Black, background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF262626))
            else
                lightColorScheme(primary = Color.Black, onPrimary = Color.White, background = Color.White, surface = Color.White, surfaceVariant = Color(0xFFEDEDED))
            MaterialTheme(colorScheme = colors) { LoocalApp(vm) }
        }
    }
}
