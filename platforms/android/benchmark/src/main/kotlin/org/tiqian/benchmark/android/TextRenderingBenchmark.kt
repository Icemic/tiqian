package org.tiqian.benchmark.android

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class TextRenderingBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test fun startupComposePlain() = startup(Case.ComposePlain)
    @Test fun startupTiqianPlain() = startup(Case.TiqianPlain)
    @Test fun startupComposeArticle() = startup(Case.ComposeArticle)
    @Test fun startupTiqianArticle() = startup(Case.TiqianArticle)
    @Test fun startupTiqianViewArticle() = startup(Case.TiqianViewArticle)

    @Test fun scrollComposePlain() = scroll(Case.ComposePlain)
    @Test fun scrollTiqianPlain() = scroll(Case.TiqianPlain)
    @Test fun scrollComposeArticle() = scroll(Case.ComposeArticle)
    @Test fun scrollTiqianArticle() = scroll(Case.TiqianArticle)
    @Test fun scrollTiqianViewArticle() = scroll(Case.TiqianViewArticle)
    @Test fun scrollSelectedTiqianViewArticle() = scroll(Case.TiqianViewSelectedArticle)
    @Test fun scrollTiqianViewOverhangArticle() = scroll(Case.TiqianViewOverhangArticle)

    @Test fun recomposeComposeArticle() = recompose(Case.ComposeArticle)
    @Test fun recomposeTiqianArticle() = recompose(Case.TiqianArticle)

    private fun startup(case: Case) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = STARTUP_COMPILATION,
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = { pressHome() },
            measureBlock = { startCaseAndWait(case) },
        )
    }

    @OptIn(ExperimentalMetricApi::class)
    private fun scroll(case: Case) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = if (case.isViewArticle) {
                listOf(
                    FrameTimingMetric(),
                    TraceSectionMetric(
                        sectionName = PAINT_OVERHANG_RECORD_SECTION,
                        mode = TraceSectionMetric.Mode.Count,
                        label = "paintOverhangRecordCount",
                    ),
                    TraceSectionMetric(
                        sectionName = PAINT_OVERHANG_RECORD_SECTION,
                        mode = TraceSectionMetric.Mode.Sum,
                        label = "paintOverhangRecordDuration",
                    ),
                )
            } else {
                listOf(FrameTimingMetric())
            },
            compilationMode = INTERACTION_COMPILATION,
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = { startCaseAndWait(case) },
            measureBlock = {
                val width = device.displayWidth
                val height = device.displayHeight
                repeat(8) {
                    device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 12)
                }
            },
        )
    }

    private fun recompose(case: Case) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = INTERACTION_COMPILATION,
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = { startCaseAndWait(case) },
            measureBlock = {
                val article = checkNotNull(device.findObject(By.desc("benchmark-recompose"))) {
                    "recomposition target is missing"
                }
                repeat(12) {
                    article.click()
                    device.waitForIdle()
                }
            },
        )
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.startCaseAndWait(case: Case) {
        val intent = Intent().apply {
            component = ComponentName(TARGET_PACKAGE, "$TARGET_PACKAGE.${case.activityName}")
            putExtra("benchmark_case", case.wireName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivityAndWait(intent)
        check(
            device.wait(
                Until.hasObject(By.desc("benchmark-ready-${case.wireName}")),
                10_000,
            ),
        ) { "benchmark surface did not become ready: ${case.wireName}" }
    }

    private enum class Case(
        val wireName: String,
        val activityName: String = "TextPerformanceBenchmarkActivity",
    ) {
        ComposePlain("compose-plain"),
        TiqianPlain("tiqian-plain"),
        ComposeArticle("compose-article"),
        TiqianArticle("tiqian-article"),
        TiqianViewArticle("tiqian-view-article", "TiqianViewDemoActivity"),
        TiqianViewSelectedArticle("tiqian-view-selected-article", "TiqianViewDemoActivity"),
        TiqianViewOverhangArticle("tiqian-view-overhang-article", "TiqianViewDemoActivity");

        val isViewArticle: Boolean
            get() = activityName == "TiqianViewDemoActivity"
    }

    private companion object {
        const val TARGET_PACKAGE = "org.tiqian.demo.android"
        const val PAINT_OVERHANG_RECORD_SECTION =
            "AndroidParagraphRenderer.recordPaintOverhang"

        // `JitVisibleCompilation`: the previous `Full()` AOT-compiled everything, so a
        // regression that only hurts JIT/interpreter paths — the S8+ (API 28) reality — was
        // invisible. Measure the compilation states a real device actually runs in instead.
        //
        // Cold startup on a freshly installed app: no baseline profile, no AOT, so the
        // interpreter/JIT startup cost is included. This is the worst case a first-launch
        // user sees, and where a missing baseline profile would show up.
        val STARTUP_COMPILATION: CompilationMode = CompilationMode.None()

        // Steady-state frames after the hot paths have JIT-warmed (3 warmup iterations):
        // this is where a "broke the JIT" regression surfaces. Swap to CompilationMode.Full()
        // for a pure-code-cost upper bound when that comparison is wanted.
        val INTERACTION_COMPILATION: CompilationMode = CompilationMode.Partial(warmupIterations = 3)
    }
}
