@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.tiqian.compose

import androidx.compose.foundation.magnifier
import androidx.compose.foundation.text.selection.animatedSelectionMagnifier
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize

/** Android's real text-default platform magnifier, driven by Tiqian selection geometry. */
internal actual fun Modifier.foundationSelectionMagnifier(state: CjkSelectionState): Modifier =
    composed {
        val density = LocalDensity.current
        var magnifierSize by remember { mutableStateOf(IntSize.Zero) }
        animatedSelectionMagnifier(
            magnifierCenter = { state.magnifierCenter(magnifierSize.width) },
            platformMagnifier = { animatedCenter ->
                Modifier.magnifier(
                    sourceCenter = { animatedCenter() },
                    onSizeChanged = { size ->
                        magnifierSize = with(density) {
                            IntSize(size.width.roundToPx(), size.height.roundToPx())
                        }
                    },
                    useTextDefault = true,
                )
            },
        )
    }
