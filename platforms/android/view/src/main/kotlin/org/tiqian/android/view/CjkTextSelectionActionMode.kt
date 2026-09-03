package org.tiqian.android.view

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewConfiguration

/**
 * Owns the Android selection ActionMode for a Tiqian text-selection host.
 *
 * The selection controller remains the source of truth for source ranges and engine geometry.
 * This capability only adapts that state to Android's floating toolbar: it builds the default
 * read-only menu, forwards the TextView-compatible custom callback, and owns ActionMode lifetime.
 */
@SuppressLint("InlinedApi")
internal class CjkTextSelectionActionMode(
    private val host: View,
    private val customCallback: () -> ActionMode.Callback?,
    private val delegate: Delegate,
) {
    /**
     * Narrow bridge into selection semantics. It deliberately contains no layout or View policy.
     */
    internal interface Delegate {
        val hasSelection: Boolean
        val canSelectAll: Boolean

        fun copySelection(): Boolean
        fun selectAll(): Boolean
        fun selectedText(): String?
        fun selectionContentRect(outRect: Rect)
        fun onActionModeCreationRejected()
        fun onActionModeDestroyed(preserveSelection: Boolean)

        /**
         * TextClassifier smart actions are a separate capability from the built-in actions.
         * Returning false is intentional until the View frontend has a real classifier bridge.
         */
        fun performAssistAction(item: MenuItem): Boolean
    }

    private var actionMode: ActionMode? = null
    private var preserveSelectionFor: ActionMode? = null
    private var ownsTransientState = false
    private val showFloatingToolbar = Runnable {
        actionMode?.hide(0L)
    }

    /** Package-private inspection hook; the View has no public ActionMode getter, like TextView. */
    internal val currentActionMode: ActionMode?
        get() = actionMode

    fun show(): Boolean {
        if (!delegate.hasSelection || !canPresent() || !host.requestFocus()) return false
        actionMode?.let { mode ->
            host.removeCallbacks(showFloatingToolbar)
            mode.hide(0L)
            mode.invalidate()
            mode.invalidateContentRect()
            return true
        }
        val started = host.startActionMode(SelectionActionModeCallback(), ActionMode.TYPE_FLOATING)
        actionMode = started
        return started != null
    }

    fun hide() {
        host.removeCallbacks(showFloatingToolbar)
        actionMode?.hide(ActionMode.DEFAULT_HIDE_DURATION.toLong())
    }

    /** Mirrors Editor.showFloatingToolbar() after a selection gesture ends. */
    fun showAfterSelectionGesture(): Boolean {
        if (!delegate.hasSelection || !canPresent()) return false
        val mode = actionMode ?: return show()
        mode.invalidate()
        mode.invalidateContentRect()
        host.removeCallbacks(showFloatingToolbar)
        host.postDelayed(
            showFloatingToolbar,
            ViewConfiguration.getDoubleTapTimeout().toLong(),
        )
        return true
    }

    fun invalidate() {
        actionMode?.invalidate()
    }

    fun invalidateContentRect() {
        actionMode?.invalidateContentRect()
    }

    /**
     * Keeps the ActionMode transient surface in sync with host focus/visibility. A hidden floating
     * toolbar is intentionally not finished; Android's hide timeout and subsequent invalidation
     * retain the same lifecycle as a native floating selection toolbar.
     */
    fun onHostVisibilityOrFocusChanged() {
        if (!canPresent()) {
            hide()
            return
        }
        actionMode?.let { mode ->
            mode.invalidate()
            mode.invalidateContentRect()
        }
    }

    /** Rebuilds the menu through the callback on the next ActionMode invalidation. */
    fun onCustomCallbackChanged() {
        actionMode?.invalidate()
    }

    fun onSelectionCleared() {
        clearOwnedTransientState()
    }

    fun finish(preserveSelection: Boolean = false) {
        host.removeCallbacks(showFloatingToolbar)
        val mode = actionMode ?: return
        preserveSelectionFor = mode.takeIf { preserveSelection }
        mode.finish()
        if (actionMode === mode) actionMode = null
        preserveSelectionFor = null
    }

    fun dispose() {
        finish(preserveSelection = true)
    }

    private fun canPresent(): Boolean =
        host.isAttachedToWindow &&
            host.isShown &&
            host.visibility == View.VISIBLE &&
            host.windowVisibility == View.VISIBLE &&
            host.hasWindowFocus() &&
            host.isFocused

    private inner class SelectionActionModeCallback : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.setTitle(null as CharSequence?)
            mode.setSubtitle(null as CharSequence?)
            mode.setTitleOptionalHint(true)
            populateDefaultMenu(menu)
            val callback = customCallback()
            if (callback != null && !callback.onCreateActionMode(mode, menu)) {
                delegate.onActionModeCreationRejected()
                return false
            }
            addProcessTextItems(menu)
            if (delegate.hasSelection && !host.hasTransientState()) {
                host.setHasTransientState(true)
                ownsTransientState = true
            }
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateSelectAllItem(menu)
            return customCallback()?.onPrepareActionMode(mode, menu) ?: true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val processIntent = item.intent
            if (isProcessTextItem(item) && processIntent != null) {
                val launched = launchProcessText(processIntent)
                if (launched) {
                    preserveSelectionFor = mode
                    mode.finish()
                    return true
                }
            }

            if (customCallback()?.onActionItemClicked(mode, item) == true) {
                return true
            }

            if (delegate.performAssistAction(item)) return true

            return when (item.itemId) {
                android.R.id.copy -> delegate.copySelection().also { copied ->
                    if (copied) mode.finish()
                }

                android.R.id.selectAll -> {
                    mode.hide(SELECT_ALL_REFRESH_HIDE_DURATION_MILLIS)
                    delegate.selectAll().also { selected ->
                        if (selected) {
                            mode.invalidate()
                            mode.invalidateContentRect()
                        }
                    }
                }

                android.R.id.shareText -> shareSelection().also { shared ->
                    if (shared) mode.finish()
                }

                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            val preserveSelection = preserveSelectionFor === mode || !host.isAttachedToWindow
            preserveSelectionFor = null
            if (actionMode === mode) actionMode = null
            customCallback()?.onDestroyActionMode(mode)
            delegate.onActionModeDestroyed(preserveSelection)
            if (!preserveSelection) clearOwnedTransientState()
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (view !== host) {
                super.onGetContentRect(mode, view, outRect)
                return
            }
            delegate.selectionContentRect(outRect)
        }
    }

    private fun populateDefaultMenu(menu: Menu) {
        menu.add(Menu.NONE, android.R.id.copy, ORDER_COPY, android.R.string.copy)
            .setAlphabeticShortcut('c')
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        if (canShare()) {
            resolveSystemShareLabel()?.let { label ->
                menu.add(Menu.NONE, android.R.id.shareText, ORDER_SHARE, label)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
        }
        updateSelectAllItem(menu)
    }

    private fun updateSelectAllItem(menu: Menu) {
        val item = menu.findItem(android.R.id.selectAll)
        if (delegate.canSelectAll) {
            if (item == null) {
                menu.add(Menu.NONE, android.R.id.selectAll, ORDER_SELECT_ALL, android.R.string.selectAll)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            }
        } else if (item != null) {
            menu.removeItem(android.R.id.selectAll)
        }
    }

    @Suppress("DEPRECATION")
    private fun addProcessTextItems(menu: Menu) {
        if (!canProcessText()) return
        val query = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        host.context.packageManager.queryIntentActivities(query, 0).forEachIndexed { index, info ->
            val activityInfo = info.activityInfo ?: return@forEachIndexed
            val samePackage = activityInfo.packageName == host.context.packageName
            val permissionGranted = activityInfo.permission?.let { permission ->
                host.context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } ?: true
            if (!samePackage && (!activityInfo.exported || !permissionGranted)) return@forEachIndexed

            val processIntent = Intent(query)
                .setClassName(activityInfo.packageName, activityInfo.name)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
            menu.add(
                Menu.NONE,
                Menu.NONE,
                PROCESS_TEXT_ORDER + index,
                info.loadLabel(host.context.packageManager),
            )
                .setIntent(processIntent)
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
    }

    private fun isProcessTextItem(item: MenuItem): Boolean =
        item.intent?.action == Intent.ACTION_PROCESS_TEXT

    private fun launchProcessText(intent: Intent): Boolean {
        val selected = delegate.selectedText() ?: return false
        val activity = activityContext() ?: return false
        return runCatching {
            activity.startActivityForResult(
                Intent(intent).putExtra(Intent.EXTRA_PROCESS_TEXT, selected.parcelSafeText()),
                PROCESS_TEXT_REQUEST_CODE,
            )
        }.isSuccess
    }

    private fun shareSelection(): Boolean {
        val selected = delegate.selectedText() ?: return false
        val activity = activityContext() ?: return false
        return runCatching {
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, selected.parcelSafeText())
            activity.startActivity(Intent.createChooser(send, null))
        }.isSuccess
    }

    private fun canShare(): Boolean =
        delegate.hasSelection &&
            activityContext() != null &&
            isDeviceProvisioned() &&
            isFrameworkTextShareSupported()

    private fun canProcessText(): Boolean =
        host.id != View.NO_ID && canShare()

    private fun isDeviceProvisioned(): Boolean =
        Settings.Global.getInt(
            host.context.contentResolver,
            Settings.Global.DEVICE_PROVISIONED,
            0,
        ) != 0

    /**
     * Newer Android releases expose an internal device-configurable gate for TextView sharing.
     * The resource does not exist on older releases (including Android 11), where TextView treats
     * sharing as enabled once the Activity and provisioning checks pass. Looking it up by name
     * preserves both platform behaviors without compiling against a hidden resource identifier.
     */
    private fun isFrameworkTextShareSupported(): Boolean {
        return AndroidTextFrameworkCompat.isTextShareSupported(host.resources)
    }

    /**
     * Android keeps this label private to the framework, but resolves it through the host's
     * Resources so the host configuration supplies the localized text. No private resource ID is
     * compiled into the library, and a framework without the resource has no Share capability.
     */
    private fun resolveSystemShareLabel(): CharSequence? {
        return AndroidTextFrameworkCompat.shareLabel(host.resources)
    }

    private fun activityContext(): Activity? {
        var context: Context? = host.context
        while (context != null) {
            if (context is Activity) return context
            val wrapper = context as? ContextWrapper ?: return null
            val base = wrapper.baseContext
            if (base === context) return null
            context = base
        }
        return null
    }

    private fun clearOwnedTransientState() {
        if (ownsTransientState) {
            host.setHasTransientState(false)
            ownsTransientState = false
        }
    }

    /** Mirrors TextUtils.trimToParcelableSize without depending on its hidden API. */
    private fun String.parcelSafeText(): String {
        if (length <= PARCEL_SAFE_TEXT_LENGTH) return this
        val safeEnd = if (
            this[PARCEL_SAFE_TEXT_LENGTH - 1].isHighSurrogate() &&
            this[PARCEL_SAFE_TEXT_LENGTH].isLowSurrogate()
        ) {
            PARCEL_SAFE_TEXT_LENGTH - 1
        } else {
            PARCEL_SAFE_TEXT_LENGTH
        }
        return substring(0, safeEnd)
    }

    private companion object {
        const val ORDER_COPY = 5
        const val ORDER_SHARE = 7
        const val ORDER_SELECT_ALL = 8
        const val PROCESS_TEXT_ORDER = 100
        const val PROCESS_TEXT_REQUEST_CODE = 100
        const val PARCEL_SAFE_TEXT_LENGTH = 100_000
        const val SELECT_ALL_REFRESH_HIDE_DURATION_MILLIS = 500L
    }
}
