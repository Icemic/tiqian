package org.tiqian.demo.android

import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import org.tiqian.android.rendering.AndroidParagraphMeasurementSession
import org.tiqian.android.view.CjkTextOverflow
import org.tiqian.android.view.CjkTextSurface
import org.tiqian.android.view.CjkSelectionDocument
import org.tiqian.android.view.CjkSelectionDocumentFragment
import org.tiqian.android.view.CjkSelectionScrollHost
import org.tiqian.android.view.CjkTextView
import org.tiqian.android.view.CjkTextContent
import org.tiqian.core.TextStyle

/** Rich-text showcase surface: the Compose demo screen rendered by the View frontend. */
class TiqianViewShowcaseActivity : ComponentActivity() {
    private val measurementSession = AndroidParagraphMeasurementSession()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val textSize = sp(15f)
        // Compose blocks 语义：零间距堆叠保持跨段行距一致，节 = 一个空行高。
        val sectionGap = (textSize * 1.5f).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        val documentFragments = mutableListOf<CjkSelectionDocumentFragment>()
        var nextParagraphKey = 0
        fun addParagraph(paragraph: CjkTextContent, gapBefore: Int = 0) {
            val key = "showcase-${nextParagraphKey++}"
            documentFragments += CjkSelectionDocumentFragment(
                key = key,
                content = paragraph,
            )
            column.addView(
                CjkTextView(this).apply {
                    id = View.generateViewId()
                    setMeasurementSession(measurementSession)
                    overflow = CjkTextOverflow.Visible
                    textIsSelectable = true
                    bindSelectionFragment(key, paragraph)
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = gapBefore },
            )
        }

        // 实时重排：在输入框打字，下面这段跟着重新排版。输入归平台控件，排版归提椠。
        val draftView = CjkTextView(this).apply {
            id = View.generateViewId()
            setMeasurementSession(measurementSession)
            overflow = CjkTextOverflow.Visible
            textIsSelectable = true
            content = CjkTextContent(
                text = "在这里打字，看我实时重排；也可以拖选、双击并复制。",
                textStyle = TextStyle(fontSize = textSize),
            )
        }
        val input = EditText(this).apply {
            setText("在这里打字，看我实时重排；也可以拖选、双击并复制。")
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    draftView.content = CjkTextContent(
                        text = s?.toString() ?: "",
                        textStyle = TextStyle(fontSize = textSize),
                    )
                }
            })
        }
        column.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        column.addView(
            draftView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = sectionGap },
        )
        addParagraph(tiqianViewShowcaseUnderlineSample(textSize), gapBefore = sectionGap)
        tiqianViewShowcaseSections(textSize).forEach { section ->
            section.forEachIndexed { index, paragraph ->
                addParagraph(paragraph, gapBefore = if (index == 0) sectionGap else 0)
            }
        }

        val horizontalPadding = dp(24)
        val verticalPadding = dp(20)
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            clipToPadding = false
            clipChildren = false
            isFillViewport = true
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            addView(column)
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            )
            view.updatePadding(
                left = horizontalPadding + safe.left,
                top = verticalPadding + safe.top,
                right = horizontalPadding + safe.right,
                bottom = verticalPadding + safe.bottom,
            )
            insets
        }
        val selectionContainer = CjkTextSurface(this).apply {
            document = CjkSelectionDocument(documentFragments)
            selectionScrollHost = CjkSelectionScrollHost.forView(scroll)
            addView(scroll)
        }
        setContentView(selectionContainer)
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
}
