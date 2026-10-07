package com.example.myapplication.android.ui.reader

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import kotlin.math.min

private const val DictionaryPickerTag = "dictionary_app_picker"
private const val DictionaryTextArgument = "dictionary_text"
private const val RememberChoiceState = "remember_dictionary_choice"
private const val DictionaryRowHeightDp = 64
private const val MaximumVisibleDictionaryRows = 5
private const val MaximumListScreenFraction = 0.45f

internal class DictionaryAppPickerDialogFragment : DialogFragment() {

    private var rememberChoiceCheckBox: CheckBox? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = requireContext()
        val text = requireArguments().getString(DictionaryTextArgument).orEmpty()
        val targets = context.packageManager.dictionaryTargets(text)
        if (targets.isEmpty()) {
            return AlertDialog.Builder(context)
                .setTitle("Open dictionary with")
                .setMessage("No dictionary app found")
                .setPositiveButton(android.R.string.ok, null)
                .create()
        }

        val adapter = DictionaryTargetAdapter(context, targets)
        val listView = ListView(context).apply {
            this.adapter = adapter
            divider = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                context.dictionaryListHeight(targets.size),
            )
        }
        val checkBox = CheckBox(context).apply {
            this.text = "Always use this app"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = context.dp(56)
            setPadding(
                context.dp(8),
                context.dp(8),
                context.dp(8),
                context.dp(8),
            )
            isChecked = savedInstanceState?.getBoolean(RememberChoiceState) ?: false
        }
        rememberChoiceCheckBox = checkBox
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(listView)
            addView(context.dictionaryFooterDivider())
            addView(
                checkBox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Open dictionary with")
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        listView.setOnItemClickListener { _, _, position, _ ->
            val target = targets.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            launchTarget(
                target = target,
                rememberChoice = checkBox.isChecked,
            )
        }
        return dialog
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            RememberChoiceState,
            rememberChoiceCheckBox?.isChecked ?: false,
        )
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        rememberChoiceCheckBox = null
        super.onDestroyView()
    }

    private fun launchTarget(
        target: DictionaryTarget,
        rememberChoice: Boolean,
    ) {
        val activity = activity ?: return
        if (activity.isFinishing || activity.isDestroyed) {
            showDictionaryUnavailableMessage(activity.applicationContext)
            return
        }

        try {
            activity.startActivity(target.intent)
            if (rememberChoice) {
                DictionaryChoiceStore(activity).remember(target.packageName)
            }
        } catch (_: ActivityNotFoundException) {
            DictionaryChoiceStore(activity).clear()
            showDictionaryAppUnavailableMessage(activity)
        } catch (_: SecurityException) {
            DictionaryChoiceStore(activity).clear()
            showDictionaryAppUnavailableMessage(activity)
        }
    }

    companion object {
        fun newInstance(text: String): DictionaryAppPickerDialogFragment =
            DictionaryAppPickerDialogFragment().apply {
                arguments = Bundle().apply {
                    putString(DictionaryTextArgument, text)
                }
            }
    }
}

internal fun launchDictionaryOrShowPicker(activity: Activity, text: String): Boolean {
    if (activity.isFinishing || activity.isDestroyed) {
        return false
    }

    val targets = activity.packageManager.dictionaryTargets(text)
    val choiceStore = DictionaryChoiceStore(activity)
    val preferredPackageName = choiceStore.preferredPackageName()
    if (preferredPackageName != null) {
        val preferredTarget = targets.firstOrNull { target ->
            target.packageName == preferredPackageName
        }
        if (preferredTarget == null) {
            choiceStore.clear()
        } else {
            return try {
                activity.startActivity(preferredTarget.intent)
                true
            } catch (_: ActivityNotFoundException) {
                choiceStore.clear()
                showDictionaryAppUnavailableMessage(activity)
                true
            } catch (_: SecurityException) {
                choiceStore.clear()
                showDictionaryAppUnavailableMessage(activity)
                true
            }
        }
    }

    return showDictionaryAppPicker(
        activity = activity,
        text = text,
        targetsAvailable = targets.isNotEmpty(),
    )
}

private fun showDictionaryAppPicker(
    activity: Activity,
    text: String,
    targetsAvailable: Boolean,
): Boolean {
    val fragmentActivity = activity as? FragmentActivity ?: return false
    if (fragmentActivity.isFinishing || fragmentActivity.isDestroyed) {
        return false
    }

    val fragmentManager = fragmentActivity.supportFragmentManager
    if (fragmentManager.isStateSaved) {
        return false
    }
    if (fragmentManager.findFragmentByTag(DictionaryPickerTag) != null) {
        return true
    }

    if (!targetsAvailable) {
        showDictionaryUnavailableMessage(activity)
        return true
    }

    return try {
        DictionaryAppPickerDialogFragment.newInstance(text)
            .showNow(fragmentManager, DictionaryPickerTag)
        true
    } catch (_: IllegalStateException) {
        false
    }
}

private fun PackageManager.dictionaryTargets(text: String): List<DictionaryTarget> {
    if (text.isBlank()) {
        return emptyList()
    }

    val intents = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(
                Intent(Intent.ACTION_DEFINE)
                    .putExtra(Intent.EXTRA_TEXT, text),
            )
        }
        add(
            Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true),
        )
    }

    val targetByPackage = linkedMapOf<String, DictionaryTarget>()
    for (intent in intents) {
        for (resolveInfo in queryActivities(intent)) {
            val activityInfo = resolveInfo.activityInfo ?: continue
            val packageName = activityInfo.packageName
            if (packageName in targetByPackage) {
                continue
            }

            val label = resolveInfo.loadLabel(this)
                ?.toString()
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: packageName
            val icon = try {
                resolveInfo.loadIcon(this)
            } catch (_: Exception) {
                null
            }
            targetByPackage[packageName] = DictionaryTarget(
                packageName = packageName,
                label = label,
                icon = icon,
                intent = Intent(intent).setComponent(
                    ComponentName(packageName, activityInfo.name),
                ),
            )
        }
    }

    return targetByPackage.values.sortedWith { left, right ->
        val labelComparison = left.label.compareTo(right.label, ignoreCase = true)
        if (labelComparison != 0) {
            labelComparison
        } else {
            left.packageName.compareTo(right.packageName)
        }
    }
}

private fun PackageManager.queryActivities(intent: Intent) =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        queryIntentActivities(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
    }

private fun showDictionaryUnavailableMessage(context: Context) {
    Toast.makeText(context, "No dictionary app found", Toast.LENGTH_SHORT).show()
}

private fun showDictionaryAppUnavailableMessage(context: Context) {
    Toast.makeText(context, "Dictionary app is unavailable", Toast.LENGTH_SHORT).show()
}

private data class DictionaryTarget(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val intent: Intent,
)

private class DictionaryTargetAdapter(
    context: Context,
    private val targets: List<DictionaryTarget>,
) : ArrayAdapter<DictionaryTarget>(
    context,
    android.R.layout.activity_list_item,
    android.R.id.text1,
    targets,
) {

    private val minimumRowHeight = context.dp(DictionaryRowHeightDp)
    private val horizontalPadding = context.dp(16)
    private val verticalPadding = context.dp(8)
    private val iconSize = context.dp(40)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val target = targets[position]
        view.minimumHeight = minimumRowHeight
        view.setPadding(
            horizontalPadding,
            verticalPadding,
            horizontalPadding,
            verticalPadding,
        )
        view.findViewById<TextView>(android.R.id.text1).apply {
            text = target.label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        view.findViewById<ImageView>(android.R.id.icon).apply {
            layoutParams = layoutParams.apply {
                width = iconSize
                height = iconSize
            }
            setImageDrawable(target.icon)
            contentDescription = null
        }
        return view
    }
}

private fun Context.dictionaryListHeight(targetCount: Int): Int {
    val rowHeight = dp(DictionaryRowHeightDp)
    val contentHeight = min(targetCount, MaximumVisibleDictionaryRows) * rowHeight
    val maximumHeight = (resources.displayMetrics.heightPixels * MaximumListScreenFraction).toInt()
    return min(contentHeight, maximumHeight).coerceAtLeast(rowHeight)
}

private fun Context.dictionaryFooterDivider(): View {
    val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
    val divider = attributes.getDrawable(0)
    attributes.recycle()
    return View(this).apply {
        background = divider
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1),
        )
    }
}

private fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()
