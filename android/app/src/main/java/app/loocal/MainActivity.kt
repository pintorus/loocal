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
    /** A page a launcher shortcut asked for (loocal://chat, errand, results). */
    private val requested = androidx.compose.runtime.mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        requested.value = route(intent)
    }

    /** A launcher shortcut's page, or a Hugging Face link shared to Loocal (Settings › Models opens on it). */
    private fun route(intent: android.content.Intent?): String? {
        // Debug builds: `am start -n app.loocal/.MainActivity --ez benchPaint true` paints one picture and logs it.
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 && intent?.getBooleanExtra("benchPaint", false) == true) { vm.benchmarkPaint(intent.getStringExtra("paintOptions")?.split(';')?.filter { '=' in it }?.associate { it.substringBefore('=') to it.substringAfter('=') }); return null }
        if (intent?.action == android.content.Intent.ACTION_SEND) {
            val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return null
            return if (vm.addModel(text)) "models" else null
        }
        return intent?.data?.host
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) requested.value = route(intent)
        setContent {
            // Status and gesture bars follow the page: dark icons on paper, light ones on ink.
            LoocalApp(vm, requested.value, { requested.value = null }) { isDark ->
                if (dark != isDark) {
                    dark = isDark
                    val style = if (isDark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
            }
        }
    }
}
