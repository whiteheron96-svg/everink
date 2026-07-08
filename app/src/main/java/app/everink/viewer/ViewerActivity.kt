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
import android.view.MotionEvent
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
import app.everink.core.annot.AnnotationWriter
import app.everink.core.render.PdfSession
import app.everink.core.store.DocumentStore
import com.artifex.mupdf.fitz.Rect
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * EverInk 뷰어 v1 — 기록 문서(document of record) 기반 보기 + 첫 주석 UX.
 *
 * 핵심 동작:
 *  - PDF를 열면 내부 documents/<id>/ 에 기록 문서로 들여온다(원본 무변경).
 *    같은 문서를 다시 열면 기존 기록 문서(누적 주석 포함)를 이어서 연다.
 *  - 페이지를 길게 누르면 그 위치에 메모(Square 주석)를 추가한다.
 *    저장은 DocumentStore.saveEdit = 백업 → temp 증분 저장 → 원자적 교체.
 *  - 암호 문서는 암호 입력, 손상 문서는 크래시 없이 안내.
 */
@SuppressLint("SetTextI18n", "NotifyDataSetChanged", "ClickableViewAccessibility")
class ViewerActivity : Activity() {

    private val pickPdf = 3001

    private lateinit var emptyView: LinearLayout
    private lateinit var recentList: LinearLayout
    private lateinit var pageList: ZoomableRecyclerView
    private lateinit var statusView: TextView

    private var session: PdfSession? = null
    private var renderExecutor: ExecutorService? = null
    private var currentStore: DocumentStore? = null
    private var currentName: String = ""
    private var currentRepaired: Boolean = false

    // 줌 확대 시 페이지를 몇 배 폭으로 렌더할지(1=기본, 2=고해상). 텍스트 선명도용.
    private var renderQuality = 1

    private val docsRoot: File by lazy { File(filesDir, "documents") }

    // 페이지 비트맵 캐시(기본 48MB, 고해상 렌더 시 2배로 확장). 축출분은 GC가 회수.
    private val pageCache = object : LruCache<Int, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        recentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

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
            addView(recentList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(Button(this@ViewerActivity).apply {
                text = "벤치마크·스파이크 도구"
                setOnClickListener {
                    startActivity(Intent(this@ViewerActivity, BenchmarkActivity::class.java))
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        pageList = ZoomableRecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ViewerActivity)
            setBackgroundColor(Color.rgb(0x22, 0x22, 0x22))
            visibility = View.GONE
            onSingleTap = { x, y -> handlePageTap(x, y) }
            onScaleSettled = { scale -> onZoomSettled(scale) }
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

        refreshRecent()

        // 외부 앱에서 "PDF 열기"로 진입한 경우
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { openFromUri(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeDocument()
    }

    // ---- 문서 열기 ----

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
            val name = queryName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "문서.pdf"
            val cached = copyToCache(uri)
            runOnUiThread {
                if (cached == null) {
                    toast("PDF를 읽지 못했습니다")
                } else {
                    openSource(cached, File(name).name)
                }
            }
        }.start()
    }

    /**
     * 원본 PDF를 기록 문서로 들여온 뒤 연다.
     * 같은 원본(내용 해시 동일)을 다시 열면 기존 기록 문서를 이어서 연다.
     */
    private fun openSource(source: File, displayName: String) {
        val dir = File(docsRoot, docIdOf(source))
        val store = DocumentStore(dir)
        val isNew = !store.document.exists()
        if (isNew) store.import(source)
        File(dir, "name.txt").writeText(displayName)
        source.delete()   // 캐시 사본은 더 이상 불필요
        val opened = openStore(store, displayName)
        // 방금 들여온 문서가 열리지 않으면 빈 기록 문서를 남기지 않는다
        if (!opened && isNew) {
            dir.deleteRecursively()
            refreshRecent()
        }
    }

    /** @return 문서를 여는 데 성공했으면 true (암호 대기 상태 포함) */
    private fun openStore(store: DocumentStore, displayName: String): Boolean {
        closeDocument()
        val s = try {
            PdfSession.open(store.document.absolutePath)
        } catch (t: Throwable) {
            toast("문서를 열 수 없습니다: ${t.message ?: t.javaClass.simpleName}")
            return false
        }
        currentStore = store
        currentName = displayName
        if (s.needsPassword()) {
            promptPassword(s)
            return true
        }
        showDocument(s)
        return true
    }

    private fun promptPassword(s: PdfSession) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "문서 암호"
        }
        AlertDialog.Builder(this)
            .setTitle("암호 보호 문서")
            .setView(input)
            .setPositiveButton("열기") { _, _ ->
                if (s.authenticate(input.text.toString())) {
                    showDocument(s)
                } else {
                    s.close()
                    toast("암호가 올바르지 않습니다")
                }
            }
            .setNegativeButton("취소") { _, _ -> s.close() }
            .setOnCancelListener { s.close() }
            .show()
    }

    private fun showDocument(s: PdfSession) {
        session = s
        renderExecutor = Executors.newSingleThreadExecutor()
        currentRepaired = s.wasRepaired()
        renderQuality = 1
        pageCache.resize(CACHE_BYTES)
        pageCache.evictAll()
        pageList.resetZoom()
        pageList.adapter = PageAdapter(s)
        emptyView.visibility = View.GONE
        pageList.visibility = View.VISIBLE
        statusView.visibility = View.VISIBLE
        statusView.text = statusText(s)
        if (currentRepaired) {
            toast("손상된 문서를 복구해 표시합니다. 내용이 불완전할 수 있습니다.")
        }
    }

    private fun statusText(s: PdfSession): String =
        "$currentName · ${s.pageCount}p · 길게=메모 · 메모 탭=보기" +
            if (currentRepaired) " · ⚠︎복구됨" else ""

    /** 배율 확정 시 렌더 품질(1x/2x 폭)을 전환하고 필요하면 재렌더한다. */
    private fun onZoomSettled(scale: Float) {
        val q = if (scale > 1.2f) 2 else 1
        if (q != renderQuality) {
            renderQuality = q
            pageCache.resize(CACHE_BYTES * q)
            pageCache.evictAll()
            pageList.adapter?.notifyDataSetChanged()
        }
    }

    /** 주석 저장 후 새 기록 문서로 세션을 다시 연다(스크롤 위치 유지). */
    private fun refreshDocument() {
        val store = currentStore ?: return
        val lm = pageList.layoutManager as LinearLayoutManager
        val pos = lm.findFirstVisibleItemPosition()
        renderExecutor?.shutdownNow()
        session?.close()
        val s = PdfSession.open(store.document.absolutePath)
        session = s
        renderExecutor = Executors.newSingleThreadExecutor()
        pageCache.evictAll()
        pageList.adapter = PageAdapter(s)
        if (pos > 0) lm.scrollToPosition(pos)
        statusView.text = statusText(s)
    }

    private fun closeDocument() {
        renderExecutor?.shutdownNow()
        renderExecutor = null
        pageList.adapter = null
        pageCache.evictAll()
        session?.close()
        session = null
        currentStore = null
    }

    // ---- 주석 추가 ----

    private fun promptAnnotation(page: Int, viewX: Float, viewY: Float, viewWidth: Int) {
        if (session == null || currentStore == null || viewWidth <= 0) return
        val input = EditText(this).apply { hint = "메모 내용" }
        AlertDialog.Builder(this)
            .setTitle("메모 추가 · ${page + 1}쪽")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                addAnnotation(page, viewX, viewY, viewWidth, input.text.toString().ifBlank { "메모" })
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun addAnnotation(page: Int, viewX: Float, viewY: Float, viewWidth: Int, text: String) {
        val s = session ?: return
        val store = currentStore ?: return
        statusView.text = "저장 중…"
        Thread {
            try {
                // 뷰 좌표(렌더폭=뷰폭) → PDF 포인트 좌표
                val b = s.pageBounds(page)
                val scale = viewWidth / b.width
                val x = (b.x0 + viewX / scale).coerceIn(b.x0, b.x1 - 40f)
                val y = (b.y0 + viewY / scale).coerceIn(b.y0, b.y1 - 24f)
                val rect = Rect(x, y, minOf(x + 220f, b.x1 - 4f), minOf(y + 80f, b.y1 - 4f))

                val result = store.saveEdit { tmpPath ->
                    AnnotationWriter.addSquare(tmpPath, page, text, rect)
                }
                runOnUiThread {
                    if (result.ok) {
                        refreshDocument()
                        toast("메모 저장됨 · 백업 ${store.backupCount()}세대 보관")
                    } else {
                        statusView.text = statusText(s)
                        toast("저장 실패: ${result.detail}")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("EverInkViewer", "annotation 저장 실패", t)
                runOnUiThread { toast("저장 실패: ${t.message ?: t.javaClass.simpleName}") }
            }
        }.start()
    }

    // ---- 주석 조회·수정·삭제 ----

    /** 단일탭(콘텐츠 좌표) → 페이지·주석 히트테스트 → 내용 다이얼로그. */
    private fun handlePageTap(contentX: Float, contentY: Float) {
        val s = session ?: return
        val store = currentStore ?: return
        val child = pageList.findChildViewUnder(contentX, contentY) ?: return
        val holder = pageList.getChildViewHolder(child) as? PageHolder ?: return
        val page = holder.boundPage
        if (page < 0 || child.width <= 0) return
        val xInPage = contentX - child.left
        val yInPage = contentY - child.top
        Thread {
            try {
                val b = s.pageBounds(page)
                val scale = child.width / b.width
                val pdfX = b.x0 + xInPage / scale
                val pdfY = b.y0 + yInPage / scale
                val hit = AnnotationWriter.list(store.document.absolutePath, page)
                    .lastOrNull { it.contains(pdfX, pdfY) } ?: return@Thread
                runOnUiThread { showAnnotationDialog(page, hit) }
            } catch (t: Throwable) {
                android.util.Log.w("EverInkViewer", "annotation 조회 실패", t)
            }
        }.start()
    }

    private fun showAnnotationDialog(page: Int, annot: AnnotationWriter.AnnotInfo) {
        AlertDialog.Builder(this)
            .setTitle("메모 · ${page + 1}쪽")
            .setMessage(annot.contents.ifBlank { "(내용 없음)" })
            .setPositiveButton("수정") { _, _ -> promptEditAnnotation(page, annot) }
            .setNegativeButton("삭제") { _, _ -> confirmDeleteAnnotation(page, annot) }
            .setNeutralButton("닫기", null)
            .show()
    }

    private fun promptEditAnnotation(page: Int, annot: AnnotationWriter.AnnotInfo) {
        val input = EditText(this).apply { setText(annot.contents) }
        AlertDialog.Builder(this)
            .setTitle("메모 수정 · ${page + 1}쪽")
            .setView(input)
            .setPositiveButton("저장") { _, _ ->
                applyAnnotationEdit("수정") { tmpPath ->
                    AnnotationWriter.updateContents(tmpPath, page, annot.index,
                        input.text.toString().ifBlank { "메모" })
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDeleteAnnotation(page: Int, annot: AnnotationWriter.AnnotInfo) {
        AlertDialog.Builder(this)
            .setTitle("메모 삭제")
            .setMessage("이 메모를 삭제할까요?\n\n\"${annot.contents}\"\n\n삭제 전 상태는 백업 세대에 보관됩니다.")
            .setPositiveButton("삭제") { _, _ ->
                applyAnnotationEdit("삭제") { tmpPath ->
                    AnnotationWriter.delete(tmpPath, page, annot.index)
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 백업→temp 증분→원자 교체 경로로 편집을 적용하고 화면을 갱신한다. */
    private fun applyAnnotationEdit(label: String, edit: (String) -> Unit) {
        val store = currentStore ?: return
        statusView.text = "저장 중…"
        Thread {
            try {
                val result = store.saveEdit(edit)
                runOnUiThread {
                    if (result.ok) {
                        refreshDocument()
                        toast("메모 $label 완료 · 백업 ${store.backupCount()}세대 보관")
                    } else {
                        session?.let { statusView.text = statusText(it) }
                        toast("$label 실패: ${result.detail}")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("EverInkViewer", "annotation $label 실패", t)
                runOnUiThread { toast("$label 실패: ${t.message ?: t.javaClass.simpleName}") }
            }
        }.start()
    }

    // ---- 최근 문서 ----

    private fun refreshRecent() {
        recentList.removeAllViews()
        val dirs = docsRoot.listFiles { f -> f.isDirectory && File(f, "doc_of_record.pdf").exists() }
            ?.sortedByDescending { File(it, "doc_of_record.pdf").lastModified() }
            ?.take(5)
            .orEmpty()
        if (dirs.isEmpty()) return
        recentList.addView(TextView(this).apply {
            text = "최근 문서"
            textSize = 13f
            setPadding(8, 40, 8, 8)
        })
        dirs.forEach { dir ->
            val name = File(dir, "name.txt").takeIf { it.exists() }?.readText()?.trim() ?: dir.name
            recentList.addView(Button(this).apply {
                text = name
                isAllCaps = false
                setOnClickListener { openStore(DocumentStore(dir), name) }
                setOnLongClickListener {
                    confirmDeleteRecent(dir, name)
                    true
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun confirmDeleteRecent(dir: File, name: String) {
        AlertDialog.Builder(this)
            .setTitle("문서 삭제")
            .setMessage("\"$name\" 기록 문서와 메모·백업을 모두 삭제할까요?\n원본 파일은 영향을 받지 않습니다.")
            .setPositiveButton("삭제") { _, _ ->
                if (dir.deleteRecursively()) {
                    toast("삭제됨: $name")
                } else {
                    toast("삭제 실패")
                }
                refreshRecent()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---- 유틸 ----

    /** 원본 내용 기반 문서 id(앞 64KB 해시 + 길이). 재열람 시 같은 기록 문서로 연결. */
    private fun docIdOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            val n = ins.read(buf)
            if (n > 0) digest.update(buf, 0, n)
        }
        digest.update(file.length().toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }.take(12)
    }

    private fun copyToCache(uri: Uri): File? {
        return try {
            val out = File(cacheDir, "import_${System.nanoTime()}.pdf")
            val copied = contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it) }
                true
            } ?: false
            if (copied) out else null
        } catch (e: Exception) {
            null
        }
    }

    private fun queryName(uri: Uri): String? = try {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- 페이지 리스트 ----

    private inner class PageHolder(val image: ImageView) : RecyclerView.ViewHolder(image) {
        var boundPage: Int = -1
        var lastTouchX: Float = 0f
        var lastTouchY: Float = 0f
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
            val holder = PageHolder(iv)
            iv.setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    holder.lastTouchX = ev.x
                    holder.lastTouchY = ev.y
                }
                false
            }
            iv.setOnLongClickListener {
                if (holder.boundPage >= 0) {
                    promptAnnotation(holder.boundPage, holder.lastTouchX, holder.lastTouchY, iv.width)
                }
                true
            }
            return holder
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
                    val bmp = pageCache.get(position)
                        ?: s.renderPage(position, width * renderQuality).also {
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

    companion object {
        private const val CACHE_BYTES = 48 * 1024 * 1024
    }
}
