package org.tiqian.benchmark.android

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates the Tiqian engine's consumer baseline profile by exercising the real
 * `CjkText` → layout → shaping → draw path in the demo's [MainActivity]/`TiqianDemoScreen`.
 *
 * Runs against the demo's **non-minified debuggable** variant so the captured method
 * signatures are the real `Lorg/tiqian/...` names. A minified/R8 build (the `benchmark`
 * build type) would record obfuscated `La/b/c;` names that no consumer AAR could use.
 * `filterPredicate` keeps only `org.tiqian.` rules, so the output ships as the engine's
 * own consumer profile.
 *
 * Run on a device with locked clocks:
 * ```
 * ANDROID_HOME=… ANDROID_SERIAL=… ./gradlew :benchmark:android:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=org.tiqian.benchmark.android.BaselineProfileGenerator
 * ```
 * Output lands in `benchmark/android/build/outputs/…_additional_output/…/
 * BaselineProfileGenerator_generate-baseline-prof.txt`.
 */
@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineRule = BaselineProfileRule()

    @Test
    fun generate() {
        baselineRule.collect(
            packageName = TARGET_PACKAGE,
            filterPredicate = { rule -> rule.contains("org/tiqian/") },
        ) {
            val intent = Intent().apply {
                component = ComponentName(TARGET_PACKAGE, "$TARGET_PACKAGE.MainActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivityAndWait(intent)
            device.waitForIdle()

            val width = device.displayWidth
            val height = device.displayHeight
            repeat(6) { device.swipe(width / 2, height * 3 / 4, width / 2, height / 4, 12) }
            repeat(6) { device.swipe(width / 2, height / 4, width / 2, height * 3 / 4, 12) }
            device.waitForIdle()
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "org.tiqian.demo.android"
    }
}
