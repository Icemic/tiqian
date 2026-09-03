package org.tiqian.android.view

/**
 * One independently releasable keep-alive generation.
 *
 * Releasing an older handle must never release a newer retention for a rebound holder.
 */
fun interface CjkSelectionRetentionHandle {
    fun release()
}

/**
 * Optional virtualized-host capability for retaining an active gesture endpoint.
 *
 * A returned handle means that [key] will not be recycled or rebound until that handle is
 * released. A host that cannot provide this guarantee should omit the capability; selection then
 * remains key-based while detached endpoint geometry and handles stay hidden.
 */
fun interface CjkSelectionRetentionHost {
    fun retain(key: Any): CjkSelectionRetentionHandle
}
