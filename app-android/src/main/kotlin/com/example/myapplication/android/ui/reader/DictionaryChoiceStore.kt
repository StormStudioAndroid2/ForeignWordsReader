package com.example.myapplication.android.ui.reader

import android.content.Context

private const val DictionaryPreferencesName = "external_text_actions"
private const val PreferredDictionaryPackageKey = "preferred_dictionary_package"

internal class DictionaryChoiceStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        DictionaryPreferencesName,
        Context.MODE_PRIVATE,
    )

    fun preferredPackageName(): String? =
        preferences.getString(PreferredDictionaryPackageKey, null)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    fun remember(packageName: String) {
        val normalizedPackageName = packageName.trim()
        if (normalizedPackageName.isEmpty()) {
            clear()
            return
        }

        preferences.edit()
            .putString(PreferredDictionaryPackageKey, normalizedPackageName)
            .apply()
    }

    fun clear() {
        preferences.edit()
            .remove(PreferredDictionaryPackageKey)
            .apply()
    }
}
