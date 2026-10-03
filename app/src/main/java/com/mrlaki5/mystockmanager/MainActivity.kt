package com.mrlaki5.mystockmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.mrlaki5.mystockmanager.ui.nav.AppNav
import com.mrlaki5.mystockmanager.ui.theme.MyStockManagerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not enableEdgeToEdge(): it calls Window.setStatusBarColor, which Play flags as deprecated; bar styling is in themes.xml.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MyStockManagerTheme {
                AppNav()
            }
        }
    }
}
