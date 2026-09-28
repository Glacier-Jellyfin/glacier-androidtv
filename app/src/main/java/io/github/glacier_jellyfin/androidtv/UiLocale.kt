package io.github.glacier_jellyfin.androidtv

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.content.edit
import java.util.Locale

/**
 * The interface language of the active profile (Settings › Account). The
 * activity applies it to its own context before anything is drawn. The
 * language in use is kept here, readable before the settings file, so the
 * app starts in it; null means the device language.
 */
object UiLocale {

    private const val PREFS = "ui_locale"
    private const val KEY_TAG = "tag"

    /** The device language, whatever Glacier set as the default. */
    private val system: Locale get() = Resources.getSystem().configuration.locales[0]

    fun current(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)

    /** Written at once: the activity is recreated right after and reads it back. */
    fun set(context: Context, tag: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            if (tag == null) remove(KEY_TAG) else putString(KEY_TAG, tag)
        }
    }

    /** [base] in the chosen language; also the default locale, for number and date formats. */
    fun wrap(base: Context): Context {
        val tag = current(base)
        val locale = tag?.let(Locale::forLanguageTag) ?: system
        Locale.setDefault(locale)
        if (tag == null) return base
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }
}
