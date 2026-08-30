package org.tiqian.test.trace

/**
 * Platform hook for the test-trace goldens. Only the JVM test runtime
 * can write into `src/jvmTest/resources`; every other target never
 * writes.
 */
internal expect object TestTracePlatform {

    /** True when traces are recorded and golden files written. */
    val updateMode: Boolean

    /**
     * True when Float arithmetic executes in double without per-op
     * rounding (Kotlin/JS). Tests whose expected values differ between
     * the strict-Float and double backends branch on this flag; it plays
     * no part in recording.
     */
    val doubleArithmetic: Boolean

    /**
     * Writes `golden/test-traces/<className>.txt` relative to the
     * engine module directory (the Gradle test working directory).
     */
    fun writeGolden(className: String, text: String)
}
