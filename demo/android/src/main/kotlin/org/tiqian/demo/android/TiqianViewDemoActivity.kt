package org.tiqian.demo.android

import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.tiqian.android.rendering.AndroidParagraphMeasurementSession
import org.tiqian.android.rendering.AndroidParagraphMeasurer
import org.tiqian.android.rendering.AndroidPrecomputedParagraph
import org.tiqian.android.view.CjkTextSurface
import org.tiqian.android.view.CjkSelectionDocument
import org.tiqian.android.view.CjkSelectionDocumentFragment
import org.tiqian.android.view.CjkSelectionRetentionHandle
import org.tiqian.android.view.CjkSelectionRetentionHost
import org.tiqian.android.view.CjkSelectionScrollHost
import org.tiqian.android.view.CjkTextOverflow
import org.tiqian.android.view.CjkTextView
import org.tiqian.android.view.CjkTextContent

/** Native View dogfood surface: no Compose interop and no platform text re-layout. */
class TiqianViewDemoActivity : ComponentActivity() {
    private val measurementSession = AndroidParagraphMeasurementSession()
    private var precomputeJob: Job? = null
    private var requestedLayoutWidth = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val benchmarkCase = intent.getStringExtra(BENCHMARK_CASE_EXTRA) ?: DEFAULT_BENCHMARK_CASE
        require(benchmarkCase in SUPPORTED_BENCHMARK_CASES) {
            "Unsupported View benchmark case: $benchmarkCase"
        }
        val selectAllForBenchmark = benchmarkCase == SELECTED_BENCHMARK_CASE

        // Page inset is part of this demo's paragraph width contract, not an overhang workaround.
        val pageMargin = dp(20)
        val verticalPadding = dp(16)
        val paragraphTextSize = sp(17f)
        val paragraphLineHeight = sp(27f)
        val benchmarkParagraphs = if (benchmarkCase == OVERHANG_BENCHMARK_CASE) {
            tiqianViewShowcaseSections(paragraphTextSize).flatten()
        } else {
            emptyList()
        }
        val paragraphs = List(VIEW_DEMO_PARAGRAPH_COUNT) { index ->
            ViewDemoParagraph(
                id = index.toLong(),
                content = if (benchmarkParagraphs.isEmpty()) {
                    tiqianViewDemoParagraph(index, paragraphTextSize, paragraphLineHeight)
                } else {
                    benchmarkParagraphs[index % benchmarkParagraphs.size]
                },
            )
        }
        val articleAdapter = ArticleAdapter(
            measurementSession = measurementSession,
            paragraphs = paragraphs,
            pageMargin = pageMargin,
            paintOverflowVisible = benchmarkCase == OVERHANG_BENCHMARK_CASE,
        )
        val article = RecyclerView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutManager = LinearLayoutManager(this@TiqianViewDemoActivity)
            adapter = articleAdapter
            clipToPadding = false
            setPadding(0, verticalPadding, 0, verticalPadding)
            addItemDecoration(ParagraphGapDecoration(dp(8)))
        }
        ViewCompat.setOnApplyWindowInsetsListener(article) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.updatePadding(
                left = safe.left,
                top = verticalPadding + safe.top,
                right = safe.right,
                bottom = verticalPadding + safe.bottom,
            )
            insets
        }
        val selectionContainer = CjkTextSurface(this).apply {
            document = CjkSelectionDocument(
                paragraphs.map { paragraph ->
                    CjkSelectionDocumentFragment(
                        key = paragraph.id,
                        content = paragraph.content,
                    )
                },
            )
            selectionScrollHost = object : CjkSelectionScrollHost {
                override fun scrollBy(deltaPx: Float): Float {
                    val before = article.computeVerticalScrollOffset()
                    article.scrollBy(0, deltaPx.toInt())
                    return (article.computeVerticalScrollOffset() - before).toFloat()
                }

                override fun viewportBoundsOnScreen(outBounds: Rect): Boolean =
                    article.getGlobalVisibleRect(outBounds)
            }
            selectionRetentionHost = CjkSelectionRetentionHost { key ->
                articleAdapter.retain(article, key)
            }
            addView(article)
        }
        setContentView(selectionContainer)
        article.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val width = view.width - view.paddingLeft - view.paddingRight - 2 * pageMargin
            if (width > 0 && width != requestedLayoutWidth) {
                requestedLayoutWidth = width
                prepareDocumentLayouts(
                    article = article,
                    adapter = articleAdapter,
                    paragraphs = paragraphs,
                    width = width,
                    selectionContainer = selectionContainer,
                    selectAllForBenchmark = selectAllForBenchmark,
                    readyMarker = "benchmark-ready-$benchmarkCase",
                )
            }
        }
        // Report from a real pre-draw traversal so StartupTiming can associate the signal with
        // the UI/RenderThread frame that actually presents the Tiqian surface.
        article.doOnPreDraw { reportFullyDrawn() }
    }

    private fun prepareDocumentLayouts(
        article: RecyclerView,
        adapter: ArticleAdapter,
        paragraphs: List<ViewDemoParagraph>,
        width: Int,
        selectionContainer: CjkTextSurface,
        selectAllForBenchmark: Boolean,
        readyMarker: String,
    ) {
        article.contentDescription = null
        precomputeJob?.cancel()
        precomputeJob = lifecycleScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val measurer = AndroidParagraphMeasurer(session = measurementSession)
                paragraphs.map { paragraph ->
                    ensureActive()
                    measurer.precompute(paragraph.content.layoutInput(maxWidth = width.toFloat()))
                }
            }
            if (requestedLayoutWidth != width) return@launch
            adapter.submitPrecomputedLayouts(width, prepared)
            if (selectAllForBenchmark) {
                check(selectionContainer.selectAll()) {
                    "the selected View benchmark requires a non-empty logical document"
                }
            }
            // Macrobenchmark scroll is a steady-state contract. Publish readiness only after the
            // document cache is ready, instead of timing lazy first-layout work as scroll replay.
            article.contentDescription = readyMarker
        }
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value.toFloat(),
        resources.displayMetrics,
    ).toInt()

    private fun sp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        value,
        resources.displayMetrics,
    )

    companion object {
        private const val BENCHMARK_CASE_EXTRA = "benchmark_case"
        private const val DEFAULT_BENCHMARK_CASE = "tiqian-view-article"
        private const val SELECTED_BENCHMARK_CASE = "tiqian-view-selected-article"
        private const val OVERHANG_BENCHMARK_CASE = "tiqian-view-overhang-article"
        private val SUPPORTED_BENCHMARK_CASES = setOf(
            DEFAULT_BENCHMARK_CASE,
            SELECTED_BENCHMARK_CASE,
            OVERHANG_BENCHMARK_CASE,
        )
    }

    private class ArticleAdapter(
        private val measurementSession: AndroidParagraphMeasurementSession,
        private val paragraphs: List<ViewDemoParagraph>,
        private val pageMargin: Int,
        private val paintOverflowVisible: Boolean,
    ) : RecyclerView.Adapter<ArticleViewHolder>() {
        private var precomputed: List<AndroidPrecomputedParagraph> = emptyList()

        init {
            setHasStableIds(true)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleViewHolder =
            ArticleViewHolder(
                CjkTextView(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    setPadding(pageMargin, 0, pageMargin, 0)
                    setMeasurementSession(measurementSession)
                    overflow = if (paintOverflowVisible) {
                        CjkTextOverflow.Visible
                    } else {
                        CjkTextOverflow.Clip
                    }
                    textIsSelectable = true
                },
            )

        override fun onBindViewHolder(holder: ArticleViewHolder, position: Int) {
            val paragraph = paragraphs[position]
            holder.boundId = paragraph.id
            holder.textView.bindSelectionFragment(
                key = paragraph.id,
                content = paragraph.content,
                retentionKey = paragraph.id,
            )
            precomputed.getOrNull(position)?.let { prepared ->
                check(holder.textView.submitPrecomputedLayout(prepared)) {
                    "precomputed paragraph contract diverged at position $position"
                }
            }
        }

        override fun getItemCount(): Int = paragraphs.size

        override fun getItemId(position: Int): Long = paragraphs[position].id

        override fun onViewRecycled(holder: ArticleViewHolder) {
            holder.textView.unbindSelectionFragment()
            holder.boundId = null
        }

        fun submitPrecomputedLayouts(width: Int, values: List<AndroidPrecomputedParagraph>) {
            require(values.size == paragraphs.size)
            require(values.all { it.result.input.constraints.maxWidth == width.toFloat() })
            precomputed = values
        }

        fun retain(article: RecyclerView, key: Any): CjkSelectionRetentionHandle {
            val id = key as? Long ?: error("View demo selection key must be a Long: $key")
            val holder = article.findViewHolderForItemId(id) as? ArticleViewHolder
                ?: error("Active selection endpoint is not attached: $id")
            holder.setIsRecyclable(false)
            var released = false
            return CjkSelectionRetentionHandle {
                if (!released) {
                    released = true
                    holder.setIsRecyclable(true)
                }
            }
        }
    }

    private class ArticleViewHolder(val textView: CjkTextView) : RecyclerView.ViewHolder(textView) {
        var boundId: Long? = null
    }

    private class ParagraphGapDecoration(private val gap: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: android.graphics.Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State,
        ) {
            outRect.bottom = gap
        }
    }
}

private data class ViewDemoParagraph(
    val id: Long,
    val content: CjkTextContent,
)
