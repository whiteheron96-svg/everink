package app.everink.viewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.everink.bench.BenchmarkActivity
import app.everink.core.render.PdfSession
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * EverInk 뷰어 v0 — 세로 스크롤 연속 페이지 보기.
 *
 * 프로덕션 UX의 출발점:
 *  - SAF 또는 외부 ACTION_VIEW 인텐트로 PDF 열기
 *  - 페이지는 화면폭 기준으로 백그라운드 렌더 + LRU 캐시
 *  - 암호 문서는 암호 입력 후 열기
 *
 * 주석 편집·기록 문서(DocumentStore) 연동은 다음 단계.
 */
@SuppressLint("SetTextI18n", "NotifyDataSetChanged")
class ViewerActivity : Activity() {

    private val pickPdf = 3001

    private lateinit var emptyView: LinearLayout
    private lateinit var pageList: RecyclerView
    private lateinit var statusView: TextView

    private var session: PdfSession? = null
    private var renderExecutor: ExecutorService? = null

    // 페이지 비트맵 캐시(약 48MB). 축출분은 GC(NativeAllocationRegistry)가 회수.
    private val pageCache = object : LruCache<Int, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
            addView(TextView(this@ViewerActivity).apply {
                text = "EverInk"
                textSize = 28f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 8)
            })
            addView(TextView(this@ViewerActivity).apply {
                text = "주석이 절대 사라지지 않는 PDF 뷰어"
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 48)
            })
            addView(Button(this@ViewerActivity).apply {
                text = "PDF 열기"
                setOnClickListener { openPicker() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(Button(this@ViewerActivity).apply {
                text = "벤치마크·스파이크 도구"
                setOnClickListener {
                    startActivity(Intent(this@ViewerActivity, BenchmarkActivity::class.java))
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        pageList = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ViewerActivity)
            setBackgroundColor(Color.rgb(0x22, 0x22, 0x22))
            visibility = View.GONE
        }

        statusView = TextView(this).apply {
            textSize = 12f
            setPadding(24, 12, 24, 12)
            setBackgroundColor(Color.argb(0xAA, 0, 0, 0))
            setTextColor(Color.WHITE)
            visibility = View.GONE
        }

        root.addView(pageList, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(emptyView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(statusView, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        setContentView(root)

        // 외부 앱에서 "PDF 열기"로 진입한 경우
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { openFromUri(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeDocument()
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
            data?.data?.let { openFromUri(it) }
        }
    }

    private fun openFromUri(uri: Uri) {
        Thread {
            val cached = copyToCache(uri)
            runOnUiThread {
                if (cached == null) {
                    toast("PDF를 읽지 못했습니다")
                } else {
                    openFile(cached)
                }
            }
        }.start()
    }

    private fun openFile(file: File) {
        closeDocument()
        val s = try {
            PdfSession.open(file.absolutePath)
        } catch (t: Throwable) {
            toast("문서를 열 수 없습니다: ${t.message ?: t.javaClass.simpleName}")
            return
        }
        if (s.needsPassword()) {
            promptPassword(s, file.name)
            return
        }
        showDocument(s, file.name)
    }

    private fun promptPassword(s: PdfSession, name: String) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "문서 암호"
        }
        AlertDialog.Builder(this)
            .setTitle("암호 보호 문서")
            .setView(input)
            .setPositiveButton("열기") { _, _ ->
                if (s.authenticate(input.text.toString())) {
                    showDocument(s, name)
                } else {
                    s.close()
                    toast("암호가 올바르지 않습니다")
                }
            }
            .setNegativeButton("취소") { _, _ -> s.close() }
            .setOnCancelListener { s.close() }
            .show()
    }

    private fun showDocument(s: PdfSession, name: String) {
        session = s
        renderExecutor = Executors.newSingleThreadExecutor()
        pageCache.evictAll()
        pageList.adapter = PageAdapter(s)
        emptyView.visibility = View.GONE
        pageList.visibility = View.VISIBLE
        statusView.visibility = View.VISIBLE
        statusView.text = "$name · ${s.pageCount}p"
    }

    private fun closeDocument() {
        renderExecutor?.shutdownNow()
        renderExecutor = null
        pageList.adapter = null
        pageCache.evictAll()
        session?.close()
        session = null
    }

    private fun copyToCache(uri: Uri): File? = try {
        val name = File(queryName(uri) ?: "opened.pdf").name
        val out = File(cacheDir, "viewer_$name")
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- 페이지 리스트 ----

    private inner class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var boundPage: Int = -1
    }

    private inner class PageAdapter(val s: PdfSession) : RecyclerView.Adapter<PageHolder>() {

        private val aspectCache = HashMap<Int, Float>()

        override fun getItemCount(): Int = s.pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val iv = ImageView(parent.context).apply {
                adjustViewBounds = true
                setBackgroundColor(Color.WHITE)
                layoutParams = RecyclerView.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(0, 8, 0, 8)
                }
            }
            return PageHolder(iv)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            holder.boundPage = position
            val cached = pageCache.get(position)
            if (cached != null && !cached.isRecycled) {
                holder.image.setImageBitmap(cached)
                return
            }

            // 자리표시자 높이: 페이지 비율 기준(스크롤 점프 방지)
            val width = if (pageList.width > 0) pageList.width else resources.displayMetrics.widthPixels
            val ratio = aspectCache[position]
            holder.image.setImageBitmap(null)
            holder.image.minimumHeight = ((ratio ?: 1.4142f) * width).toInt()

            renderExecutor?.execute {
                if (holder.boundPage != position || session !== s) return@execute
                try {
                    val r = aspectCache[position] ?: s.pageAspectRatio(position).also {
                        aspectCache[position] = it
                    }
                    val bmp = pageCache.get(position) ?: s.renderPage(position, width).also {
                        pageCache.put(position, it)
                    }
                    runOnUiThread {
                        if (holder.boundPage == position && !bmp.isRecycled) {
                            holder.image.minimumHeight = (r * width).toInt()
                            holder.image.setImageBitmap(bmp)
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("EverInkViewer", "page $position render 실패", t)
                }
            }
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.boundPage = -1
            holder.image.setImageBitmap(null)
        }
    }
}
