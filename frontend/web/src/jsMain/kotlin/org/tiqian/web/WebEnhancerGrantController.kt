package org.tiqian.web

import org.w3c.dom.HTMLElement

/**
 * GrantController: the coordinator's grant, one plain JS object per grant
 * addressed to one recipient (ADR 0039). Every member is a value-copied stop
 * term; the runtime validates [root] and [generation], then asks
 * [shouldStop] after each paragraph. [deadline] and [quota] are the terms
 * the closure was built from; they travel with the grant so it stays
 * self-describing, and no live coordinator state crosses the boundary.
 */
external interface GrantController {
    val root: HTMLElement
    val generation: Int
    val deadline: Double
    val quota: Int
    fun shouldStop(processedInSlice: Int): Boolean
}

/**
 * GrantAdmission: the stop question of one granted slice, asked once per
 * paragraph boundary (ADR 0039). The page coordinator constructs a plain
 * controller object per grant carrying value-copied stop terms: the
 * recipient root, the job generation the grant addresses, a deadline
 * already converted into the Date.now() domain, and a paragraph quota. The
 * controller's shouldStop closure captures only those numbers, never live
 * coordinator state, so no global page state crosses the grant. The layout
 * loop owns no clock, policy, or identity; it asks and obeys, and commits
 * at least one paragraph per slice because the question runs after an item.
 */
internal fun interface GrantAdmission {
    public fun shouldStop(processedInSlice: Int): Boolean
}
