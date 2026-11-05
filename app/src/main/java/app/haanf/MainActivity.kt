package app.haanf

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.haanf.ui.HaanfRoot
import app.haanf.ui.theme.HaanfTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HaanfApplication).container
        setContent {
            HaanfTheme {
                HaanfRoot(container)
            }
        }
    }
}
