package org.tiqian.test.trace

import org.tiqian.test.trace.TestTraceRender.escapeOperand
import org.tiqian.test.trace.TestTraceRender.render
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.test.assertFailsWith as ktAssertFailsWith
import kotlin.test.assertEquals as ktAssertEquals
import kotlin.test.assertFalse as ktAssertFalse
import kotlin.test.assertNotNull as ktAssertNotNull
import kotlin.test.assertNull as ktAssertNull
import kotlin.test.assertTrue as ktAssertTrue
import kotlin.test.fail as ktFail

/**
 * Process-collecting drop-in replacements for the kotlin.test assertion
 * functions used by the engine test suite. Every call records its
 * operands as one event line into the open test-trace section, then
 * delegates to the underlying kotlin.test function unchanged, so
 * pass/fail semantics stay byte-for-byte the same as before
 * instrumentation. A regular run records nothing: [recordEvent] returns
 * before building a line unless TIQIAN_UPDATE_GOLDEN=1 is set.
 *
 * Numeric operands are pinned by static overloads rather than inspected
 * at runtime: Kotlin/JS boxes Int, Long, Float, and Double as the same
 * runtime number, so a runtime `is Double` check cannot tell an Int `2`
 * from a Double `2.0`. The overload selected at compile time decides
 * the rendering for direct operands (Int through [TraceFormat.i],
 * Float through [TraceFormat.fd] with six decimals).
 *
 * Every field value passes [TestTraceRender.canonicalNumbers] at the
 * single recordEvent choke point, which strips whole-number fractions
 * ("5.000000" -> "5", the JVM toString "10.0" -> the Kotlin/JS "10").
 * Composite operands render recursively through one fd-based number
 * text, so the same call site records identical bytes on JVM and JS.
 */

@PublishedApi
internal fun recordEvent(name: String, vararg fields: Pair<String, String>?) {
    if (!TestTracePlatform.updateMode) return
    val line = buildString {
        append(name)
        for (field in fields) {
            if (field == null) continue
            append(' ').append(field.first).append('=')
                .append(TestTraceRender.canonicalNumbers(field.second))
        }
    }
    TestTrace.currentRecorder()?.record(line)
}

/** Recorder handle for the instrumented class currently running. */
internal object TestTrace {
    internal var recorder: TestTraceRecorder? = null

    fun currentRecorder(): TestTraceRecorder? = recorder
}

@PublishedApi
internal fun msgField(message: String?): Pair<String, String>? =
    if (message == null) null else "msg" to "'${escapeOperand(message)}'"

fun <T> assertEquals(expected: T, actual: T, message: String? = null) {
    recordEvent("eq", "expected" to render(expected), "actual" to render(actual), msgField(message))
    ktAssertEquals(expected, actual, message)
}

fun assertEquals(expected: Int, actual: Int, message: String? = null) {
    recordEvent("eq", "expected" to TraceFormat.i(expected), "actual" to TraceFormat.i(actual), msgField(message))
    ktAssertEquals(expected, actual, message)
}

fun assertEquals(expected: Int?, actual: Int?, message: String? = null) {
    recordEvent(
        "eq",
        "expected" to (expected?.let { TraceFormat.i(it) } ?: "-"),
        "actual" to (actual?.let { TraceFormat.i(it) } ?: "-"),
        msgField(message),
    )
    ktAssertEquals(expected, actual, message)
}

fun assertEquals(expected: Long, actual: Long, message: String? = null) {
    recordEvent("eq", "expected" to expected.toString(), "actual" to actual.toString(), msgField(message))
    ktAssertEquals(expected, actual, message)
}

fun assertEquals(expected: Float, actual: Float, message: String? = null) {
    val expectedText = TraceFormat.fd(expected, 6)
    val actualText = TraceFormat.fd(actual, 6)
    recordEvent("eq", "expected" to expectedText, "actual" to actualText, msgField(message))
    ktAssertEquals(f32Of(expected), f32Of(actual), message)
}

fun assertEquals(expected: Boolean, actual: Boolean, message: String? = null) {
    recordEvent("eq", "expected" to expected.toString(), "actual" to actual.toString(), msgField(message))
    ktAssertEquals(expected, actual, message)
}

fun assertEquals(expected: String, actual: String, message: String? = null) {
    recordEvent(
        "eq",
        "expected" to "'${escapeOperand(expected)}'",
        "actual" to "'${escapeOperand(actual)}'",
        msgField(message),
    )
    ktAssertEquals(expected, actual, message)
}

/** Absolute-tolerance form: the third operand is a Float tolerance. */
fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float, message: String? = null) {
    recordEvent(
        "eq-tol",
        "expected" to TraceFormat.fd(expected, 6),
        "actual" to TraceFormat.fd(actual, 6),
        "tol" to TraceFormat.fd(absoluteTolerance, 6),
        msgField(message),
    )
    ktAssertEquals(f32Of(expected), f32Of(actual), f32Of(absoluteTolerance), message)
}

/**
 * Applies the Float rounding the operand denotes on every backend.
 * Kotlin/JS evaluates Float literals in double precision, so a literal
 * operand arrives off the Float grid while the computed operand is on it;
 * the bit round-trip rounds the literal there and is an identity on JVM
 * and Native. Both Float comparisons above run through it so every
 * backend compares the same two Float values.
 */
private fun f32Of(v: Float): Float = Float.fromBits(v.toRawBits())

/**
 * Routes a Float literal through a FloatArray store so the code under
 * test receives the Float the literal denotes. Kotlin/JS evaluates Float
 * literals in double precision and only the FloatArray store applies the
 * Float rounding, so an un-routed non-representable literal reaches the
 * engine as a double there; JVM and Native are unchanged. Inputs whose
 * output a test pins must route through this.
 */
fun f32Literal(v: Float): Float = floatArrayOf(v)[0]

// The Boolean/null assertions carry the same data-flow contracts as their
// kotlin.test counterparts so `assertTrue(x is T)` keeps enabling smart
// casts at instrumented call sites. The Kotlin/JS number boxing noted
// above is unrelated: contracts resolve at compile time.

@OptIn(ExperimentalContracts::class)
fun assertTrue(actual: Boolean, message: String? = null) {
    contract { returns() implies actual }
    recordEvent("is-true", "actual" to actual.toString(), msgField(message))
    ktAssertTrue(actual, message)
}

fun assertTrue(message: String? = null, block: () -> Boolean) {
    val value = block()
    recordEvent("is-true", "actual" to value.toString(), msgField(message))
    ktAssertTrue(value, message)
}

@OptIn(ExperimentalContracts::class)
fun assertFalse(actual: Boolean, message: String? = null) {
    contract { returns() implies (!actual) }
    recordEvent("is-false", "actual" to actual.toString(), msgField(message))
    ktAssertFalse(actual, message)
}

@OptIn(ExperimentalContracts::class)
fun assertFalse(message: String? = null, block: () -> Boolean) {
    val value = block()
    recordEvent("is-false", "actual" to value.toString(), msgField(message))
    ktAssertFalse(value, message)
}

@OptIn(ExperimentalContracts::class)
fun assertNull(actual: Any?, message: String? = null) {
    contract { returns() implies (actual == null) }
    recordEvent("null", "actual" to render(actual), msgField(message))
    ktAssertNull(actual, message)
}

@OptIn(ExperimentalContracts::class)
fun <T : Any> assertNotNull(actual: T?, message: String? = null): T {
    contract { returns() implies (actual != null) }
    recordEvent("not-null", "actual" to render(actual), msgField(message))
    return ktAssertNotNull(actual, message)
}

/**
 * Reified exception-expectation form. Records the expected exception
 * type and the thrown message after the underlying assertion ran, so
 * the golden pins both the expectation and what the engine actually
 * threw. All current call sites use this reified form.
 */
inline fun <reified T : Throwable> assertFailsWith(message: String? = null, block: () -> Unit): T {
    val thrown = ktAssertFailsWith<T>(message, block)
    recordEvent(
        "raises",
        "exception" to (T::class.simpleName ?: "Throwable"),
        "thrown" to render(thrown.message),
        msgField(message),
    )
    return thrown
}

fun fail(message: String? = null, cause: Throwable? = null): Nothing {
    recordEvent("fail", msgField(message))
    return ktFail(message, cause)
}

/**
 * Runs [block] and records that it ran to completion. An exception from
 * the block propagates unchanged, so failure semantics are identical to
 * calling the block directly; the event line documents that the subject
 * call completed in this run, which is the whole assertion for
 * no-throw style tests.
 */
inline fun <R> assertDoesNotThrow(block: () -> R): R {
    val result = block()
    recordEvent("no-throw")
    return result
}
