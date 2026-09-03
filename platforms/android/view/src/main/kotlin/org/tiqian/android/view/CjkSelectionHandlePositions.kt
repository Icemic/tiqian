package org.tiqian.android.view

/** Standalone paragraph endpoint token kept opaque to the popup event adapter. */
internal data class CjkStandaloneHandlePosition(
    val offset: Int,
) : CjkSelectionHandlePosition

/** Logical document endpoint token kept opaque to the popup event adapter. */
internal data class CjkDocumentHandlePosition(
    val anchor: CjkDocumentSelectionAnchor,
) : CjkSelectionHandlePosition
