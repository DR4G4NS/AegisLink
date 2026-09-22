package dev.aegis.remote.android

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.aegis.remote.android.ui.AegisAndroidApp
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAegisLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AegisAndroidApp()
        }
    }
}

private const val LANGUAGE_PREFERENCES = "aegis-language"
private const val LANGUAGE_KEY = "language-tag"

private fun Context.withAegisLocale(): Context {
    val languageTag =
        getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, null)
            .orEmpty()
    if (languageTag.isBlank()) return this
    val configuration =
        Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
    return createConfigurationContext(configuration)
}

internal fun MainActivity.selectLanguage(languageTag: String) {
    getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE)
        .edit()
        .putString(LANGUAGE_KEY, languageTag)
        .apply()
    recreate()
}
