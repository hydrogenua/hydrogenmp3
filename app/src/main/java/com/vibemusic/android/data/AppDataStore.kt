package com.vibemusic.android.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

/** Общее хранилище настроек приложения. */
val Context.appDataStore by preferencesDataStore(name = "app_settings")

/** ARL-токен аккаунта Deezer. */
val ARL_KEY = stringPreferencesKey("deezer_arl")

/** Тег версии, для которой юзер нажал «Позже» на обновлении. */
val DISMISSED_UPDATE_KEY = stringPreferencesKey("dismissed_update_tag")

/** Язык интерфейса: "system" | "ru" | "en". */
val LANG_KEY = stringPreferencesKey("app_lang")
