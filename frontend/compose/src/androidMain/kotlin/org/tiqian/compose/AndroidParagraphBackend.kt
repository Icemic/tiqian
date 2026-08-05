package org.tiqian.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import org.tiqian.clreq.ClreqProfile
import org.tiqian.layout.ExplainableStubParagraphLayoutEngine
import org.tiqian.layout.LookaheadLineBreaker
import org.tiqian.shaping.nativefont.AndroidNativeFontMetricsResolver
import org.tiqian.shaping.nativefont.AndroidNativeTextShaper

@Composable
internal actual fun rememberPlatformParagraphMeasurer(profile: ClreqProfile): ParagraphMeasurer {
    val context = LocalContext.current.applicationContext
    return remember(profile, context) {
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
