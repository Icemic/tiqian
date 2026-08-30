package org.tiqian.layout

import org.tiqian.core.LayoutConstraints
import org.tiqian.core.LayoutInput
import org.tiqian.core.LayoutResult
import org.tiqian.core.ParagraphStyle
import org.tiqian.core.TextRange
import org.tiqian.core.TextSpan
import org.tiqian.core.TextStyle
import org.tiqian.core.TiqianTextContent
import org.tiqian.core.ic
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext
import org.tiqian.test.trace.TestTraceRecorder
import org.tiqian.test.trace.assertFailsWith
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

// A lone surrogate written inside a string literal is replaced with '?' when
// the JS test bundle re-serializes its sources, so inputs that carry one are
// built from char codes at runtime to keep the code unit intact everywhere.
private fun surrogateText(vararg codes: Int): String =
    CharArray(codes.size) { codes[it].toChar() }.concatToString()

class ContextualDashEllipsisRoleResolverCoverageTest {
    private val testTrace = TestTraceRecorder("ContextualDashEllipsisRoleResolverCoverageTest")
    private val resolver = ContextualDashEllipsisRoleResolver()

    @Test
    fun parentheticalPairWithOnlyLeftOuterScriptTakesTheLeftRole() {
        testTrace.section("parentheticalPairWithOnlyLeftOuterScriptTakesTheLeftRole")
        val decisions = resolver.resolve("中文——word——", FontRoleContext(locale = "zh-Hans"))

        assertEquals(2, decisions.size)
        assertTrue(
            decisions.all {
                it.role == FontRole.CjkPunctuation &&
                    it.source == "ParentheticalDashPairContext" &&
                    it.reason.startsWith("only-left-outer-script")
            },
            decisions.toString(),
        )
    }

    @Test
    fun parentheticalPairWithOnlyRightOuterScriptTakesTheRightRole() {
        testTrace.section("parentheticalPairWithOnlyRightOuterScriptTakesTheRightRole")
        val decisions = resolver.resolve("——word——中文", FontRoleContext(locale = "zh-Hans"))

        assertEquals(2, decisions.size)
        assertTrue(
            decisions.all {
                it.role == FontRole.CjkPunctuation &&
                    it.source == "ParentheticalDashPairContext" &&
                    it.reason.startsWith("only-right-outer-script")
            },
            decisions.toString(),
        )
    }

    @Test
    fun parentheticalPairWithoutOuterScriptFallsBackToParagraphLanguage() {
        testTrace.section("parentheticalPairWithoutOuterScriptFallsBackToParagraphLanguage")
        val decisions = resolver.resolve("——word——", FontRoleContext(locale = "zh-Hans"))

        assertEquals(2, decisions.size)
        assertTrue(
            decisions.all {
                it.role == FontRole.CjkPunctuation &&
                    it.source == "ParagraphLanguageDashEllipsisContext" &&
                    it.reason.startsWith("parenthetical-pair-no-outer-context")
            },
            decisions.toString(),
        )
    }

    @Test
    fun forwardPassWalkerArmsRunBeforeTheClassifierRejectsLoneSurrogates() {
        testTrace.section("forwardPassWalkerArmsRunBeforeTheClassifierRejectsLoneSurrogates")
        // The forward index pass builds scalar starts before classification,
        // so each malformed input walks one compat arm and then throws.
        // A high surrogate at the paragraph end takes the end-of-text arm.
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(surrogateText(0x2014, 0xD83D))
        }
        // A high surrogate followed by a non-low scalar below and above the
        // low-surrogate range takes both directions of the pairing check.
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(surrogateText(0xD83D, 0x2014))
        }
        assertFailsWith<IllegalArgumentException> {
            resolver.resolve(surrogateText(0xD83D, 0xFFFD, 0x2014))
        }
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}

class ContextualRoleExtensionCoverageTest {
    private val testTrace = TestTraceRecorder("ContextualRoleExtensionCoverageTest")

    @Test
    fun contextualRoleExtensionsWrapOutsideThePipeline() {
        testTrace.section("contextualRoleExtensionsWrapOutsideThePipeline")
        val base = CjkFontRoleClassifier()
        val context = FontRoleContext(locale = "zh-Hans")

        // No contextual marks in the text: each extension returns the
        // receiver unchanged. The context-free calls also execute the
        // default-argument expressions.
        assertSame(base, base.withContextualDashEllipsisRoles("中文", context))
        assertSame(base, base.withContextualQuoteRoles("中文", context))
        assertSame(base, base.withContextualDashEllipsisRoles("中文"))
        assertSame(base, base.withContextualQuoteRoles("中文"))

        // With marks the wrappers resolve the run role directly and delegate
        // every other range to the base classifier.
        val dashAware = base.withContextualDashEllipsisRoles("中文—English", context)
        assertEquals(FontRole.CjkPunctuation, dashAware.classify("中文—English", TextRange(2, 3), context))
        assertEquals(
            base.classify("中文—English", TextRange(0, 1), context),
            dashAware.classify("中文—English", TextRange(0, 1), context),
        )

        val quoteAware = base.withContextualQuoteRoles("中a“b”c文", context)
        assertEquals(FontRole.LatinText, quoteAware.classify("中a“b”c文", TextRange(2, 3), context))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}

class ContextualDashEllipsisClusterCoverageTest {
    private val testTrace = TestTraceRecorder("ContextualDashEllipsisClusterCoverageTest")
    private val engine = ExplainableStubParagraphLayoutEngine()

    @Test
    fun latinDashRunAtParagraphEndStaysOneCluster() {
        testTrace.section("latinDashRunAtParagraphEndStaysOneCluster")
        val result = layout("End——")

        val decision = result.debug.fontDecisions.single { it.sourceText.contains("—") }
        assertEquals("——", decision.sourceText)
        assertEquals(FontRole.LatinText.name, decision.role)
    }

    @Test
    fun styleSpanInsideLatinDashRunSplitsTheCluster() {
        testTrace.section("styleSpanInsideLatinDashRunSplitsTheCluster")
        // The sized span puts a boundary between the two dashes, so the
        // coalesce loop stops at the first mark and each forms its own
        // cluster.
        val result = layout(
            "A——B",
            spans = listOf(TextSpan(TextRange(2, 3), TextStyle(fontWeight = 700))),
        )

        val dashDecisions = result.debug.fontDecisions.filter { it.sourceText == "—" }
        assertEquals(2, dashDecisions.size)
        assertTrue(dashDecisions.all { it.role == FontRole.LatinText.name })
    }

    private fun layout(
        text: String,
        locale: String = "zh-Hans",
        spans: List<TextSpan> = emptyList(),
    ): LayoutResult = engine.layout(
        LayoutInput(
            content = TiqianTextContent(text, spans = spans),
            textStyle = TextStyle(locale = locale),
            paragraphStyle = ParagraphStyle(firstLineIndent = 0.ic),
            constraints = LayoutConstraints(maxWidth = 1000f),
        ),
    )

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
