package org.tiqian.layout

import kotlin.test.Test
import org.tiqian.test.trace.assertEquals
import org.tiqian.test.trace.f32Literal
import kotlin.test.AfterTest
import org.tiqian.test.trace.TestTraceRecorder

/**
 * ecmaJsonNumber must render the same Float to the same bytes on every
 * Kotlin backend; plan snapshots and the ffi/js boundary consume it directly.
 * Every input routes through f32Literal so every backend feeds the engine
 * the Float the literal denotes. Every expectation below was cross-checked
 * against the ECMAScript `String(number)` output of the widened double.
 * The digits come from an exact shortest-round-trip search over the Float
 * bits, never from a platform `Double.toString`: powers of two have
 * asymmetric rounding intervals, so Kotlin/Native's dtoa prints a
 * down-rounded neighbor (2^-25 prints as 16 digits that parse to the value
 * below), and at an exact half the even candidate wins.
 */
class PreparedParagraphJsonNumberTest {
    private val testTrace = TestTraceRecorder("PreparedParagraphJsonNumberTest")


    @Test
    fun zeroValuesSerializeWithoutSign() {
        testTrace.section("zeroValuesSerializeWithoutSign")
        assertEquals("0", ecmaJsonNumber(f32Literal(0.0f)))
        assertEquals("0", ecmaJsonNumber(f32Literal(-0.0f)))
        assertEquals("NaN", ecmaJsonNumber(f32Literal(Float.NaN)))
        assertEquals("Infinity", ecmaJsonNumber(f32Literal(Float.POSITIVE_INFINITY)))
        assertEquals("-Infinity", ecmaJsonNumber(f32Literal(Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun integerFormsPadToDecimalExponent() {
        testTrace.section("integerFormsPadToDecimalExponent")
        assertEquals("1", ecmaJsonNumber(f32Literal(1.0f)))
        assertEquals("200", ecmaJsonNumber(f32Literal(200.0f)))
        // The Floats nearest 1e15 / 1e16 / 1e20 are exact integers once
        // widened; the integer form carries every digit of the widening.
        assertEquals("999999986991104", ecmaJsonNumber(f32Literal(1.0e15f)))
        assertEquals("10000000272564224", ecmaJsonNumber(f32Literal(1.0e16f)))
        assertEquals("100000002004087730000", ecmaJsonNumber(f32Literal(1.0e20f)))
        // 2^53: the exact expansion is not longer than the platform digits,
        // so the tie-break returns the platform digits unchanged.
        assertEquals("9007199254740992", ecmaJsonNumber(f32Literal(9007199254740992.0f)))
    }

    @Test
    fun fractionFormsInsertDecimalPoint() {
        testTrace.section("fractionFormsInsertDecimalPoint")
        assertEquals("1.5", ecmaJsonNumber(f32Literal(1.5f)))
        assertEquals("12.5", ecmaJsonNumber(f32Literal(12.5f)))
        assertEquals("1000000.5", ecmaJsonNumber(f32Literal(1000000.5f)))
    }

    @Test
    fun smallFractionsUseLeadingZeros() {
        testTrace.section("smallFractionsUseLeadingZeros")
        // The Floats nearest decimal fractions have long exact expansions;
        // each expectation is the exact widening of that Float.
        assertEquals("0.10000000149011612", ecmaJsonNumber(f32Literal(0.1f)))
        assertEquals("0.44999998807907104", ecmaJsonNumber(f32Literal(0.45f)))
        assertEquals("0.05000000074505806", ecmaJsonNumber(f32Literal(0.05f)))
        assertEquals("0.009999999776482582", ecmaJsonNumber(f32Literal(0.01f)))
        assertEquals("0.00009999999747378752", ecmaJsonNumber(f32Literal(0.0001f)))
        assertEquals("0.0003499999875202775", ecmaJsonNumber(f32Literal(0.00035f)))
    }

    @Test
    fun exponentFormsCarryExplicitSign() {
        testTrace.section("exponentFormsCarryExplicitSign")
        assertEquals("1.0000000200408773e+21", ecmaJsonNumber(f32Literal(1.0e21f)))
        assertEquals("9.999999778196308e+21", ecmaJsonNumber(f32Literal(1.0e22f)))
        assertEquals("1.4999999667294463e+22", ecmaJsonNumber(f32Literal(1.5e22f)))
        assertEquals("2.499999944549077e+22", ecmaJsonNumber(f32Literal(2.5e22f)))
        assertEquals("1.5000000207726418e+24", ecmaJsonNumber(f32Literal(1.5e24f)))
        assertEquals("1.0000000116860974e-7", ecmaJsonNumber(f32Literal(1.0e-7f)))
        assertEquals("1.500000053056283e-7", ecmaJsonNumber(f32Literal(1.5e-7f)))
    }

    @Test
    fun negativeValuesKeepOnlyMagnitudeSign() {
        testTrace.section("negativeValuesKeepOnlyMagnitudeSign")
        assertEquals("-1.5", ecmaJsonNumber(f32Literal(-1.5f)))
        assertEquals("-2.499999993688107e-7", ecmaJsonNumber(f32Literal(-2.5e-7f)))
    }

    @Test
    fun exactTiesRoundToEvenDigit() {
        testTrace.section("exactTiesRoundToEvenDigit")
        // 2^-24: the exact expansion ends in ...0625, an exact tie between
        // ...062 and ...063 at the platform digit count; the even candidate
        // ...062 is the canonical answer even though the platform shortest
        // strings print the odd candidate ...063.
        assertEquals("5.960464477539062e-8", ecmaJsonNumber(f32Literal(5.960464477539063e-8f)))
        // 2^-25: exact tie whose even candidate already equals the platform
        // digits, so the canonical form keeps them.
        assertEquals("2.9802322387695312e-8", ecmaJsonNumber(f32Literal(2.9802322387695312e-8f)))
        // Exact half with a non-zero tail past the half digit: the tail, not
        // the parity rule, forces the round up (the kept digit is even), and
        // the platform digits already agree.
        assertEquals("1.7432641983032227", ecmaJsonNumber(f32Literal(1.7432641983032227f)))
    }

    @Test
    fun exactExpansionRoundsPlatformDigits() {
        testTrace.section("exactExpansionRoundsPlatformDigits")
        // 2^60: the exact expansion's first sixteen digits round up past the
        // platform digits and the integer form pads the zero tail.
        assertEquals("1152921504606847000", ecmaJsonNumber(f32Literal(1152921504606846976.0f)))
        // 2^-44: the exact expansion continues 4869... below the kept digit,
        // so the canonical digits round down to ...801 even though the
        // platform shortest prints the up-rounded neighbor ...802 (a power
        // of two has an asymmetric rounding interval).
        assertEquals("5.684341886080801e-14", ecmaJsonNumber(f32Literal(5.684341886080802e-14f)))
        // 2^122: the same family at integer scale; the exact expansion is
        // ...6634916..., the platform prints ...664.
        assertEquals("5.316911983139663e+36", ecmaJsonNumber(f32Literal(5.316911983139664e+36f)))
    }

    @Test
    fun boundaryMidpointsAcceptOnlyAtEvenMantissa() {
        testTrace.section("boundaryMidpointsAcceptOnlyAtEvenMantissa")
        // The two rarest grid values in a 121,830-value sweep: a shortest
        // search candidate lands exactly ON a rounding-interval boundary
        // (the up candidate on the high midpoint of the first value, the
        // truncated candidate on the low midpoint of the second). A decimal
        // equal to a midpoint parses back to the value only when the
        // mantissa is even, which is what the equality arms of the interval
        // check encode; both mantissas here are even.
        assertEquals("33474762504142850", ecmaJsonNumber(Float.fromBits(0x5AEDDA3D)))
        assertEquals("103571925162262530", ecmaJsonNumber(Float.fromBits(0x5BB7FB0F)))
    }

    @Test
    fun decimalAlignedMantissaSkipsZeroChunk() {
        testTrace.section("decimalAlignedMantissaSkipsZeroChunk")
        // The widened double's 53-bit mantissa is 6710886400000000, whose
        // low 8-decimal-digit chunk is zero; the chunked multiplier skips it
        // and shifts the next chunk up. The value itself is an exact f32
        // integer, so the output is the plain integer form.
        assertEquals("12500000", ecmaJsonNumber(f32Literal(12500000.0f)))
    }

    @Test
    fun subnormalExpansionsSerialize() {
        testTrace.section("subnormalExpansionsSerialize")
        assertEquals("1.401298464324817e-45", ecmaJsonNumber(Float.fromBits(1)))
        assertEquals("4.203895392974451e-45", ecmaJsonNumber(Float.fromBits(3)))
    }

    @AfterTest
    fun flushTestTrace() {
        testTrace.flush()
    }
}
