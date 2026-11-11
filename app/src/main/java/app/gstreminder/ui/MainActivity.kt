package app.gstreminder.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val screen by vm.screen.collectAsStateWithLifecycle()
                BackHandler(enabled = screen != Screen.Dashboard) { vm.back() }
                when (val s = screen) {
                    Screen.Dashboard -> DashboardScreen(vm)
                    Screen.Import -> ImportScreen(vm)
                    is Screen.Detail -> DealerScreen(vm, s.dealerId)
                    Screen.Settings -> SettingsScreen(vm)
                }
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme(primary = Color(0xFF9CC2FF))
        else -> lightColorScheme(primary = Color(0xFF1E5AA8))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

object StatusColors {
    val ok = Color(0xFF2E7D32)
    val warn = Color(0xFFB26A00)
    val bad = Color(0xFFC62828)
    val info = Color(0xFF1565C0)
    val muted = Color(0xFF757575)
}
