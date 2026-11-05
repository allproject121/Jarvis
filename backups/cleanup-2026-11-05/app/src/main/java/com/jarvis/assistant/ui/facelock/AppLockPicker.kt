package com.jarvis.assistant.ui.facelock

import android.content.Intent
import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jarvis.assistant.databinding.DialogAppLockPickerBinding
import com.jarvis.assistant.databinding.ItemAppLockAppBinding
import com.jarvis.assistant.util.HudSoundManager
import com.jarvis.assistant.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Protocol 7 app picker: master "App lock" switch + launchable apps with checkboxes.
 * Shown by JarvisAppLockActivity only after a successful biometric / device-credential check.
 * Every change is written to [AppLockStore] immediately.
 */
class AppLockPicker(
    private val activity: AppCompatActivity,
    private val onClosed: () -> Unit
) {

    private data class AppEntry(val packageName: String, val label: String, val icon: Drawable?)

    private lateinit var binding: DialogAppLockPickerBinding
    private var dialog: AlertDialog? = null
    private var allApps: List<AppEntry> = emptyList()
    private val adapter = AppAdapter()

    val isShowing: Boolean get() = dialog?.isShowing == true

    fun show() {
        binding = DialogAppLockPickerBinding.inflate(LayoutInflater.from(activity))
        binding.switchAppLockMaster.isChecked = AppLockStore.isEnabled(activity)
        binding.switchAppLockMaster.setOnCheckedChangeListener { _, isChecked ->
            HudSoundManager.playTapSound()
            AppLockStore.setEnabled(activity, isChecked)
            updateSummary()
        }
        binding.btnAppLockOpenAccessibility.setOnClickListener {
            PermissionHelper.openAccessibilitySettings(activity)
        }
        binding.rvAppLockApps.layoutManager = LinearLayoutManager(activity)
        binding.rvAppLockApps.adapter = adapter
        binding.etAppLockSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        refreshAccessibilityRow()
        updateSummary()

        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Protocol 7 · App Lock")
            .setView(binding.root)
            .setPositiveButton("Done", null)
            .setOnDismissListener { onClosed() }
            .show()

        loadApps()
    }

    fun dismiss() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
    }

    /** Called from the activity's onResume (user may come back from Accessibility settings). */
    fun refreshAccessibilityRow() {
        if (!::binding.isInitialized) return
        val enabled = PermissionHelper.isAccessibilityServiceEnabled(activity)
        binding.layoutAppLockAccessibility.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun updateSummary() {
        val count = AppLockStore.lockedPackages(activity).size
        binding.tvAppLockSummary.text = if (AppLockStore.isEnabled(activity)) {
            "$count app(s) locked. They ask for Face / Fingerprint / PIN each time you open them."
        } else {
            "App lock is OFF. Turn it on, then check the apps to protect."
        }
    }

    private fun loadApps() {
        activity.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = activity.packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(launcherIntent, 0)
                    .asSequence()
                    .mapNotNull { it.activityInfo }
                    .filter { it.packageName != activity.packageName }
                    .distinctBy { it.packageName }
                    .map { info ->
                        AppEntry(
                            packageName = info.packageName,
                            label = try { info.loadLabel(pm).toString() } catch (_: Exception) { info.packageName },
                            icon = try { info.loadIcon(pm) } catch (_: Exception) { null }
                        )
                    }
                    .toList()
            }
            val locked = AppLockStore.lockedPackages(activity)
            allApps = apps.sortedWith(
                compareBy<AppEntry>({ it.packageName !in locked }, { it.label.lowercase(Locale.ROOT) })
            )
            if (!::binding.isInitialized) return@launch
            binding.tvAppLockLoading.visibility = if (allApps.isEmpty()) View.VISIBLE else View.GONE
            if (allApps.isEmpty()) binding.tvAppLockLoading.text = "No apps found"
            applyFilter()
        }
    }

    private fun applyFilter() {
        val query = binding.etAppLockSearch.text?.toString().orEmpty().trim().lowercase(Locale.ROOT)
        adapter.submit(
            if (query.isEmpty()) allApps
            else allApps.filter { it.label.lowercase(Locale.ROOT).contains(query) || it.packageName.contains(query) }
        )
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppAdapter.Holder>() {
        private var items: List<AppEntry> = emptyList()

        inner class Holder(val item: ItemAppLockAppBinding) : RecyclerView.ViewHolder(item.root)

        fun submit(list: List<AppEntry>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemAppLockAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = items[position]
            holder.item.tvAppLockLabel.text = entry.label
            holder.item.ivAppLockIcon.setImageDrawable(entry.icon)
            holder.item.cbAppLockLocked.isChecked = AppLockStore.isLocked(activity, entry.packageName)
            holder.item.root.setOnClickListener {
                HudSoundManager.playTapSound()
                val nowLocked = !AppLockStore.isLocked(activity, entry.packageName)
                AppLockStore.setLocked(activity, entry.packageName, nowLocked)
                holder.item.cbAppLockLocked.isChecked = nowLocked
                if (nowLocked && !AppLockStore.isEnabled(activity)) {
                    // Checking an app implies the user wants the lock on.
                    binding.switchAppLockMaster.isChecked = true
                }
                updateSummary()
            }
        }
    }
}
