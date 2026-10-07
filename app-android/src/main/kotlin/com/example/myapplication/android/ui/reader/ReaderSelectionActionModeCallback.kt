package com.example.myapplication.android.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFragment

private const val DictionaryActionId = 1
private const val TranslateActionId = 2
private const val CopyActionId = 3

internal class ReaderSelectionActionModeCallback(
    private val applicationContext: Context,
    private val scope: CoroutineScope,
    private val navigatorProvider: () -> EpubNavigatorFragment?,
) : ActionMode.Callback {

    private var backCallback: OnBackPressedCallback? = null

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.clear()
        menu.add(Menu.NONE, DictionaryActionId, 0, "Dictionary")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, TranslateActionId, 1, "Translate")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(Menu.NONE, CopyActionId, 2, "Copy")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        installSelectionBackCallback(mode)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val action = when (item.itemId) {
            DictionaryActionId -> SelectionAction.Dictionary
            TranslateActionId -> SelectionAction.Translate
            CopyActionId -> SelectionAction.Copy
            else -> return false
        }

        scope.launch {
            val navigator = navigatorProvider()
            val externalLaunch = try {
                try {
                    val text = navigator
                        ?.currentSelection()
                        ?.locator
                        ?.text
                        ?.highlight
                        ?.trim()
                        .orEmpty()
                    if (text.isEmpty()) {
                        return@launch
                    }

                    when (action) {
                        SelectionAction.Dictionary -> PendingExternalLaunch(
                            activity = navigator?.activity,
                            text = text,
                            action = ExternalTextAction.Dictionary,
                        )
                        SelectionAction.Translate -> PendingExternalLaunch(
                            activity = navigator?.activity,
                            text = text,
                            action = ExternalTextAction.Translate,
                        )
                        SelectionAction.Copy -> {
                            val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE)
                                as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("Selected text", text),
                            )
                            null
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A fragment can detach while Readium is resolving the selection.
                    null
                }
            } finally {
                try {
                    clearSelection(navigator)
                } finally {
                    mode.finish()
                }
            }

            if (externalLaunch != null) {
                val activity = externalLaunch.activity
                if (activity == null || activity.isFinishing || activity.isDestroyed) {
                    ExternalTextActionLauncher.showUnavailableMessage(applicationContext)
                } else {
                    ExternalTextActionLauncher.launch(
                        activity = activity,
                        text = externalLaunch.text,
                        action = externalLaunch.action,
                    )
                }
            }
        }
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        backCallback?.remove()
        backCallback = null
    }

    private fun installSelectionBackCallback(mode: ActionMode) {
        backCallback?.remove()
        val activity = navigatorProvider()?.activity as? ComponentActivity ?: return
        backCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                isEnabled = false
                mode.finish()
                scope.launch {
                    clearSelection(navigatorProvider())
                }
            }
        }.also { callback ->
            activity.onBackPressedDispatcher.addCallback(callback)
        }
    }

    private suspend fun clearSelection(navigator: EpubNavigatorFragment?) {
        try {
            navigator?.clearSelection()
        } catch (_: IllegalStateException) {
            // The navigator can detach while the suspending selection operation is pending.
        }
    }

    private data class PendingExternalLaunch(
        val activity: android.app.Activity?,
        val text: String,
        val action: ExternalTextAction,
    )

    private enum class SelectionAction {
        Dictionary,
        Translate,
        Copy,
    }
}
