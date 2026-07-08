package app.everink.bench

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/**
 * MuPDF 렌더링 벤치마크 하네스 (스파이크).
 *
 * 실행 방법 2가지:
 *  1) 화면의 [PDF 선택] 버튼 → SAF로 PDF 고르기
 *  2) adb push 로 파일 배치 후 [폴더 스캔] 버튼:
 *     adb push test.pdf /sdcard/Android/data/app.everink/files/
 *
 * 결과는 화면 + Logcat(tag=EverInkBench)에 출력.
 */
@SuppressLint("SetTextI18n")
class BenchmarkActivity : Activity() {

    private lateinit var log: TextView
    private val pickPdf = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val title = TextView(this).apply {
            text = "EverInk · MuPDF 렌더링 벤치마크"
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val pickBtn = Button(this).apply {
            text = "PDF 선택 (SAF)"
            setOnClickListener { openPicker() }
        }
        val scanBtn = Button(this).apply {
            text = "앱 폴더 스캔 후 전체 벤치마크"
            setOnClickListener { scanAndRun() }
        }
        val storageBtn = Button(this).apply {
            text = "저장 파이프라인 스파이크 열기"
            setOnClickListener {
                startActivity(Intent(this@BenchmarkActivity, StorageActivity::class.java))
            }
        }

        log = TextView(this).apply {
            text = "준비됨.\n\n외부 대용량 PDF로 검증하려면:\n" +
                "adb push big.pdf ${getExternalFilesDir(null)}/\n후 [앱 폴더 스캔] 실행.\n"
            textSize = 12f
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply { addView(log) }

        root.addView(title, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(pickBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scanBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(storageBtn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)

        // 자동 실행 경로(adb 자동화용): am start ... --ez autorun true
        if (intent?.getBooleanExtra("autorun", false) == true) {
            log.postDelayed({ scanAndRun() }, 500)
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
            // SAF URI는 파일 경로가 아니므로 캐시로 복사 후 벤치(스파이크 편의).
            val cached = copyToCache(uri) ?: run { append("복사 실패"); return }
            runFiles(listOf(cached))
        }
    }

    private fun copyToCache(uri: Uri): File? = try {
        val name = queryName(uri) ?: "picked.pdf"
        val out = File(cacheDir, name)
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

    private fun scanAndRun() {
        val dir = getExternalFilesDir(null)
        val pdfs = dir?.listFiles { f -> f.extension.equals("pdf", true) }?.toList().orEmpty()
        if (pdfs.isEmpty()) {
            append("스캔 결과 PDF 없음.\nadb push <file>.pdf $dir/ 후 다시 실행.")
            return
        }
        runFiles(pdfs)
    }

    private fun runFiles(files: List<File>) {
        log.text = "벤치마크 시작 (${files.size}개)...\n\n"
        Thread {
            for (f in files) {
                append("… ${f.name} 렌더 중\n")
                val r = BenchmarkRunner.run(
                    path = f.absolutePath,
                    fileName = f.name,
                    fileSize = f.length(),
                    onProgress = { cur, total ->
                        if (cur % 200 == 0) append("   $cur/$total\n")
                    },
                )
                android.util.Log.i("EverInkBench", r.pretty())
                append("\n" + r.pretty() + "\n")
            }
            append("\n=== 완료 ===\n")
        }.start()
    }

    private fun append(s: String) = runOnUiThread {
        log.append(s)
    }
}
