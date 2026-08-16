@file:Suppress("DEPRECATION")

package org.tiqian.compose

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Remembers state for one [CjkSelectionContainer]. */
@Composable
fun rememberCjkSelectionState(): CjkSelectionState = remember { CjkSelectionState() }

/**
 * Enables source-faithful static-text selection for descendant `CjkText` surfaces. Unlike
 * Compose's `SelectionContainer`, this container consumes Tiqian [org.tiqian.core.LayoutResult]
 * geometry directly, so line breaks, punctuation glue, ruby expansion, and copied source ranges do
 * not pass through a hidden second text layout. [document] supplies stable logical fragments for a
 * virtualized document; selection and copying then survive off-screen disposal.
 *
 * When [content] uses `Modifier.verticalScroll`, pass that modifier's [scrollState] here as well.
 * A mouse, touch, or handle drag then scrolls inside the edge bands after the gesture has crossed
 * touch slop. `null` disables auto-scroll.
 */
@Composable
fun CjkSelectionContainer(
    modifier: Modifier = Modifier,
    state: CjkSelectionState = rememberCjkSelectionState(),
    scrollState: ScrollState? = null,
    autoScrollEdgeSize: Dp = 48.dp,
    autoScrollMaxVelocity: Dp = 1_200.dp,
    document: CjkSelectionDocument? = null,
    content: @Composable () -> Unit,
) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    val textToolbar = LocalTextToolbar.current
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val colors = LocalTextSelectionColors.current
    SideEffect {
        state.attach(
            clipboardManager,
            textToolbar,
            hapticFeedback,
            colors.backgroundColor.toArgb(),
            foundationSelectionToolbarHandleClearancePx(density),
            document,
        )
    }
    CjkSelectionAutoScrollEffect(
        state = state,
        scrollState = scrollState,
        edgeSize = autoScrollEdgeSize,
        maxVelocity = autoScrollMaxVelocity,
    )

    Box(
        modifier = modifier
            .foundationClearSelectionOnTap(state)
            .foundationSelectionMagnifier(state)
            .onGloballyPositioned(state::updateContainerCoordinates)
            .focusRequester(state.focusRequester)
            .onFocusChanged { focus ->
                if (!focus.hasFocus && state.hasSelection) state.clearSelection()
            }
            .onKeyEvent(state::handleCopyKeyEvent)
            // A low-level focus target is enough for keyboard copy/Escape. `focusable()` also
            // publishes an otherwise empty accessibility node around every descendant text node.
            .focusTarget(),
    ) {
        FoundationSelectionContextMenuArea(state) {
            CompositionLocalProvider(LocalCjkSelectionState provides state) {
                content()
            }
            if (state.isTouchSelection && state.hasSelection) {
                CjkSelectionHandle(state, isStart = true)
                CjkSelectionHandle(state, isStart = false)
            }
        }
    }

    DisposableEffect(state) {
        onDispose(state::detach)
    }
}

/** Prevents descendant `CjkText` nodes from registering in an outer [CjkSelectionContainer]. */
@Composable
fun CjkDisableSelection(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCjkSelectionState provides null, content = content)
}

/**
 * Associates one descendant [CjkText] with a stable [CjkSelectionDocumentFragment] key.
 */
@Composable
fun CjkSelectionScope(
    ownerKey: Any,
    retentionKey: Any = ownerKey,
    content: @Composable () -> Unit,
) {
    val scope = remember(ownerKey, retentionKey) {
        CjkSelectionScopeInfo(ownerKey, retentionKey)
    }
    CompositionLocalProvider(LocalCjkSelectionScope provides scope, content = content)
}

@Composable
private fun CjkSelectionHandle(
    state: CjkSelectionState,
    isStart: Boolean,
) {
    FoundationSelectionHandle(
        offsetProvider = {
            (if (isStart) state.startHandlePosition else state.endHandlePosition)
                ?: Offset.Unspecified
        },
        isStartHandle = isStart,
        handlesCrossed = state.handlesCrossed,
        lineHeight = if (isStart) state.startHandleLineHeight else state.endHandleLineHeight,
        modifier = Modifier.foundationSelectionHandleGestures(state, isStart),
    )
}

internal val LocalCjkSelectionState = compositionLocalOf<CjkSelectionState?> { null }
internal val LocalCjkSelectionScope = compositionLocalOf<CjkSelectionScopeInfo?> { null }
