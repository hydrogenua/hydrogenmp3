package com.vibemusic.android

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vibemusic.android.data.LANG_KEY
import com.vibemusic.android.data.appDataStore
import com.vibemusic.android.ui.VibeApp
import com.vibemusic.android.ui.theme.VibeTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        // Свой выбор языка (Настройки → Язык); "system" — не трогаем.
        val lang = runBlocking {
            newBase.appDataStore.data.first()[LANG_KEY] ?: "system"
        }
        super.attachBaseContext(
            if (lang == "system") newBase
            else {
                val cfg = Configuration(newBase.resources.configuration)
                cfg.setLocale(Locale(lang))
                newBase.createConfigurationContext(cfg)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Приложение всегда тёмное — рисуем системные бары поверх фона со светлыми иконками.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            VibeTheme {
                VibeApp()
            }
        }
    }
}
