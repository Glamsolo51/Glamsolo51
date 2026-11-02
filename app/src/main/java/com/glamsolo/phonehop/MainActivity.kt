package com.glamsolo.phonehop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.glamsolo.phonehop.ui.MainViewModel
import com.glamsolo.phonehop.ui.PhoneHopRoot
import com.glamsolo.phonehop.ui.components.IconSource
import com.glamsolo.phonehop.ui.components.LocalIconSource
import com.glamsolo.phonehop.ui.theme.PhoneHopTheme
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val icons = IconSource { graph.icons.load(it) }
        setContent {
            PhoneHopTheme {
                CompositionLocalProvider(LocalIconSource provides icons) {
                    PhoneHopRoot(viewModel)
                }
            }
        }

        // Android's "install this app?" screens are opened from the foreground activity.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                graph.installer.confirmation.filterNotNull().collect { intent ->
                    graph.installer.confirmationShown()
                    runCatching { startActivity(intent) }
                }
            }
        }
    }
}
