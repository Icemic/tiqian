package org.tiqian.test.trace

/**
 * Accumulates per-test assertion events for the test-trace golden files.
 *
 * Every instrumented test class owns one [TestTraceRecorder]. The
 * `section(name)` call inserted at the top of each `@Test` function opens
 * a section; the traced assertion facade appends one line per assertion
 * into the open section. Recording and writing happen only under
 * TIQIAN_UPDATE_GOLDEN=1; a regular run executes the underlying
 * assertions unchanged and records nothing. The store is a singleton
 * because JUnit-style runners create a fresh test-class instance per
 * test function; per-instance state would lose everything but the last
 * section.
 *
 * Golden layout, one file per class (`golden/test-traces/<Class>.txt`):
 *
 * ```
 * class: <Class>
 * test: <function>
 * <event lines>
 * test: <function>
 * ...
 * ```
 *
 * Sections are written sorted by name so the file is byte-stable no
 * matter what order the runner executes the functions in. The files are
 * local generated artifacts (gitignored); the Haxe port replays the same
 * tests and diffs against them.
 */
internal object TestTraceStore {

    private val byClass = LinkedHashMap<String, LinkedHashMap<String, StringBuilder>>()

    fun open(className: String, sectionName: String) {
        byClass.getOrPut(className) { LinkedHashMap() }.getOrPut(sectionName) { StringBuilder() }
    }

    fun append(className: String, sectionName: String, line: String) {
        val section = byClass[className]?.get(sectionName) ?: return
        section.append(line).append('\n')
    }

    /** Full class golden text with sections sorted by name. */
    fun classText(className: String): String = buildString {
        append("class: ").append(className).append('\n')
        val sections = byClass[className].orEmpty()
        for (name in sections.keys.sorted()) {
            append("test: ").append(name).append('\n')
            val text = sections.getValue(name).toString()
            if (text.isNotEmpty()) append(text)
        }
    }
}

/**
 * Per-test-class recorder handed out by the instrumentation. Insertion
 * pattern inside an instrumented class:
 *
 * ```
 * private val testTrace = TestTraceRecorder("<Class>")
 *
 * @Test
 * fun something() {
 *     testTrace.section("something")
 *     ...traced assertions...
 * }
 *
 * @AfterTest
 * fun flushTestTrace() { testTrace.flush() }
 * ```
 */
internal class TestTraceRecorder(private val className: String) {

    init {
        // The last constructed recorder is the one whose test function is
        // running: JUnit-style runners build a fresh class instance right
        // before each test method, so instance construction time marks
        // the active recorder for the traced assertions in that method.
        TestTrace.recorder = this
    }

    private var sectionName: String? = null

    /** Opens the section for the test function about to run. */
    fun section(name: String) {
        sectionName = name
        TestTraceStore.open(className, name)
    }

    /** Appends one event line to the open section. */
    fun record(line: String) {
        val name = sectionName ?: return
        TestTraceStore.append(className, name, line)
    }

    /**
     * Writes the class golden in update mode; a regular run does nothing.
     * The whole class file is rewritten from the sections accumulated
     * during this run, so a filtered test execution would write a partial
     * file; always generate with the full `:engine:jvmTest` run.
     */
    fun flush() {
        sectionName ?: return
        if (!TestTracePlatform.updateMode) return
        TestTracePlatform.writeGolden(className, TestTraceStore.classText(className))
    }
}
