@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package org.tiqian.compose

import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.TextContextMenuItems
import androidx.compose.foundation.text.contextmenu.internal.ProvideDefaultPlatformTextContextMenuProviders
import androidx.compose.foundation.text.contextmenu.modifier.ToolbarRequesterImpl
import androidx.compose.foundation.text.contextmenu.modifier.addTextContextMenuComponentsWithContext
import androidx.compose.foundation.text.contextmenu.modifier.showTextContextMenuOnSecondaryClick
import androidx.compose.foundation.text.contextmenu.modifier.textContextMenuToolbarHandler
import androidx.compose.foundation.text.contextmenu.modifier.translateRootToDestination
import androidx.compose.foundation.text.selection.addPlatformTextContextMenuItems
import androidx.compose.foundation.text.selection.PlatformSelectionBehaviors
import androidx.compose.foundation.text.selection.rememberPlatformSelectionBehaviors
import androidx.compose.foundation.text.selection.SelectedTextType
import androidx.compose.foundation.text.textItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Uses Foundation's current Android text-context-menu provider, which owns the platform ActionMode,
 * PROCESS_TEXT entries, positioning invalidation, and host-provided menu filters/components.
 */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal actual fun FoundationSelectionContextMenuArea(
    state: CjkSelectionState,
    content: @Composable () -> Unit,
) {
    var platformSelectionBehaviors: PlatformSelectionBehaviors? = null
    if (ComposeFoundationFlags.isSmartSelectionEnabled) {
        platformSelectionBehaviors =
            rememberPlatformSelectionBehaviors(SelectedTextType.StaticText, localeList = null)
    }
    val toolbarRequester = remember(state) { ToolbarRequesterImpl() }
    val systemMenu = remember(state, toolbarRequester) {
        CjkSystemContextMenu(
            show = { toolbarRequester.show() },
            hide = { toolbarRequester.hide() },
        )
    }
    DisposableEffect(state, systemMenu) {
        state.attachSystemContextMenu(systemMenu)
        onDispose { state.detachSystemContextMenu(systemMenu) }
    }

    val modifier = Modifier
        .addTextContextMenuComponentsWithContext { context ->
            val textAndSelection = state.contextTextAndSelection()
            addPlatformTextContextMenuItems(
                context = context,
                editable = false,
                text = textAndSelection?.first,
                selection = textAndSelection?.second,
                platformSelectionBehaviors = platformSelectionBehaviors,
            ) {
                separator()
                textItem(
                    resources = context.resources,
                    item = TextContextMenuItems.Copy,
                    enabled = state.hasSelection,
                ) {
                    state.copyFromContextMenu()
                    close()
                }
                textItem(
                    resources = context.resources,
                    item = TextContextMenuItems.SelectAll,
                    enabled = !state.isEntireContainerSelected(),
                ) {
                    state.selectAll()
                    if (!state.isTouchSelection) close()
                }
                separator()
            }
        }
        .showTextContextMenuOnSecondaryClick { clickLocation ->
            state.selectWordAtContainerPositionIfOutsideSelection(clickLocation)
            state.contextTextAndSelection()?.let { (text, selection) ->
                platformSelectionBehaviors?.onShowContextMenu(
                    text = text,
                    selection = selection,
                    secondaryClickLocation = clickLocation,
                )
            }
        }
        .textContextMenuToolbarHandler(
            requester = toolbarRequester,
            onShow = {
                state.contextTextAndSelection()?.let { (text, selection) ->
                    platformSelectionBehaviors?.onShowSelectionToolbar(text, selection)
                }
            },
            computeContentBounds = { destinationCoordinates ->
                val rootBounds = state.selectionContentRectInRoot()
                    ?: return@textContextMenuToolbarHandler null
                val containerCoordinates = state.contextMenuContainerCoordinates()
                    ?: return@textContextMenuToolbarHandler null
                translateRootToDestination(
                    rootContentBounds = rootBounds,
                    localCoordinates = containerCoordinates,
                    destinationCoordinates = destinationCoordinates,
                )
            },
        )

    ProvideDefaultPlatformTextContextMenuProviders(modifier, content)
}
