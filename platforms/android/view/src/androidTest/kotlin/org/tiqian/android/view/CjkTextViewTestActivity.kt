package org.tiqian.android.view

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

class CjkTextViewTestActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        val locale = localeOverride
        if (locale == null) {
            super.attachBaseContext(newBase)
            return
        }
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(locale)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
    }

    companion object {
        @Volatile
        var localeOverride: Locale? = null
    }
}
