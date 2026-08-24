@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

import kotlin.JsFun
import kotlin.test.Test
import kotlin.test.assertTrue

@JsFun("(path) => { const node = path.split('.').reduce((acc, key) => acc && acc[key], globalThis); return typeof node === 'function'; }")
private external fun globalIsFunction(path: String): Boolean
@JsFun("(name) => typeof globalThis[name] === 'object' && globalThis[name] !== null")
private external fun globalIsObject(name: String): Boolean
@JsFun("(name, target) => target != null && typeof target[name] === 'function'")
private external fun globalIsFunctionOn(name: String, target: JsAny?): Boolean

class TiqianWebBridgeInstallTest {
    @Test
    fun lifecycleBridgeInstallsOptionsFromJs() {
        lifecycleBridge()
        assertTrue(globalIsFunction("__TiqianLifecycle.optionsFromJs"))
        assertTrue(globalIsFunction("__TiqianLifecycle.optionFloat"))
        assertTrue(globalIsFunction("__TiqianLifecycle.conformingExactFontSessionId"))
        assertTrue(globalIsFunction("__TiqianLifecycle.allowsSnapshotExactLayout"))
        assertTrue(globalIsFunction("__TiqianLifecycle.withoutExactFontSession"))
        assertTrue(globalIsFunction("__TiqianLifecycle.withRootDefaults"))
        assertTrue(globalIsFunction("__TiqianLifecycle.reportIssue"))
        assertTrue(globalIsFunction("__TiqianLifecycle.clearIssue"))
        assertTrue(globalIsFunction("__TiqianLifecycle.captureSourceInlineSize"))
        assertTrue(globalIsFunction("__TiqianLifecycle.applyConfiguredHostFontSize"))
        assertTrue(globalIsFunction("__TiqianLifecycle.stabilizeContentSizedItemInlineSize"))
    }

    @Test
    fun workerRequestBridgeInstallsWorkerLayoutRequest() {
        workerRequestBridge()
        assertTrue(globalIsFunction("__TiqianWorkerRequest.workerLayoutRequest"))
        assertTrue(globalIsFunction("__TiqianWorkerRequest.workerLayoutRequestForRoot"))
    }

    @Test
    fun prepareParagraphLayoutBridgeInstallsPrepareParagraphLayout() {
        prepareParagraphLayoutBridge()
        assertTrue(globalIsFunction("__TiqianPrepareParagraphLayout.prepareParagraphLayout"))
    }

    @Test
    fun commitPreparedParagraphBridgeInstallsCommitFunctions() {
        commitPreparedParagraphBridge()
        assertTrue(globalIsFunction("__TiqianCommitPreparedParagraph.commitWorkerPreparedParagraph"))
        assertTrue(globalIsFunction("__TiqianCommitPreparedParagraph.commitPreparedParagraph"))
    }

    @Test
    fun processParagraphBridgeInstallsProcessParagraph() {
        processParagraphBridge()
        assertTrue(globalIsFunction("__TiqianProcessParagraph.processParagraph"))
    }

    @Test
    fun canvasFontsBridgeInstallsCreateFontFamilies() {
        canvasFontsBridge()
        assertTrue(globalIsFunction("__TiqianCanvasFonts.createFontFamilies"))
        assertTrue(globalIsFunction("__TiqianCanvasFonts.cssFamilyToken"))
    }

    @Test
    fun browserMetricsBridgeInstallsAllFourGlobals() {
        browserMetricsBridge()
        assertTrue(globalIsObject("__TiqianCanvasFonts"))
        assertTrue(globalIsObject("__TiqianCanvasMetrics"))
        assertTrue(globalIsObject("__TiqianCanvasShaping"))
        assertTrue(globalIsObject("__TiqianBrowserMetricsBridge"))
        assertTrue(globalIsFunction("__TiqianCanvasFonts.createFontFamilies"))
        assertTrue(globalIsFunction("__TiqianCanvasMetrics.createMetricsResolver"))
        assertTrue(globalIsFunction("__TiqianCanvasShaping.createTextShaper"))
        assertTrue(globalIsFunction("__TiqianBrowserMetricsBridge.createBrowserMetricsBridge"))
    }

    @Test
    fun preparedMetadataBridgeInstallsJsonBuilders() {
        preparedMetadataBridge()
        assertTrue(globalIsFunction("__TiqianPreparedMetadata.preparedSemanticReplayJson"))
        assertTrue(globalIsFunction("__TiqianPreparedMetadata.preparedInlineObjectMetaJson"))
        assertTrue(globalIsFunction("__TiqianPreparedMetadata.preparedCjkStrongSemanticsJson"))
    }

    @Test
    fun progressiveRelayoutSessionBridgeInstallsSessionFactory() {
        progressiveRelayoutSessionBridge()
        assertTrue(globalIsFunction("__TiqianProgressiveRelayoutSession.createProgressiveRelayoutSession"))
    }

    @Test
    fun ffiFacadeExposesAllFiveMembers() {
        val facade = tsFfiFacade
        assertTrue(globalIsFunctionOn("classifyFontRole", facade))
        assertTrue(globalIsFunctionOn("unsupportedInlineShapingProperties", facade))
        assertTrue(globalIsFunctionOn("firstDivergentInlineShapingProperty", facade))
        assertTrue(globalIsFunctionOn("precomputeParagraphWithDiagnostics", facade))
        assertTrue(globalIsFunctionOn("precomputeParagraphWithBrowserMetrics", facade))
    }
}
