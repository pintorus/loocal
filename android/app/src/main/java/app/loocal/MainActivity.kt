package app.loocal

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.loocal.ui.ChatViewModel
import app.loocal.ui.LoocalApp

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()
    private var dark: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Status and gesture bars follow the page: dark icons on paper, light ones on ink.
            LoocalApp(vm) { isDark ->
                if (dark != isDark) {
                    dark = isDark
                    val style = if (isDark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
            }
        }
    }
}
