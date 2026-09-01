package org.tiqian.demo.android

import android.graphics.Color
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

        // 页边距 = 段落 view 自身 padding：行尾悬挂标点画进页边距，不越 view 边界。
        val pageMargin = dp(20)
        val verticalPadding = dp(16)
        val paragraphTextSize = sp(17f)
        val paragraphLineHeight = sp(27f)
        val paragraphs = List(VIEW_DEMO_PARAGRAPH_COUNT) { index ->
            tiqianViewDemoParagraph(index, paragraphTextSize, paragraphLineHeight)
        }
        val articleAdapter = ArticleAdapter(
            measurementSession = measurementSession,
            paragraphs = paragraphs,
            pageMargin = pageMargin,
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
        setContentView(article)
        article.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val width = view.width - view.paddingLeft - view.paddingRight - 2 * pageMargin
            if (width > 0 && width != requestedLayoutWidth) {
                requestedLayoutWidth = width
                prepareDocumentLayouts(article, articleAdapter, paragraphs, width)
            }
        }
        // Report from a real pre-draw traversal so StartupTiming can associate the signal with
        // the UI/RenderThread frame that actually presents the Tiqian surface.
        article.doOnPreDraw { reportFullyDrawn() }
    }

    private fun prepareDocumentLayouts(
        article: RecyclerView,
        adapter: ArticleAdapter,
        paragraphs: List<CjkTextContent>,
        width: Int,
    ) {
        article.contentDescription = null
        precomputeJob?.cancel()
        precomputeJob = lifecycleScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val measurer = AndroidParagraphMeasurer(session = measurementSession)
                paragraphs.map { content ->
                    ensureActive()
                    measurer.precompute(content.layoutInput(maxWidth = width.toFloat()))
                }
            }
            if (requestedLayoutWidth != width) return@launch
            adapter.submitPrecomputedLayouts(width, prepared)
            // Macrobenchmark scroll is a steady-state contract. Publish readiness only after the
            // document cache is ready, instead of timing lazy first-layout work as scroll replay.
            article.contentDescription = READY_MARKER
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
        const val READY_MARKER = "benchmark-ready-tiqian-view-article"
    }

    private class ArticleAdapter(
        private val measurementSession: AndroidParagraphMeasurementSession,
        private val paragraphs: List<CjkTextContent>,
        private val pageMargin: Int,
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
                    textIsSelectable = true
                },
            )

        override fun onBindViewHolder(holder: ArticleViewHolder, position: Int) {
            holder.textView.content = paragraphs[position]
            precomputed.getOrNull(position)?.let { prepared ->
                check(holder.textView.submitPrecomputedLayout(prepared)) {
                    "precomputed paragraph contract diverged at position $position"
                }
            }
        }

        override fun getItemCount(): Int = paragraphs.size

        override fun getItemId(position: Int): Long = position.toLong()

        fun submitPrecomputedLayouts(width: Int, values: List<AndroidPrecomputedParagraph>) {
            require(values.size == paragraphs.size)
            require(values.all { it.result.input.constraints.maxWidth == width.toFloat() })
            precomputed = values
        }
    }

    private class ArticleViewHolder(val textView: CjkTextView) : RecyclerView.ViewHolder(textView)

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
