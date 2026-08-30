package org.tiqian.trace

/**
 * One unit-level process-trace scenario: a named corpus entry whose body
 * records the intermediate values of an engine component through
 * [org.tiqian.test.trace.TraceRecorder].
 *
 * The scenario text is the port contract: a TIQIAN_UPDATE_GOLDEN=1 run
 * writes it to `golden/process-traces/<id>.txt`, and the Haxe port runs
 * the same scenario bodies and must produce the same bytes.
 *
 * Field values passed to `TraceRecorder.event` may only be Float, Double,
 * Int, Long, Boolean, String, or null. Data-class `toString()` renders
 * floats through the platform default and diverges between targets, so a
 * scenario decomposes aggregates into traced fields.
 */
data class TraceScenario(
    val id: String,
    val notes: String,
    val run: () -> String,
)
