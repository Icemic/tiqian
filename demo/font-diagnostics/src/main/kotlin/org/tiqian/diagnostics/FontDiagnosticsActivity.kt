package org.tiqian.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

/**
 * 给非技术协作者使用的单页收集界面。技术字段只进入 ZIP，不要求发送者理解报告格式。
 * 纯 View 保持 APK 小，并覆盖 API 23 起的 OEM 设备。
 */
class FontDiagnosticsActivity : Activity() {

    private var evidence: CollectedFontEvidence? = null
    private lateinit var progress: ProgressBar
    private lateinit var statusTitle: TextView
    private lateinit var statusDetail: TextView
    private lateinit var sendButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()

        val pagePadding = dp(20)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(pagePadding, pagePadding, pagePadding, pagePadding)
        }
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPadding(
                pagePadding + safe.left,
                pagePadding + safe.top,
                pagePadding + safe.right,
                pagePadding + safe.bottom,
            )
            insets
        }

        page.addView(
            TextView(this).apply {
                text = getString(R.string.screen_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(COLOR_TEXT)
            },
        )
        page.addView(
            TextView(this).apply {
                text = getString(R.string.intro)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(COLOR_SECONDARY_TEXT)
                setLineSpacing(0f, 1.18f)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(10)
            },
        )

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(2).toFloat()
        }
        progress = ProgressBar(this).apply { isIndeterminate = true }
        statusCard.addView(
            progress,
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT),
        )
        statusTitle = TextView(this).apply {
            text = getString(R.string.collecting_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            gravity = Gravity.CENTER
        }
        statusCard.addView(
            statusTitle,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(14)
            },
        )
        statusDetail = TextView(this).apply {
            text = getString(R.string.collecting_detail)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(COLOR_SECONDARY_TEXT)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.12f)
        }
        statusCard.addView(
            statusDetail,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            },
        )
        sendButton = Button(this).apply {
            text = getString(R.string.send_result)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setAllCaps(false)
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(COLOR_ACTION)
            minHeight = dp(52)
            visibility = View.GONE
            setOnClickListener { share() }
        }
        statusCard.addView(
            sendButton,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(18)
            },
        )
        page.addView(
            statusCard,
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(24)
                leftMargin = dp(4)
                rightMargin = dp(4)
            },
        )

        page.addView(
            TextView(this).apply {
                text = getString(R.string.privacy_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(COLOR_TEXT)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(26)
            },
        )
        page.addView(
            TextView(this).apply {
                text = getString(R.string.privacy_body)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(COLOR_SECONDARY_TEXT)
                setLineSpacing(0f, 1.15f)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(6)
            },
        )
        page.addView(
            TextView(this).apply {
                text = getString(R.string.provider_label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(COLOR_TERTIARY_TEXT)
            },
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(28)
            },
        )

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipChildren = false
            clipToPadding = false
            setBackgroundColor(COLOR_PAGE)
            addView(page, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        WindowInsetsControllerCompat(window, scroll).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.requestApplyInsets(page)

        collectEvidence()
    }

    private fun collectEvidence() {
        Thread {
            val result = runCatching {
                val dir = File(cacheDir, "reports").apply { mkdirs() }
                AndroidFontEvidenceCollector.collect(dir)
            }
            runOnUiThread {
                result.fold(
                    onSuccess = { collected ->
                        evidence = collected
                        progress.visibility = View.GONE
                        statusTitle.text = getString(R.string.complete_title)
                        statusDetail.text = getString(R.string.complete_detail)
                        sendButton.visibility = View.VISIBLE
                    },
                    onFailure = { error ->
                        progress.visibility = View.GONE
                        statusTitle.text = getString(R.string.failure_title)
                        statusDetail.text = getString(
                            R.string.failure_detail,
                            error::class.java.simpleName,
                        )
                    },
                )
            }
        }.start()
    }

    private fun share() {
        val collected = evidence ?: return
        val uri = runCatching {
            FileProvider.getUriForFile(this, "$packageName.reports", collected.bundleFile)
        }.getOrElse {
            Toast.makeText(this, getString(R.string.bundle_read_failed), Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject))
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, collected.bundleFile.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.share_chooser_title),
            ),
        )
    }

    @Suppress("DEPRECATION")
    private fun configureEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private companion object {
        val COLOR_PAGE = Color.rgb(247, 247, 247)
        val COLOR_TEXT = Color.rgb(28, 28, 30)
        val COLOR_SECONDARY_TEXT = Color.rgb(92, 92, 97)
        val COLOR_TERTIARY_TEXT = Color.rgb(142, 142, 147)
        val COLOR_ACTION = Color.rgb(34, 99, 210)
    }
}
