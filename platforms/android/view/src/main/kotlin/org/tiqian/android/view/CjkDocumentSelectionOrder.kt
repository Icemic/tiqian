package org.tiqian.android.view

/** Logical fragment order and binding validation, independent from interaction presentation. */
internal class CjkDocumentSelectionOrder(
    private val host: CjkTextSurface,
    private val registrations: LinkedHashMap<CjkTextView, Any>,
) {
    var document: CjkSelectionDocument? = null
        private set

    private var attachedOrderCache: List<CjkTextView>? = null
    private var attachedOrderIndexCache: Map<Any, Int>? = null

    fun setDocument(value: CjkSelectionDocument?) {
        document = value
        invalidate()
    }

    fun validateDocument(next: CjkSelectionDocument?, candidates: Collection<CjkTextView>) {
        if (next == null) return
        val keys = HashSet<Any>()
        candidates.forEach { view ->
            if (!view.textIsSelectable) return@forEach
            val key = view.selectionDocumentKey ?: return@forEach
            val index = next.indexByKey[key]
                ?: error("CjkTextView selectionDocumentKey is absent from CjkSelectionDocument: $key")
            requireFragmentMatches(next.fragments[index], view.content, key)
            require(keys.add(key)) {
                "Only one attached CjkTextView may expose document fragment key: $key"
            }
        }
    }

    fun validateRegistration(view: CjkTextView) {
        val doc = document ?: return
        val key = view.selectionDocumentKey ?: return
        val index = doc.indexByKey[key]
            ?: error("CjkTextView selectionDocumentKey is absent from CjkSelectionDocument: $key")
        requireFragmentMatches(doc.fragments[index], view.content, key)
    }

    fun validateProspectiveBinding(view: CjkTextView, key: Any, content: CjkTextContent) {
        document?.let { doc ->
            val index = doc.indexByKey[key]
                ?: error("CjkTextView selectionDocumentKey is absent from CjkSelectionDocument: $key")
            requireFragmentMatches(doc.fragments[index], content, key)
        }
        require(registrations.none { (other, otherKey) -> other !== view && otherKey == key }) {
            "Only one attached CjkTextView may expose document fragment key: $key"
        }
    }

    fun validateContent(view: CjkTextView, content: CjkTextContent) {
        if (view !in registrations) return
        val doc = document ?: return
        val key = registrations.getValue(view)
        requireFragmentMatches(doc.fragments[doc.indexByKey.getValue(key)], content, key)
    }

    fun logicalFragments(): List<CjkSelectionDocumentFragment> = document?.fragments
        ?: orderedViews().map { view ->
            CjkSelectionDocumentFragment(registrations.getValue(view), view.content)
        }

    fun orderedViews(): List<CjkTextView> {
        if (document != null) return registrations.keys.sortedBy { orderOf(registrations.getValue(it)) }
        return orderedViewsWithoutDocument()
    }

    fun normalize(
        anchor: CjkDocumentSelectionAnchor,
        extent: CjkDocumentSelectionAnchor,
    ): Pair<CjkDocumentSelectionAnchor, CjkDocumentSelectionAnchor>? {
        if (orderOf(anchor.key) == Int.MAX_VALUE || orderOf(extent.key) == Int.MAX_VALUE) return null
        return if (compareAnchors(anchor, extent) <= 0) anchor to extent else extent to anchor
    }

    fun compareAnchors(left: CjkDocumentSelectionAnchor, right: CjkDocumentSelectionAnchor): Int =
        if (left.key == right.key) left.offset.compareTo(right.offset)
        else orderOf(left.key).compareTo(orderOf(right.key))

    fun orderOf(key: Any): Int = document?.indexByKey?.get(key)
        ?: attachedOrderIndex()[key]
        ?: Int.MAX_VALUE

    fun keyFor(view: CjkTextView): Any? = if (document == null) view else view.selectionDocumentKey

    fun invalidate() {
        attachedOrderCache = null
        attachedOrderIndexCache = null
    }

    private fun orderedViewsWithoutDocument(): List<CjkTextView> {
        if (document != null) return registrations.keys.toList()
        attachedOrderCache?.let { return it }
        val hostLocation = IntArray(2).also(host::getLocationOnScreen)
        return registrations.keys.sortedWith(compareBy<CjkTextView> {
            val location = IntArray(2).also(it::getLocationOnScreen)
            location[1] - hostLocation[1]
        }.thenBy {
            val location = IntArray(2).also(it::getLocationOnScreen)
            location[0] - hostLocation[0]
        }).also { ordered ->
            attachedOrderCache = ordered
            attachedOrderIndexCache = ordered.mapIndexed { index, view ->
                registrations.getValue(view) to index
            }.toMap()
        }
    }

    private fun attachedOrderIndex(): Map<Any, Int> {
        attachedOrderIndexCache?.let { return it }
        orderedViewsWithoutDocument()
        return attachedOrderIndexCache.orEmpty()
    }

    private fun requireFragmentMatches(
        fragment: CjkSelectionDocumentFragment,
        content: CjkTextContent,
        key: Any,
    ) {
        require(fragment.text == content.content.text) {
            "CjkTextView text differs from CjkSelectionDocument fragment: $key"
        }
        require(fragment.rubySpans == content.rubySpans) {
            "CjkTextView ruby spans differ from CjkSelectionDocument fragment: $key"
        }
        require(fragment.inlineObjects == content.inlineObjects) {
            "CjkTextView inline objects differ from CjkSelectionDocument fragment: $key"
        }
    }
}
