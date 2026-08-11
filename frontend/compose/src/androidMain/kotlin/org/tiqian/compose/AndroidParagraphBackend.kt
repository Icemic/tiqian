package org.tiqian.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.tiqian.clreq.ClreqProfile
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.shaping.android.nativefont.AndroidNativeFontMetricsResolver
import org.tiqian.shaping.android.nativefont.AndroidNativeTextShaper
import org.tiqian.shaping.android.nativefont.TiqianAndroidFontBackend

@Composable
internal actual fun rememberPlatformParagraphMeasurer(profile: ClreqProfile): ParagraphMeasurer {
    val context = LocalContext.current.applicationContext
    val fontCatalogRevision = remember(context) {
        mutableLongStateOf(TiqianAndroidFontBackend.catalogRevision(context))
    }
    DisposableEffect(context) {
        val registration = TiqianAndroidFontBackend.addCatalogRevisionListener(context) { revision ->
            fontCatalogRevision.longValue = revision
        }
        onDispose(registration::close)
    }
    return remember(profile, context, fontCatalogRevision.longValue) {
        ParagraphMeasurer(
            ExplainableStubParagraphLayoutEngine(
                lineBreaker = LookaheadLineBreaker(),
                textShaper = BoundedComposeTextShaperCache(AndroidNativeTextShaper(context)),
                fontMetricsResolver = BoundedComposeFontMetricsCache(AndroidNativeFontMetricsResolver(context)),
                clreqProfileResolver = { profile },
            ),
        )
    }
}
