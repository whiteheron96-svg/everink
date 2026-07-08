package app.everink.bench

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * 저장 파이프라인 스파이크 실행기.
 * 실행: adb shell am start -n app.everink/.bench.StorageActivity --ez autorun true
 * 결과: 화면 + Logcat(tag=EverInkStore)
 */
@SuppressLint("SetTextI18n")
class StorageActivity : Activity() {

    private lateinit var log: TextView
    private val pickPdf = 2001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val title = TextView(this).apply {
            text = "EverInk · 저장 파이프라인 스파이크"
            textSize = 18f
            setPadding(0, 0, 0, 24)
        }
        val pickBtn = Button(this).apply {
            text = "PDF 선택 후 저장 스파이크"
            setOnClickListener { openPicker() }
        }
        val scanBtn = Button(this).apply {
            text = "앱 폴더 PDF로 실행"
            setOnClickListener { scanAndRun() }
        }

        log = TextView(this).apply {
            textSize = 12f
            text = "준비됨.\n\nPDF를 직접 선택하거나 다음 위치에 PDF를 넣고 실행하세요:\n" +
                "${getExternalFilesDir(null)}/\n"
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }

        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(pickBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scanBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(ScrollView(this).apply {
            addView(log, MATCH_PARENT, MATCH_PARENT)
        }, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        if (intent?.getBooleanExtra("autorun", false) == true) {
            log.postDelayed({ scanAndRun() }, 300)
        }
    }

    private fun openPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
        }
        startActivityForResult(intent, pickPdf)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickPdf && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val cached = copyToCache(uri) ?: run {
                append("PDF 복사 실패\n")
                return
            }
            runSpike(cached)
        }
    }

    private fun scanAndRun() {
        val dir = getExternalFilesDir(null)
        val pdfs = dir?.listFiles { f -> f.extension.equals("pdf", true) }
            ?.sortedWith(compareBy<File> { if (it.name == "big_pages.pdf") 0 else 1 }.thenBy { it.name })
            .orEmpty()
        val base = pdfs.firstOrNull()
        if (base == null) {
            append("스캔 결과 PDF 없음.\nadb push <file>.pdf $dir/ 후 다시 실행.\n")
            return
        }
        runSpike(base)
    }

    private fun runSpike(base: File) {
        log.text = "저장 파이프라인 스파이크 시작\n대상: ${base.name}\n\n"
        Thread {
            val workDir = File(cacheDir, "storage_spike").apply { mkdirs() }
            val report = StorageSpike.run(base.absolutePath, workDir, sessions = 5)
            android.util.Log.i("EverInkStore", report.pretty())
            append("\n" + report.pretty())
        }.start()
    }

    private fun copyToCache(uri: Uri): File? = try {
        val name = File(queryName(uri) ?: "picked.pdf").name
        val out = File(cacheDir, "storage_$name")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        out
    } catch (e: Exception) {
        null
    }

    private fun queryName(uri: Uri): String? =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }

    private fun append(s: String) = runOnUiThread { log.append(s) }
}
