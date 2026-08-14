@file:Suppress("DEPRECATION")

package org.tiqian.compose

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange as ComposeTextRange
import androidx.compose.ui.text.UrlAnnotation
import androidx.compose.ui.text.VerbatimTtsAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import org.tiqian.core.LayoutResult
import org.tiqian.core.getBoundingBoxes
import org.tiqian.core.getCursorRect
import org.tiqian.core.getLineForOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
class CjkSelectionTest {
    @Test
    fun logicalSelectionSurvivesVisibleFragmentDisposal() {
        var state: CjkSelectionState? = null
        var showFirst by mutableStateOf(true)
        val document = CjkSelectionDocument(
            listOf(
                CjkSelectionDocumentFragment("first", AnnotatedString("第一段")),
                CjkSelectionDocumentFragment("second", AnnotatedString("第二段")),
            ),
        )
        ImageComposeScene(width = 320, height = 160) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CjkSelectionContainer(
                state = selectionState,
                document = document,
            ) {
                if (showFirst) {
                    CjkSelectionScope("first") { CjkText("第一段") }
                } else {
                    CjkSelectionScope("second") { CjkText("第二段") }
                }
            }
        }.use { scene ->
            scene.render()
            assertTrue(state!!.selectAll())
            assertEquals("第一段\n第二段", state!!.selectedText?.text)
            showFirst = false
            scene.render()
            assertEquals("第一段\n第二段", state!!.selectedText?.text)
            assertTrue(state!!.hasSelection)
        }
    }

    @Test
    fun logicalDocumentIgnoresUnmappedHostText() {
        var state: CjkSelectionState? = null
        val document = CjkSelectionDocument(
            listOf(CjkSelectionDocumentFragment("body", AnnotatedString("正文"))),
        )
        ImageComposeScene(width = 320, height = 160) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CjkSelectionContainer(state = selectionState, document = document) {
                Column {
                    CjkText("宿主标题")
                    CjkSelectionScope("body") { CjkText("正文") }
                }
            }
        }.use { scene ->
            scene.render()
            assertTrue(state!!.selectAll())
            assertEquals("正文", state!!.selectedText?.text)
        }
    }


    @Test
    fun toolbarHandleClearanceScalesWithDensity() {
        val oneX = foundationSelectionToolbarHandleClearancePx(Density(1f))
        val twoX = foundationSelectionToolbarHandleClearancePx(Density(2f))

        assertEquals(oneX * 2f, twoX, 0.001f)
    }

    @Test
    fun mouseDragSelectsSourceTextAndPaintsLayoutBoxes() {
        val source = "前文选择文本后文。"
        var state: CjkSelectionState? = null
        var layout: LayoutResult? = null

        ImageComposeScene(width = 420, height = 160) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CompositionLocalProvider(
                LocalTextSelectionColors provides TextSelectionColors(Color.Blue, Color.Magenta),
            ) {
                CjkSelectionContainer(state = selectionState) {
                    CjkText(
                        text = source,
                        modifier = Modifier.width(400.dp),
                        style = TextStyle(fontSize = 24.sp),
                        onTextLayout = { layout = it },
                    )
                }
            }
        }.use { scene ->
            scene.render()
            val result = layout ?: error("layout missing")
            val selectionState = state ?: error("selection state missing")
            assertTrue(selectionState.selectAll(), "CjkText must register with its selection container")
            selectionState.clearSelection()
            drag(scene, cursor(result, 2), cursor(result, 6), startTime = 1_000L)
            val image = scene.render().toComposeImageBitmap().toPixelMap()

            assertEquals("选择文本", selectionState.selectedText?.text)
            var magentaPixels = 0
            for (y in 0 until image.height) for (x in 0 until image.width) {
                val color = image[x, y]
                if (color.red > 0.75f && color.blue > 0.75f && color.green < 0.35f) magentaPixels++
            }
            assertTrue(magentaPixels > 300, "selection background should be visible, got $magentaPixels")
        }
    }

    @Test
    fun selectionCopiesOriginalAnnotatedSource() {
        val clipboard = RecordingClipboardManager()
        var state: CjkSelectionState? = null
        var layout: LayoutResult? = null
        val source = AnnotatedString("甲乙丙丁戊")

        ImageComposeScene(width = 320, height = 140) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                CjkSelectionContainer(state = selectionState) {
                    CjkText(
                        text = source,
                        modifier = Modifier.width(300.dp),
                        style = TextStyle(fontSize = 24.sp),
                        onTextLayout = { layout = it },
                    )
                }
            }
        }.use { scene ->
            scene.render()
            val result = layout ?: error("layout missing")
            drag(scene, cursor(result, 1), cursor(result, 4), startTime = 2_000L)

            assertTrue(state?.copySelection() == true)
            assertEquals("乙丙丁", clipboard.recordedText?.text)
        }
    }

    @Test
    fun copyMatchesWebRubyProjectionWithoutChangingSelectionOrAccessibilityText() {
        val clipboard = RecordingClipboardManager()
        var state: CjkSelectionState? = null
        val firstSource = buildAnnotatedString {
            append("前")
            ruby("提椠", "tíqiàn")
        }
        val secondSource = buildAnnotatedString {
            bopomofo("您", "ㄋㄧㄣˊ")
            append("后")
        }

        ImageComposeScene(width = 360, height = 220) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                CjkSelectionContainer(state = selectionState) {
                    Column {
                        CjkText(firstSource, modifier = Modifier.width(340.dp), style = TextStyle(fontSize = 24.sp))
                        CjkText(secondSource, modifier = Modifier.width(340.dp), style = TextStyle(fontSize = 24.sp))
                    }
                }
            }
        }.use { scene ->
            scene.render()
            val selectionState = state ?: error("selection state missing")
            assertTrue(selectionState.selectAll())
            scene.render()

            assertEquals("前提椠\n您后", selectionState.selectedText?.text)
            assertTrue(selectionState.copySelection())
            assertEquals("前提椠（tíqiàn）\n您（ㄋㄧㄣˊ）后", clipboard.recordedText?.text)

            val semanticTexts = scene.semanticsOwners.flatMap {
                it.getAllSemanticsNodes(mergingEnabled = false)
            }.mapNotNull { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull() }
            assertTrue(firstSource in semanticTexts)
            assertTrue(secondSource in semanticTexts)
        }
    }

    @Test
    fun partialRubyBaseSelectionDoesNotCopyDetachedReading() {
        val clipboard = RecordingClipboardManager()
        var state: CjkSelectionState? = null
        val source = buildAnnotatedString {
            ruby("提椠", "tíqiàn")
            append("之后")
        }

        ImageComposeScene(width = 320, height = 140) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                CjkSelectionContainer(state = selectionState) {
                    CjkText(source, modifier = Modifier.width(300.dp), style = TextStyle(fontSize = 24.sp))
                }
            }
        }.use { scene ->
            scene.render()
            val semantics = scene.semanticsOwners.flatMap {
                it.getAllSemanticsNodes(mergingEnabled = false)
            }.single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull() == source &&
                    SemanticsActions.SetSelection in node.config
            }
            val setSelection = semantics.config[SemanticsActions.SetSelection].action
                ?: error("set-selection semantics action missing")

            assertTrue(setSelection(0, 1, true))
            assertEquals("提", state?.selectedText?.text)
            assertTrue(state?.copySelection() == true)
            assertEquals("提", clipboard.recordedText?.text)
        }
    }

    @Test
    fun desktopContextMenuConsumesTheLiveTiqianSelectionAndActions() {
        val clipboard = RecordingClipboardManager()
        val contextMenu = RecordingTextContextMenu()
        var state: CjkSelectionState? = null
        var layout: LayoutResult? = null
        val source = "前 context 后"

        ImageComposeScene(width = 360, height = 140) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CompositionLocalProvider(
                LocalClipboardManager provides clipboard,
                LocalTextContextMenu provides contextMenu,
            ) {
                CjkSelectionContainer(state = selectionState) {
                    CjkText(
                        source,
                        modifier = Modifier.width(340.dp),
                        style = TextStyle(fontSize = 24.sp),
                        onTextLayout = { layout = it },
                    )
                }
            }
        }.use { scene ->
            scene.render()
            val selectionState = state ?: error("selection state missing")
            val manager = contextMenu.manager ?: error("desktop text context menu was not installed")

            assertTrue(selectionState.selectAll())
            scene.render()
            assertEquals(source, manager.selectedText.text)
            assertTrue(manager.copy?.enabled == true)
            manager.copy?.execute?.invoke()
            assertEquals(source, clipboard.recordedText?.text)

            selectionState.clearSelection()
            manager.selectWordAtPositionIfNotAlreadySelected(
                cursor(layout ?: error("layout missing"), source.indexOf("context") + 2),
            )
            assertEquals("context", selectionState.selectedText?.text)
        }
    }

    @Test
    fun clickOutsideSelectableClearsTheSettledSelection() {
        var state: CjkSelectionState? = null

        ImageComposeScene(width = 360, height = 220) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CjkSelectionContainer(
                modifier = Modifier.width(360.dp).height(220.dp),
                state = selectionState,
            ) {
                CjkText(
                    "甲乙丙丁",
                    modifier = Modifier.width(340.dp),
                    style = TextStyle(fontSize = 24.sp),
                )
            }
        }.use { scene ->
            scene.render()
            val selectionState = state ?: error("selection state missing")
            assertTrue(selectionState.selectAll())
            scene.render()

            tap(
                scene,
                position = Offset(300f, 180f),
                pressTime = 2_500L,
                releaseTime = 2_550L,
            )
            scene.render()

            assertEquals(null, selectionState.selectedText)
            assertTrue(!selectionState.hasSelection)
        }
    }

    @Test
    fun tapWithoutCjkSelectionDoesNotHideAnotherChildsTextToolbar() {
        val toolbar = RecordingTextToolbar()

        ImageComposeScene(width = 360, height = 220) {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                CjkSelectionContainer(
                    modifier = Modifier.width(360.dp).height(220.dp),
                ) {}
            }
        }.use { scene ->
            scene.render()

            tap(
                scene,
                position = Offset(300f, 180f),
                pressTime = 2_600L,
                releaseTime = 2_650L,
            )
            scene.render()

            assertEquals(0, toolbar.hideCalls)
        }
    }

    @Test
    fun doubleClickSelectsTheEngineLatinWordCluster() {
        val source = "前 template 后"
        var state: CjkSelectionState? = null
        var layout: LayoutResult? = null

        ImageComposeScene(width = 420, height = 140) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CjkSelectionContainer(state = selectionState) {
                CjkText(
                    text = source,
                    modifier = Modifier.width(400.dp),
                    style = TextStyle(fontSize = 24.sp),
                    onTextLayout = { layout = it },
                )
            }
        }.use { scene ->
            scene.render()
            val wordBoxes = (layout ?: error("layout missing")).getBoundingBoxes(2, 10)
            val wordBox = org.tiqian.core.Rect(
                wordBoxes.minOf { it.left }, wordBoxes.minOf { it.top },
                wordBoxes.maxOf { it.right }, wordBoxes.maxOf { it.bottom },
            )
            val point = Offset((wordBox.left + wordBox.right) / 2f, (wordBox.top + wordBox.bottom) / 2f)
            tap(scene, point, pressTime = 1_000L, releaseTime = 1_050L)
            tap(scene, point, pressTime = 1_180L, releaseTime = 1_230L)

            assertEquals("template", state?.selectedText?.text)
        }
    }

    @Test
    fun tripleClickUsesFoundationGestureCountingAndSelectsTheSourceParagraph() {
        val source = "首段文字\nsecond paragraph\n末段"
        var state: CjkSelectionState? = null
        var layout: LayoutResult? = null

        ImageComposeScene(width = 520, height = 200) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CjkSelectionContainer(state = selectionState) {
                CjkText(
                    text = source,
                    modifier = Modifier.width(500.dp),
                    style = TextStyle(fontSize = 24.sp),
                    onTextLayout = { layout = it },
                )
            }
        }.use { scene ->
            scene.render()
            val result = layout ?: error("layout missing")
            val point = cursor(result, source.indexOf("second") + 2)
            tap(scene, point, pressTime = 1_000L, releaseTime = 1_040L)
            tap(scene, point, pressTime = 1_140L, releaseTime = 1_180L)
            tap(scene, point, pressTime = 1_280L, releaseTime = 1_320L)

            assertEquals("second paragraph", state?.selectedText?.text)
        }
    }

    @Test
    fun backwardMouseDragPreservesCrossedFoundationHandleDirection() {
        val source = "甲乙丙丁戊己庚"
        var state: CjkSelectionState? = null
        var layout: LayoutResult? = null

        ImageComposeScene(width = 360, height = 140) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CjkSelectionContainer(state = selectionState) {
                CjkText(
                    source,
                    modifier = Modifier.width(340.dp),
                    style = TextStyle(fontSize = 24.sp),
                    onTextLayout = { layout = it },
                )
            }
        }.use { scene ->
            scene.render()
            val result = layout ?: error("layout missing")
            drag(scene, cursor(result, 6), cursor(result, 2), startTime = 2_000L)
            val selectionState = state ?: error("selection state missing")

            assertEquals("丙丁戊己", selectionState.selectedText?.text)
            assertTrue(selectionState.handlesCrossed)
            assertTrue(
                (selectionState.startHandlePosition?.x ?: 0f) >
                    (selectionState.endHandlePosition?.x ?: 0f),
                "logical start/end handles must not be normalized before Foundation or their shapes flip",
            )
        }
    }

    @Test
    fun dragAcrossCjkTextNodesCopiesWithParagraphSeparator() {
        var state: CjkSelectionState? = null
        var first: LayoutResult? = null
        var second: LayoutResult? = null

        ImageComposeScene(width = 360, height = 220) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CjkSelectionContainer(state = selectionState) {
                Column {
                    CjkText(
                        "甲乙丙",
                        modifier = Modifier.width(340.dp),
                        style = TextStyle(fontSize = 24.sp),
                        onTextLayout = { first = it },
                    )
                    CjkText(
                        "丁戊己",
                        modifier = Modifier.width(340.dp),
                        style = TextStyle(fontSize = 24.sp),
                        onTextLayout = { second = it },
                    )
                }
            }
        }.use { scene ->
            scene.render()
            val firstResult = first ?: error("first layout missing")
            val secondResult = second ?: error("second layout missing")
            val start = cursor(firstResult, 1)
            val endLocal = cursor(secondResult, 2)
            val end = Offset(endLocal.x, firstResult.size.height + endLocal.y)
            drag(scene, start, end, startTime = 3_000L)

            assertEquals("乙丙\n丁戊", state?.selectedText?.text)
        }
    }

    @Test
    fun selectionDragCancelsLinkClickButTapStillClicks() {
        var clicks = 0
        var state: CjkSelectionState? = null
        var layout: LayoutResult? = null
        val text = buildAnnotatedString {
            append("甲")
            withLink(LinkAnnotation.Clickable("tag", linkInteractionListener = { clicks++ })) {
                append("链接")
            }
            append("乙")
        }

        ImageComposeScene(width = 320, height = 140) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CjkSelectionContainer(state = selectionState) {
                CjkText(
                    text,
                    modifier = Modifier.width(300.dp),
                    style = TextStyle(fontSize = 24.sp),
                    onTextLayout = { layout = it },
                )
            }
        }.use { scene ->
            scene.render()
            val linkBoxes = (layout ?: error("layout missing")).getBoundingBoxes(1, 3)
            val centerY = (linkBoxes.first().top + linkBoxes.first().bottom) / 2f
            val inside = Offset((linkBoxes.first().left + linkBoxes.last().right) / 2f, centerY)
            tap(scene, inside, pressTime = 4_000L, releaseTime = 4_050L)
            assertEquals(1, clicks, "an ordinary link tap must still dispatch")

            drag(
                scene,
                Offset(linkBoxes.first().left + 1f, centerY),
                Offset(linkBoxes.last().right - 1f, centerY),
                startTime = 5_000L,
            )

            assertEquals(1, clicks, "selection drag must cancel the pending link click")
            assertEquals("链接", state?.selectedText?.text)
        }
    }

    @Test
    fun touchLongPressSelectsOneHanInteractionUnit() {
        var state: CjkSelectionState? = null
        var layout: LayoutResult? = null
        val shortLongPress = object : ViewConfiguration {
            override val longPressTimeoutMillis = 1L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 8f
        }

        ImageComposeScene(width = 320, height = 140) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CompositionLocalProvider(LocalViewConfiguration provides shortLongPress) {
                CjkSelectionContainer(state = selectionState) {
                    CjkText(
                        "甲中文乙",
                        modifier = Modifier.width(300.dp),
                        style = TextStyle(fontSize = 24.sp),
                        onTextLayout = { layout = it },
                    )
                }
            }
        }.use { scene ->
            scene.render()
            val box = (layout ?: error("layout missing")).getBoundingBoxes(1, 2).single()
            val point = Offset((box.left + box.right) / 2f, (box.top + box.bottom) / 2f)
            val selectionState = state ?: error("selection state missing")
            scene.sendPointerEvent(PointerEventType.Press, point, timeMillis = 6_000L, type = PointerType.Touch)
            Thread.sleep(20L)
            scene.render()
            scene.sendPointerEvent(
                PointerEventType.Move,
                point + Offset(1f, 0f),
                timeMillis = 6_025L,
                type = PointerType.Touch,
            )
            assertFalse(
                selectionState.isSelectionAutoScrollArmed,
                "post-long-press jitter below touch slop must not arm auto-scroll",
            )
            scene.sendPointerEvent(PointerEventType.Release, point, timeMillis = 6_050L, type = PointerType.Touch)

            assertEquals("中", selectionState.selectedText?.text)
        }
    }

    @Test
    fun clearingTouchSelectionHidesSystemMenuBeforePublishingEmptySelection() {
        var state: CjkSelectionState? = null
        var layout: LayoutResult? = null
        val shortLongPress = object : ViewConfiguration {
            override val longPressTimeoutMillis = 1L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 8f
        }

        ImageComposeScene(width = 320, height = 140) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CompositionLocalProvider(LocalViewConfiguration provides shortLongPress) {
                CjkSelectionContainer(state = selectionState) {
                    CjkText(
                        "甲中文乙",
                        modifier = Modifier.width(300.dp),
                        style = TextStyle(fontSize = 24.sp),
                        onTextLayout = { layout = it },
                    )
                }
            }
        }.use { scene ->
            scene.render()
            val selectionState = state ?: error("selection state missing")
            val menuEvents = mutableListOf<Pair<String, Boolean>>()
            val systemMenu = CjkSystemContextMenu(
                show = { menuEvents += "show" to selectionState.hasSelection },
                hide = { menuEvents += "hide" to selectionState.hasSelection },
            )
            selectionState.attachSystemContextMenu(systemMenu)

            val box = (layout ?: error("layout missing")).getBoundingBoxes(1, 2).single()
            val point = Offset((box.left + box.right) / 2f, (box.top + box.bottom) / 2f)
            scene.sendPointerEvent(
                PointerEventType.Press,
                point,
                timeMillis = 6_500L,
                type = PointerType.Touch,
            )
            Thread.sleep(20L)
            scene.render()
            scene.sendPointerEvent(
                PointerEventType.Release,
                point,
                timeMillis = 6_550L,
                type = PointerType.Touch,
            )
            assertEquals("show" to true, menuEvents.last())
            menuEvents.clear()

            selectionState.clearSelection()
            assertEquals(
                listOf("hide" to true),
                menuEvents,
                "ActionMode must close before the empty state can rebuild it as Select all only",
            )
            selectionState.detachSystemContextMenu(systemMenu)
        }
    }

    @Test
    fun semanticsCanSetAndCopyASourceSafeSelection() {
        val clipboard = RecordingClipboardManager()
        val source = "甲😀乙丁"
        var state: CjkSelectionState? = null

        ImageComposeScene(width = 320, height = 140) {
            val selectionState = rememberCjkSelectionState()
            state = selectionState
            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                CjkSelectionContainer(state = selectionState) {
                    CjkText(source, modifier = Modifier.width(300.dp), style = TextStyle(fontSize = 24.sp))
                }
            }
        }.use { scene ->
            scene.render()
            val semantics = scene.semanticsOwners.flatMap {
                it.getAllSemanticsNodes(mergingEnabled = false)
            }.single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == source &&
                    SemanticsActions.SetSelection in node.config
            }
            val setSelection = semantics.config[SemanticsActions.SetSelection].action
                ?: error("set-selection semantics action missing")
            assertTrue(
                SemanticsActions.CopyText !in semantics.config,
                "static text must not advertise Android ACTION_COPY before it owns a selection",
            )

            assertTrue(setSelection(2, 4, true))
            assertEquals("😀乙", state?.selectedText?.text)
            scene.render()

            val updatedSemantics = scene.semanticsOwners.flatMap {
                it.getAllSemanticsNodes(mergingEnabled = false)
            }.single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == source &&
                    SemanticsActions.SetSelection in node.config
            }
            assertEquals(
                ComposeTextRange(1, 4),
                updatedSemantics.config[SemanticsProperties.TextSelectionRange],
            )
            val copy = updatedSemantics.config[SemanticsActions.CopyText].action
                ?: error("copy semantics action missing")
            assertTrue(copy())
            assertEquals("😀乙", clipboard.recordedText?.text)
        }
    }

    @OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
    @Suppress("DEPRECATION")
    @Test
    fun semanticsPreserveLinksAndTtsAnnotationsForPlatformAccessibility() {
        val source = buildAnnotatedString {
            withLink(LinkAnnotation.Url("https://example.com/modern")) {
                append("链接")
            }
            append("正文")
            addUrlAnnotation(UrlAnnotation("https://example.com"), 0, 2)
            addTtsAnnotation(VerbatimTtsAnnotation("正文"), 2, 4)
        }

        ImageComposeScene(width = 320, height = 140) {
            CjkText(source, modifier = Modifier.width(300.dp), style = TextStyle(fontSize = 24.sp))
        }.use { scene ->
            scene.render()
            val exposed = scene.semanticsOwners.flatMap {
                it.getAllSemanticsNodes(mergingEnabled = false)
            }.single { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text == source.text
            }.config[SemanticsProperties.Text].single()

            assertEquals(1, exposed.getLinkAnnotations(0, exposed.length).size)
            assertEquals(1, exposed.getUrlAnnotations(0, exposed.length).size)
            assertEquals(1, exposed.getTtsAnnotations(0, exposed.length).size)
        }
    }

    @Test
    fun selectionContainerDoesNotExposeAnEmptyAccessibilityFocusStop() {
        ImageComposeScene(width = 320, height = 140) {
            CjkSelectionContainer {
                CjkText("无障碍正文", modifier = Modifier.width(300.dp), style = TextStyle(fontSize = 24.sp))
            }
        }.use { scene ->
            scene.render()
            val semantics = scene.semanticsOwners.flatMap {
                it.getAllSemanticsNodes(mergingEnabled = false)
            }

            assertFalse(
                semantics.any { node ->
                    SemanticsProperties.Focused in node.config &&
                        node.config.getOrNull(SemanticsProperties.Text).isNullOrEmpty()
                },
                "keyboard selection ownership must not add an unnamed accessibility focus stop",
            )
        }
    }

    @Test
    fun autoScrollVelocityRequiresAnArmedDragAndRampsAtBothEdges() {
        assertEquals(
            0f,
            selectionAutoScrollVelocity(
                armed = false,
                pointerY = 199f,
                viewportHeight = 200f,
                edgeSize = 40f,
                maxVelocity = 1_000f,
            ),
        )
        assertTrue(
            selectionAutoScrollVelocity(true, 10f, 200f, 40f, 1_000f) < 0f,
            "top edge must scroll backward",
        )
        assertTrue(
            selectionAutoScrollVelocity(true, 190f, 200f, 40f, 1_000f) > 0f,
            "bottom edge must scroll forward",
        )
        assertEquals(0f, selectionAutoScrollVelocity(true, 100f, 200f, 40f, 1_000f))
    }

    @Test
    fun touchDragAtViewportEdgeScrollsAndExtendsSelection() {
        val source = "甲乙丙丁戊己庚辛壬癸。".repeat(30)
        var state: CjkSelectionState? = null
        var scrollValue = 0
        var layout: LayoutResult? = null
        val shortLongPress = object : ViewConfiguration {
            override val longPressTimeoutMillis = 1L
            override val doubleTapTimeoutMillis = 300L
            override val doubleTapMinTimeMillis = 40L
            override val touchSlop = 8f
        }

        ImageComposeScene(width = 320, height = 160) {
            val selectionState = rememberCjkSelectionState()
            val scrollState = rememberScrollState()
            state = selectionState
            scrollValue = scrollState.value
            CompositionLocalProvider(LocalViewConfiguration provides shortLongPress) {
                CjkSelectionContainer(
                    modifier = Modifier.width(320.dp).height(160.dp),
                    state = selectionState,
                    scrollState = scrollState,
                ) {
                    Column(Modifier.verticalScroll(scrollState)) {
                        CjkText(
                            source,
                            modifier = Modifier.width(300.dp),
                            style = TextStyle(fontSize = 24.sp),
                            onTextLayout = { layout = it },
                        )
                    }
                }
            }
        }.use { scene ->
            val frameBase = System.nanoTime()
            scene.render(frameBase)
            val firstBox = (layout ?: error("layout missing")).getBoundingBoxes(1, 2).single()
            val start = Offset((firstBox.left + firstBox.right) / 2f, (firstBox.top + firstBox.bottom) / 2f)
            val edge = Offset(start.x, 158f)
            scene.sendPointerEvent(
                PointerEventType.Press,
                start,
                timeMillis = 7_000L,
                type = PointerType.Touch,
            )
            Thread.sleep(20L)
            scene.render(frameBase + 16_000_000L)
            scene.sendPointerEvent(
                PointerEventType.Move,
                edge,
                timeMillis = 7_025L,
                type = PointerType.Touch,
            )
            repeat(24) { frame ->
                scene.render(frameBase + (frame + 2L) * 16_000_000L)
            }
            scene.sendPointerEvent(
                PointerEventType.Release,
                edge,
                timeMillis = 7_450L,
                type = PointerType.Touch,
            )
            scene.render(frameBase + 450_000_000L)

            assertTrue(scrollValue > 0, "dragging inside the bottom edge band must scroll the viewport")
            assertTrue(
                (state?.selectedText?.length ?: 0) > 1,
                "selection must be refreshed against content moving under the stationary edge pointer",
            )
        }
    }

    @Test
    fun contextMenuBoundsTrackScrollAndUseTheVisibleViewport() {
        val source = "甲乙丙丁戊己庚辛壬癸。".repeat(20)
        var state: CjkSelectionState? = null
        var scrollState: ScrollState? = null
        var layout: LayoutResult? = null

        ImageComposeScene(width = 320, height = 160) {
            val selectionState = rememberCjkSelectionState()
            val scrolling = rememberScrollState()
            state = selectionState
            scrollState = scrolling
            CjkSelectionContainer(
                modifier = Modifier.width(320.dp).height(160.dp),
                state = selectionState,
                scrollState = scrolling,
            ) {
                Column(Modifier.verticalScroll(scrolling)) {
                    CjkText(
                        source,
                        modifier = Modifier.width(300.dp),
                        style = TextStyle(fontSize = 24.sp),
                        onTextLayout = { layout = it },
                    )
                }
            }
        }.use { scene ->
            scene.render()
            val result = layout ?: error("layout missing")
            val secondLine = result.lines.getOrNull(1) ?: error("second line missing")
            drag(
                scene,
                cursor(result, secondLine.range.start + 1),
                cursor(result, (secondLine.range.start + 4).coerceAtMost(secondLine.range.end)),
                startTime = 8_000L,
            )
            scene.render()
            val selectionState = state ?: error("selection state missing")
            val before = selectionState.selectionContentRectInRoot()
                ?: error("selected region should be visible before scrolling")

            val scrolling = scrollState ?: error("scroll state missing")
            runBlocking { scrolling.scrollTo(12) }
            // ImageComposeScene consumes the ScrollState change in one frame and publishes the
            // descendant's new global coordinates in the next. Read menu geometry only after both.
            scene.render()
            scene.render()
            val after = selectionState.selectionContentRectInRoot()
                ?: error("selected region should remain partially visible")
            assertTrue(after.top < before.top, "system menu anchor must move with selected content")
            assertTrue(
                kotlin.math.abs((before.top - after.top) - scrolling.value) < 1.5f,
                "menu anchor must consume the same scroll delta as Compose verticalScroll",
            )

            runBlocking { scrolling.scrollTo(secondLine.bottom.toInt() + 20) }
            scene.render()
            scene.render()
            assertEquals(
                null,
                selectionState.selectionContentRectInRoot(),
                "a selection fully clipped by the viewport must not publish a new menu anchor",
            )
        }
    }

    private fun cursor(result: LayoutResult, offset: Int): Offset {
        val caret = result.getCursorRect(offset)
        val line = result.lines[result.getLineForOffset(offset)]
        return Offset(caret.left, (line.top + line.bottom) / 2f)
    }

    private fun drag(scene: ImageComposeScene, start: Offset, end: Offset, startTime: Long) {
        val pressed = PointerButtons(isPrimaryPressed = true)
        scene.sendPointerEvent(
            PointerEventType.Press, start, timeMillis = startTime,
            buttons = pressed, button = PointerButton.Primary,
        )
        scene.sendPointerEvent(
            PointerEventType.Move, end, timeMillis = startTime + 50,
            buttons = pressed,
        )
        scene.sendPointerEvent(
            PointerEventType.Release, end, timeMillis = startTime + 100,
            buttons = PointerButtons(), button = PointerButton.Primary,
        )
    }

    private fun tap(
        scene: ImageComposeScene,
        position: Offset,
        pressTime: Long,
        releaseTime: Long,
    ) {
        scene.sendPointerEvent(
            PointerEventType.Press, position, timeMillis = pressTime,
            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary,
        )
        scene.sendPointerEvent(
            PointerEventType.Release, position, timeMillis = releaseTime,
            buttons = PointerButtons(), button = PointerButton.Primary,
        )
    }

    private class RecordingClipboardManager : ClipboardManager {
        var recordedText: AnnotatedString? = null

        override fun setText(annotatedString: AnnotatedString) {
            recordedText = annotatedString
        }

        override fun getText(): AnnotatedString? = recordedText
    }

    private class RecordingTextToolbar : TextToolbar {
        var hideCalls: Int = 0
        override var status: TextToolbarStatus = TextToolbarStatus.Shown

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            hideCalls++
            status = TextToolbarStatus.Hidden
        }
    }

    private class RecordingTextContextMenu : TextContextMenu {
        var manager: TextContextMenu.TextManager? = null

        @Composable
        override fun Area(
            textManager: TextContextMenu.TextManager,
            state: ContextMenuState,
            content: @Composable () -> Unit,
        ) {
            manager = textManager
            content()
        }
    }
}
