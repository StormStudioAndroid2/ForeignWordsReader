package com.example.myapplication.android.ui.reader

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.example.myapplication.shared.reader.ReaderWordItem

internal enum class ExternalTextAction(
    val chooserTitle: String,
) {
    Dictionary("Open dictionary with"),
    Translate("Translate word"),
}

internal object ExternalTextActionLauncher {

    fun launch(
        activity: Activity,
        item: ReaderWordItem,
        action: ExternalTextAction,
    ) {
        launch(
            activity = activity,
            text = item.actionText(),
            action = action,
        )
    }

    fun launch(
        activity: Activity,
        text: String,
        action: ExternalTextAction,
    ) {
        val text = text.trim()
        if (text.isEmpty()) {
            return
        }

        when (action) {
            ExternalTextAction.Dictionary -> {
                if (launchDictionaryOrShowPicker(activity, text)) {
                    return
                }
            }
            ExternalTextAction.Translate -> {
                for (intent in translationIntents(text)) {
                    if (
                        activity.packageManager.hasActivity(intent) &&
                        activity.startChooser(intent, action.chooserTitle)
                    ) {
                        return
                    }
                }
            }
        }

        showUnavailableMessage(activity)
    }

    fun showUnavailableMessage(context: Context) {
        Toast.makeText(
            context,
            "No dictionary or translation app found",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun translationIntents(text: String): List<Intent> =
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(translationIntent(text))
            }
            add(processTextIntent(text))
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun translationIntent(text: String): Intent =
        Intent(Intent.ACTION_TRANSLATE)
            .putExtra(Intent.EXTRA_TEXT, text)

    private fun processTextIntent(text: String): Intent =
        Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)

    private fun Activity.startChooser(intent: Intent, title: String): Boolean =
        try {
            startActivity(Intent.createChooser(intent, title))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    private fun PackageManager.hasActivity(intent: Intent): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            ).isNotEmpty()
        } else {
            @Suppress("DEPRECATION")
            queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).isNotEmpty()
        }
}

internal fun ReaderWordItem.actionText(): String =
    displayWord.trim().ifEmpty { lemma.trim() }

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
