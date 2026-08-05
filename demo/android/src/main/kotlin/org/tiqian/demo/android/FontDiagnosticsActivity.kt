package org.tiqian.demo.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一次性字体诊断界面：装上、打开、点「分享报告」发回即可，不需要 adb 或开发环境。
 */
class FontDiagnosticsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val report by produceState(initialValue = "正在采集…") {
                value = withContext(Dispatchers.Default) {
                    runCatching { FontDiagnosticsReport.collect() }
                        .getOrElse { t ->
                            "采集失败：${t::class.java.name}: ${t.message}\n\n${Log.getStackTraceString(t)}"
                        }
                }
            }
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        "提椠字体诊断",
                        style = MaterialTheme.typography.h6,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    Text(
                        "只读取字体信息，不修改任何设置，也不联网。把报告分享回来即可。",
                        style = MaterialTheme.typography.caption,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(bottom = 12.dp),
                    ) {
                        Button(onClick = { share(report) }) { Text("分享报告") }
                        Button(onClick = { copy(report) }) { Text("复制") }
                    }
                    Text(
                        text = report,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    )
                }
            }
        }
    }

    /**
     * 按文件分享，而不是塞进 EXTRA_TEXT：报告有几百 KB，消息应用会把长文本截断，
     * 截断后的报告拿回来还得再麻烦对方跑一次。
     */
    private fun share(report: String) {
        val uri = runCatching {
            val dir = File(cacheDir, "reports").apply { mkdirs() }
            val name = "tiqian-font-report-${Build.MANUFACTURER}-${Build.MODEL}-api${Build.VERSION.SDK_INT}"
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val file = File(dir, "$name.txt")
            file.writeText(report)
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

    private fun copy(report: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("提椠字体诊断报告", report))
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }
}
