package org.tiqian.compose

import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.RawFontMetrics
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper

/**
 * `BoundedComposeShapingReuse`: retain exact width-independent shaping results while one default
 * Compose measurer is alive. Width is deliberately absent because it is absent from [ShapingInput];
 * text, source range, resolved style, font decision, and display substitution all remain in the key.
 *
 * This cache is UI-thread confined, matching Compose measurement. It is bounded so a measurer shared
 * by a long document cannot retain every cluster it has ever seen.
 */
internal class BoundedComposeTextShaperCache(
    private val delegate: TextShaper,
    maxEntries: Int = DEFAULT_COMPOSE_SHAPING_CACHE_ENTRIES,
) : TextShaper {
    private val cache = BoundedInsertionCache<ShapingInput, ShapingResult>(maxEntries)

    override fun shape(input: ShapingInput): ShapingResult =
        cache.getOrPut(input) { delegate.shape(input) }
}

/**
 * `BoundedComposeFontMetricsReuse`: font metrics are face/style/size data, not line-width data.
 * Keep exact requests across rebreaks; face-selection text remains part of the key so custom-family
 * fallback cannot accidentally reuse metrics from a different resolved face.
 */
internal class BoundedComposeFontMetricsCache(
    private val delegate: FontMetricsResolver,
    maxEntries: Int = DEFAULT_COMPOSE_METRICS_CACHE_ENTRIES,
) : FontMetricsResolver {
    private val cache = BoundedInsertionCache<FontMetricsRequest, RawFontMetrics>(maxEntries)

    override fun resolve(request: FontMetricsRequest): RawFontMetrics =
        cache.getOrPut(request) { delegate.resolve(request) }
}

private class BoundedInsertionCache<K, V>(maxEntries: Int) {
    private val maxEntries = maxEntries.also { require(it > 0) { "maxEntries must be positive" } }
    private val values = LinkedHashMap<K, V>()

    fun getOrPut(key: K, create: () -> V): V {
        values[key]?.let { return it }
        return create().also { value ->
            if (values.size >= maxEntries) {
                values.remove(values.keys.first())
            }
            values[key] = value
        }
    }
}

private const val DEFAULT_COMPOSE_SHAPING_CACHE_ENTRIES = 512
private const val DEFAULT_COMPOSE_METRICS_CACHE_ENTRIES = 256
