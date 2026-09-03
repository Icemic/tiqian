package org.tiqian.android.view

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Resources
import kotlin.math.roundToInt

/**
 * Deliberately narrow bridge for TextView behavior that Android does not expose as public API.
 *
 * Resource-name lookups live only here, are capability checked, and always have a behaviorally
 * safe fallback. Instrumented native-TextView oracle tests cover the menu contract on each target
 * API; callers must not add further private framework lookups outside this boundary.
 */
internal object AndroidTextFrameworkCompat {
    @SuppressLint("DiscouragedApi")
    fun selectionHandleMinimumSize(context: Context): Int {
        val resourceId = context.resources.getIdentifier(
            "text_handle_min_size",
            "dimen",
            "android",
        )
        if (resourceId != 0) return context.resources.getDimensionPixelSize(resourceId)
        return (HANDLE_MINIMUM_SIZE_FALLBACK_DP * context.resources.displayMetrics.density)
            .roundToInt()
    }

    @SuppressLint("DiscouragedApi")
    fun isTextShareSupported(resources: Resources): Boolean {
        val resourceId = resources.getIdentifier(
            "config_textShareSupported",
            "bool",
            "android",
        )
        return resourceId == 0 || resources.getBoolean(resourceId)
    }

    @SuppressLint("DiscouragedApi")
    fun shareLabel(resources: Resources): CharSequence? {
        val resourceId = resources.getIdentifier("share", "string", "android")
        return resourceId.takeIf { it != 0 }?.let(resources::getText)
    }

    private const val HANDLE_MINIMUM_SIZE_FALLBACK_DP = 40f
}
