package app.haanf

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.haanf.ui.HaanfRoot
import app.haanf.ui.theme.HaanfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Haanf is light only, so the bar icons stay dark even when the phone is in dark mode.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val container = (application as HaanfApplication).container
        setContent {
            HaanfTheme {
                HaanfRoot(container)
            }
        }
    }
}
