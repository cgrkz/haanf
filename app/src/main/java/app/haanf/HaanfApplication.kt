package app.haanf

import android.app.Application
import androidx.annotation.VisibleForTesting

class HaanfApplication : Application() {
    var container: AppContainer
        get() = current ?: AppContainer(this).also { current = it }

        @VisibleForTesting
        set(value) {
            current = value
        }

    private var current: AppContainer? = null
}
