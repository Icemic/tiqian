package org.tiqian.shaping.android

import android.os.Build
import org.tiqian.shaping.TextShaper

/** Selects the strongest public Android text contract available on the running OS. */
fun createAndroidTextShaper(
    typefaceResolver: AndroidTypefaceResolver = SystemAndroidTypefaceResolver(),
): TextShaper = if (Build.VERSION.SDK_INT >= 31) {
    AndroidPaintTextShaper(typefaceResolver)
} else {
    AndroidLegacyTextShaper(typefaceResolver)
}
