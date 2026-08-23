package org.tiqian.shaping.coretext

import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.RawFontMetrics
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper

/**
 * Caches width-independent shaping results across re-layouts — the Core Text peer of the Compose
 * backend's shaping cache. A reflow (window resize / font-size-preserving relayout) keeps the same
 * clusters and only re-breaks lines, so re-shaping every cluster through Core Text (`CTLine` per
 * cluster) each tick is pure waste; the cache turns that into a lookup. Width is deliberately not in
 * the key (it is absent from [ShapingInput]); text + range + style + font decision + display
 * substitution are. Single-thread confined (matches the engine's single-thread layout).
 */
class CachingTextShaper(
    private val delegate: TextShaper,
    maxEntries: Int = DEFAULT_SHAPING_CACHE_ENTRIES,
) : TextShaper {
    private val cache = BoundedInsertionCache<ShapingInput, ShapingResult>(maxEntries)

    override fun shape(input: ShapingInput): ShapingResult =
        cache.getOrPut(input) { delegate.shape(input) }
}

/**
 * Caches font metrics (face/style/size data, not width data) across re-layouts. Face-selection text
 * stays in the key so a custom-family fallback can't reuse another resolved face's metrics.
 */
class CachingFontMetricsResolver(
    private val delegate: FontMetricsResolver,
    maxEntries: Int = DEFAULT_METRICS_CACHE_ENTRIES,
) : FontMetricsResolver {
    private val cache = BoundedInsertionCache<FontMetricsRequest, RawFontMetrics>(maxEntries)

    override fun resolve(request: FontMetricsRequest): RawFontMetrics =
        cache.getOrPut(request) { delegate.resolve(request) }
}

private const val DEFAULT_SHAPING_CACHE_ENTRIES = 512
private const val DEFAULT_METRICS_CACHE_ENTRIES = 256

/** Bounded map with oldest-insertion eviction (no per-access reordering — cheap and good enough here). */
private class BoundedInsertionCache<K, V>(maxEntries: Int) {
    private val maxEntries = maxEntries.also { require(it > 0) { "maxEntries must be positive" } }
    private val values = LinkedHashMap<K, V>()

    fun getOrPut(key: K, create: () -> V): V {
        values[key]?.let { return it }
        return create().also { value ->
            if (values.size >= maxEntries) values.remove(values.keys.first())
            values[key] = value
        }
    }
}
