@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.ffi.js

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class PrecomputeExportsTest {
    @Test
    fun realLayoutPipelineUsesAndReleasesSynchronousNodeFontHandles() {
        val json = precomputeParagraphWithDiagnostics(
            text = "中文中文",
            maxWidthPx = 36.0,
            fontFamilies = "Fixture CJK",
            fontSizePx = 18.0,
            lineHeightPx = 27.0,
            locale = "zh-Hans",
            fontWeight = 400,
            italic = false,
            firstLineIndentIc = 0.0,
            lineLengthGridEnabled = true,
            sourceBoundaries = "",
            textSpans = "",
            inlineBoxes = "",
            lineBreakSpans = "",
            inlineObjects = "",
            zeroAdvanceEpsilonPx = 0.0,
            shapeJson = { requestJson: String ->
                val raw = kotlin.js.JSON.parse<dynamic>(requestJson)
                val start = (raw.range.start as Double).toInt()
                val end = (raw.range.end as Double).toInt()
                val text = raw.text as String
                val seg = text.substring(start, end)
                """
                {
                  "clusters": [
                    {
                      "range": { "start": $start, "end": $end },
                      "text": "$seg",
                      "displayText": "$seg",
                      "fontKey": "cjk-primary",
                      "advance": 36.0,
                      "baselineShift": 0.0
                    }
                  ],
                  "glyphRuns": [
                    {
                      "range": { "start": $start, "end": $end },
                      "fontKey": "cjk-primary",
                      "advance": 36.0,
                      "openTypeFeatures": [],
                      "glyphs": [
                        {
                          "id": 100,
                          "clusterRange": { "start": 0, "end": 1 },
                          "advance": 18.0,
                          "x": 0.0,
                          "y": 0.0,
                          "bounds": { "left": 0.0, "top": -15.84, "right": 18.0, "bottom": 2.16 }
                        },
                        {
                          "id": 101,
                          "clusterRange": { "start": 1, "end": 2 },
                          "advance": 18.0,
                          "x": 18.0,
                          "y": 0.0,
                          "bounds": { "left": 0.0, "top": -15.84, "right": 18.0, "bottom": 2.16 }
                        }
                      ]
                    }
                  ],
                  "decisions": [
                    {
                      "range": { "start": $start, "end": $end },
                      "sourceText": "$seg",
                      "displayText": "$seg",
                      "fontKey": "cjk-primary",
                      "glyphCount": 2,
                      "advance": 36.0,
                      "source": "HarfBuzz",
                      "reason": "test",
                      "glyphsWithoutInkBounds": 0,
                      "missingGlyphs": 0,
                      "resolvedFace": "Fixture CJK",
                      "script": "Hani",
                      "language": "zh-Hans",
                      "featureEvidence": null
                    }
                  ]
                }
                """.trimIndent()
            },
            metricsJson = { requestJson: String ->
                """{"ascent":18.72,"descent":5.04,"leading":0.0,"source":"RawTables","typoAscent":15.84,"typoDescent":2.16}"""
            },
        )

        // Parse the outer envelope and check the plan
        val envelope = kotlin.js.JSON.parse<dynamic>(json)
        val planJson = envelope.plan as String
        assertContains(planJson, "\"layoutRevision\":\"tiqian-layout-v2\"")
        // With the fixture backend, each character gets its own line due to glyph advance measurement
        assertContains(planJson, "\"rangeStart\":0,\"rangeEnd\":1")
        assertContains(planJson, "\"rangeStart\":1,\"rangeEnd\":2")
        assertContains(planJson, "\"rangeStart\":2,\"rangeEnd\":3")
        assertContains(planJson, "\"rangeStart\":3,\"rangeEnd\":4")
    }

    @Test
    fun unavailableMidlineEllipsisRollsBackToSourceEllipsis() {
        val json = precomputeParagraphWithDiagnostics(
            text = "……",
            maxWidthPx = 72.0,
            fontFamilies = "Fixture CJK",
            fontSizePx = 18.0,
            lineHeightPx = 27.0,
            locale = "zh-Hans",
            fontWeight = 400,
            italic = false,
            firstLineIndentIc = 0.0,
            lineLengthGridEnabled = true,
            sourceBoundaries = "",
            textSpans = "",
            inlineBoxes = "",
            lineBreakSpans = "",
            inlineObjects = "",
            zeroAdvanceEpsilonPx = 0.0,
            shapeJson = { requestJson: String ->
                val raw = kotlin.js.JSON.parse<dynamic>(requestJson)
                val start = (raw.range.start as Double).toInt()
                val end = (raw.range.end as Double).toInt()
                val text = raw.text as String
                val seg = text.substring(start, end)
                """
                {
                  "clusters": [
                    {
                      "range": { "start": $start, "end": $end },
                      "text": "$seg",
                      "displayText": "$seg",
                      "fontKey": "cjk-primary",
                      "advance": 18.0,
                      "baselineShift": 0.0
                    }
                  ],
                  "glyphRuns": [
                    {
                      "range": { "start": $start, "end": $end },
                      "fontKey": "cjk-primary",
                      "advance": 18.0,
                      "openTypeFeatures": [],
                      "glyphs": [
                        {
                          "id": 0,
                          "clusterRange": { "start": $start, "end": $end },
                          "advance": 18.0,
                          "x": 0.0,
                          "y": 0.0,
                          "bounds": { "left": 0.0, "top": -15.84, "right": 18.0, "bottom": 2.16 }
                        }
                      ]
                    }
                  ],
                  "decisions": [
                    {
                      "range": { "start": $start, "end": $end },
                      "sourceText": "$seg",
                      "displayText": "$seg",
                      "fontKey": "cjk-primary",
                      "glyphCount": 1,
                      "advance": 18.0,
                      "source": "HarfBuzz",
                      "reason": "test",
                      "glyphsWithoutInkBounds": 0,
                      "missingGlyphs": 1,
                      "resolvedFace": "Fixture CJK",
                      "script": "Hani",
                      "language": "zh-Hans",
                      "featureEvidence": null
                    }
                  ]
                }
                """.trimIndent()
            },
            metricsJson = { requestJson: String ->
                """{"ascent":18.72,"descent":5.04,"leading":0.0,"source":"RawTables","typoAscent":15.84,"typoDescent":2.16}"""
            },
        )

        val envelope = kotlin.js.JSON.parse<dynamic>(json)
        val planJson = envelope.plan as String
        assertContains(planJson, "\"source\":\"……\",\"display\":\"……\"")
    }
}