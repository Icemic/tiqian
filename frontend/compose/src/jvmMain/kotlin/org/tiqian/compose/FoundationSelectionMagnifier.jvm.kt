package org.tiqian.compose

import androidx.compose.ui.Modifier

/** Compose Foundation does not provide a static-text magnifier on desktop. */
internal actual fun Modifier.foundationSelectionMagnifier(state: CjkSelectionState): Modifier = this
