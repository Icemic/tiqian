package org.tiqian.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * 装上、打开、点「分享报告」发回即可，不需要 adb 或开发环境。
 *
 * 纯 View 实现：这个 APK 要发给别人装，越小越好，也不想在老 OEM 设备上多引入一层 UI 框架。
 */
class FontDiagnosticsActivity : Activity() {

    private var report: String = ""
    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }

        root.addView(
            TextView(this).apply {
                text = "提椠字体诊断"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.BLACK)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "只读取字体信息，不修改任何设置，也不联网。采集完成后点「分享报告」发回即可。"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.DKGRAY)
                setPadding(0, dp(4), 0, dp(12))
            },
        )

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val shareButton = Button(this).apply {
            text = "分享报告"
            isEnabled = false
            setOnClickListener { share() }
        }
        val copyButton = Button(this).apply {
            text = "复制"
            isEnabled = false
            setOnClickListener { copy() }
        }
        buttons.addView(shareButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        buttons.addView(copyButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        root.addView(buttons)

        body = TextView(this).apply {
            text = "正在采集…"
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
            gravity = Gravity.START
        }
        root.addView(
            ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT),
        )

        setContentView(root, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        @Suppress("DEPRECATION")
        object : AsyncTask<Void, Void, String>() {
            override fun doInBackground(vararg params: Void?): String =
                runCatching { FontDiagnosticsReport.collect() }
                    .getOrElse { t -> "采集失败：${t::class.java.name}: ${t.message}\n\n${t.stackTraceToString()}" }

            override fun onPostExecute(result: String) {
                report = result
                body.text = result
                shareButton.isEnabled = true
                copyButton.isEnabled = true
            }
        }.execute()
    }

    /**
     * 按文件分享而不是塞进 `EXTRA_TEXT`：报告有一百多 KB，消息应用会截断长文本，
     * 截断后的报告拿回来还得再麻烦对方跑一次。
     */
    private fun share() {
        val uri = runCatching {
            val dir = File(cacheDir, "reports").apply { mkdirs() }
            val name = "tiqian-font-report-${Build.MANUFACTURER}-${Build.MODEL}-api${Build.VERSION.SDK_INT}"
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            File(dir, "$name.txt").apply { writeText(report) }
        }.mapCatching { file ->
            FileProvider.getUriForFile(this, "$packageName.reports", file)
        }.getOrElse { t ->
            Toast.makeText(this, "写入报告文件失败：${t.message}", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "提椠字体诊断报告")
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "分享报告",
            ),
        )
    }

    private fun copy() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("提椠字体诊断报告", report))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
