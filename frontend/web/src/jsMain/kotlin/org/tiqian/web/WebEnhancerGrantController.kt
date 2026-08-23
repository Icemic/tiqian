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
