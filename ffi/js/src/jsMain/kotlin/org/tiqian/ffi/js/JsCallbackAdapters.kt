package org.tiqian.ffi.js

import org.tiqian.font.FontMetricsRequest
import org.tiqian.font.FontMetricsResolver
import org.tiqian.font.RawFontMetrics
import org.tiqian.shaping.ShapingInput
import org.tiqian.shaping.ShapingResult
import org.tiqian.shaping.TextShaper

/**
 * Text shaper adapter that delegates shaping requests across the JavaScript ABI
 * via a synchronous callback returning a JSON string.
 */
internal class JsCallbackTextShaper(
    private val shapeJson: (String) -> String,
) : TextShaper {
    override fun shape(input: ShapingInput): ShapingResult {
        val requestJson = input.appendShapingInputJson()
        val responseJson = shapeJson(requestJson)
        return parseShapingResultJson(responseJson)
    }
}

/**
 * Font metrics resolver adapter that delegates font metrics resolution across the JavaScript ABI
 * via a synchronous callback returning a JSON string.
 */
internal class JsCallbackFontMetricsResolver(
    private val metricsJson: (String) -> String,
) : FontMetricsResolver {
    override fun resolve(request: FontMetricsRequest): RawFontMetrics {
        val requestJson = request.appendFontMetricsRequestJson()
        val responseJson = metricsJson(requestJson)
        return parseRawFontMetricsJson(responseJson)
    }
}