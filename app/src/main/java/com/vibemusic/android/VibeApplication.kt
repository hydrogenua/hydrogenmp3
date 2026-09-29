package com.vibemusic.android

import android.app.Application
import java.io.File

/**
 * «Чёрный ящик»: любой необработанный краш пишется в filesDir/last_crash.txt,
 * а при следующем запуске приложение показывает стектрейс с кнопкой «скопировать».
 * Для sideload-тестирования без logcat это единственный способ узнать причину.
 */
class VibeApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                File(filesDir, CRASH_FILE).writeText(throwable.stackTraceToString())
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_FILE = "last_crash.txt"
    }
}
