package app.everink.viewer

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.os.Build
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
import app.everink.R
import app.everink.bench.BenchmarkActivity
import app.everink.ui.InkUi
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
    private lateinit var topBar: LinearLayout
    private lateinit var btnSearch: TextView
    private lateinit var btnGoto: TextView
    private lateinit var btnOutline: TextView
    private lateinit var btnInk: TextView
    private lateinit var btnInkSave: TextView
    private lateinit var btnInkCancel: TextView
    private lateinit var btnInkPen: TextView
    private lateinit var btnInkUndo: TextView
    private lateinit var btnClose: TextView
    private var lastQuery: String = ""
    // 페이지별 검색 일치(바깥=건, 안쪽=사각형들). PDF 포인트 좌표.
    private val searchHits = HashMap<Int, List<List<RectF>>>()

    // 줌 확대 시 페이지를 몇 배 폭으로 렌더할지(1=기본, 2=고해상). 텍스트 선명도용.
    private var renderQuality = 1

    private val docsRoot: File by lazy { File(filesDir, "documents") }

    // 필기 모드 상태: 확정 획(그린 순서 유지=undo용) + 진행 중 획 (좌표는 PDF 포인트)
    private data class InkStrokeData(
        val page: Int,
        val points: List<PointF>,
        val colorIdx: Int,
        val widthPts: Float,
    )

    private var inkMode = false
    private val inkDone = mutableListOf<InkStrokeData>()
    private var activeStroke: MutableList<PointF>? = null

    // 펜 설정: (이름, 미리보기 색, PDF 색)
    private val penColors = listOf(
        Triple("파랑", Color.rgb(31, 74, 217), floatArrayOf(0.12f, 0.29f, 0.85f)),
        Triple("빨강", Color.rgb(219, 38, 38), floatArrayOf(0.86f, 0.15f, 0.15f)),
        Triple("초록", Color.rgb(26, 153, 77), floatArrayOf(0.10f, 0.60f, 0.30f)),
        Triple("검정", Color.rgb(20, 20, 20), floatArrayOf(0.08f, 0.08f, 0.08f)),
    )
    private val penWidths = listOf("가늘게" to 1.5f, "보통" to 2.5f, "굵게" to 4.5f)
    private var penColorIdx = 0
    private var penWidthIdx = 1
    private var activePage = -1
    private var activeChildLeft = 0
    private var activeChildTop = 0
    private var activePxPerPt = 1f
    private var activeBounds: PdfSession.PageBounds? = null

    // 페이지 경계 캐시(어댑터가 채우고 필기 좌표 변환이 읽음)
    private val pageBoundsCache = HashMap<Int, PdfSession.PageBounds>()

    // 페이지 비트맵 캐시(기본 48MB, 고해상 렌더 시 2배로 확장). 축출분은 GC가 회수.
    private val pageCache = object : LruCache<Int, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)

        recentList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val pad = InkUi.dp(this, 24f)
        emptyView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(InkUi.color(this@ViewerActivity, R.color.paper_bg))
            setPadding(pad, pad, pad, pad)
            // 워드마크: "Ever"는 잉크 네이비, "Ink"는 앰버
            addView(TextView(this@ViewerActivity).apply {
                text = android.text.SpannableStringBuilder().apply {
                    append("Ever")
                    setSpan(android.text.style.ForegroundColorSpan(
                        InkUi.color(this@ViewerActivity, R.color.ink_primary)), 0, 4, 0)
                    append("Ink")
                    setSpan(android.text.style.ForegroundColorSpan(
                        InkUi.color(this@ViewerActivity, R.color.amber)), 4, 7, 0)
                }
                textSize = 34f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, InkUi.dp(this@ViewerActivity, 6f))
            })
            addView(TextView(this@ViewerActivity).apply {
                text = "주석이 절대 사라지지 않는 PDF 뷰어"
                textSize = 13.5f
                setTextColor(InkUi.color(this@ViewerActivity, R.color.ink_dim))
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, InkUi.dp(this@ViewerActivity, 36f))
            })
            addView(TextView(this@ViewerActivity).apply {
                text = "PDF 열기"
                textSize = 16f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(InkUi.color(this@ViewerActivity, R.color.btn_fg))
                gravity = Gravity.CENTER
                background = InkUi.pill(InkUi.color(this@ViewerActivity, R.color.btn_bg), 26f, this@ViewerActivity)
                setOnClickListener { openPicker() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, InkUi.dp(this@ViewerActivity, 52f)))
            addView(recentList, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(TextView(this@ViewerActivity).apply {
                text = "벤치마크·스파이크 도구"
                textSize = 12f
                setTextColor(InkUi.color(this@ViewerActivity, R.color.ink_dim))
                gravity = Gravity.CENTER
                setPadding(0, InkUi.dp(this@ViewerActivity, 40f), 0, 0)
                setOnClickListener {
                    startActivity(Intent(this@ViewerActivity, BenchmarkActivity::class.java))
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        pageList = ZoomableRecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ViewerActivity)
            setBackgroundColor(InkUi.color(this@ViewerActivity, R.color.viewer_bg))
            visibility = View.GONE
            onSingleTap = { x, y -> handlePageTap(x, y) }
            onScaleSettled = { scale -> onZoomSettled(scale) }
            onInkTouch = { ev -> handleInkTouch(ev) }
        }

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = InkUi.pill(InkUi.color(this@ViewerActivity, R.color.overlay_bg), 22f, this@ViewerActivity)
            visibility = View.GONE
            val px = InkUi.dp(this@ViewerActivity, 13f)
            val py = InkUi.dp(this@ViewerActivity, 9f)
            fun tool(label: String, onClick: () -> Unit) = TextView(this@ViewerActivity).apply {
                text = label
                textSize = 13.5f
                setTextColor(InkUi.color(this@ViewerActivity, R.color.overlay_fg))
                setPadding(px, py, px, py)
                setOnClickListener { onClick() }
            }
            btnSearch = tool("검색") { promptSearch() }
            btnGoto = tool("이동") { promptGoto() }
            btnOutline = tool("목차") { showOutline() }
            btnInk = tool("필기") { setInkMode(true) }
            btnInkPen = tool("펜") { promptPen() }
            btnInkUndo = tool("↩") { undoInkStroke() }
            btnInkSave = tool("저장") { saveInk() }
            btnInkCancel = tool("취소") { setInkMode(false) }
            btnClose = tool("✕") { closeToHome() }
            btnInkPen.visibility = View.GONE
            btnInkUndo.visibility = View.GONE
            btnInkSave.visibility = View.GONE
            btnInkCancel.visibility = View.GONE
            addView(btnSearch)
            addView(btnGoto)
            addView(btnOutline)
            addView(btnInk)
            addView(btnInkPen)
            addView(btnInkUndo)
            addView(btnInkSave)
            addView(btnInkCancel)
            addView(btnClose)
        }

        statusView = TextView(this).apply {
            textSize = 12f
            val hx = InkUi.dp(this@ViewerActivity, 14f)
            val hy = InkUi.dp(this@ViewerActivity, 7f)
            setPadding(hx, hy, hx, hy)
            background = InkUi.pill(InkUi.color(this@ViewerActivity, R.color.overlay_bg), 18f, this@ViewerActivity)
            setTextColor(InkUi.color(this@ViewerActivity, R.color.overlay_fg))
            visibility = View.GONE
        }

        root.addView(pageList, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(emptyView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val m = InkUi.dp(this, 10f)
        root.addView(statusView, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(m, m, m, m)
        })
        root.addView(topBar, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            setMargins(m, m, m, m)
        })

        // targetSdk 35 edge-to-edge: 도구바/상태 표시가 시스템 바와 겹치지 않게 인셋 적용
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val sb = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                top = sb.top
                bottom = sb.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            val m2 = InkUi.dp(this, 10f)
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = top + m2
            (statusView.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottom + m2
            topBar.requestLayout()
            statusView.requestLayout()
            insets
        }
        setContentView(root)

        refreshRecent()

        // 외부 앱에서 "PDF 열기" 또는 "공유"로 진입한 경우
        incomingUri(intent)?.let { openFromUri(it) }
    }

    /** ACTION_VIEW(열기)와 ACTION_SEND(공유) 인텐트에서 문서 URI를 꺼낸다. */
    private fun incomingUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        else -> null
    }

    /** 문서를 닫고 홈(최근 문서)으로 돌아간다. */
    private fun closeToHome() {
        closeDocument()
        pageList.visibility = View.GONE
        statusView.visibility = View.GONE
        emptyView.visibility = View.VISIBLE
        refreshRecent()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            inkMode -> setInkMode(false)          // 필기 중이면 필기만 종료
            session != null -> closeToHome()      // 문서 열람 중이면 문서 닫기
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 앱이 이미 떠 있는 상태에서 외부 "PDF 열기"/"공유"로 재진입한 경우
        incomingUri(intent)?.let { openFromUri(it) }
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
        searchHits.clear()
        lastQuery = ""
        pageBoundsCache.clear()
        if (inkMode) setInkMode(false)
        emptyView.visibility = View.GONE
        pageList.visibility = View.VISIBLE
        statusView.visibility = View.VISIBLE
        topBar.visibility = View.VISIBLE
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
        searchHits.clear()
        inkDone.clear()
        activeStroke = null
        activePage = -1
        if (::topBar.isInitialized) topBar.visibility = View.GONE
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

    // ---- 필기(잉크) ----

    private fun setInkMode(on: Boolean) {
        if (session == null && on) return
        inkMode = on
        pageList.inkMode = on
        btnSearch.visibility = if (on) View.GONE else View.VISIBLE
        btnGoto.visibility = if (on) View.GONE else View.VISIBLE
        btnOutline.visibility = if (on) View.GONE else View.VISIBLE
        btnInk.visibility = if (on) View.GONE else View.VISIBLE
        btnInkPen.visibility = if (on) View.VISIBLE else View.GONE
        btnInkUndo.visibility = if (on) View.VISIBLE else View.GONE
        btnInkSave.visibility = if (on) View.VISIBLE else View.GONE
        btnInkCancel.visibility = if (on) View.VISIBLE else View.GONE
        btnClose.visibility = if (on) View.GONE else View.VISIBLE
        if (!on) {
            inkDone.clear()
            activeStroke = null
            activePage = -1
            refreshInkOverlays()
        }
        if (on) {
            statusView.text = inkStatusText()
        } else {
            session?.let { statusView.text = statusText(it) }
        }
    }

    private fun inkStatusText(): String =
        "필기 모드 · ${penColors[penColorIdx].first}·${penWidths[penWidthIdx].first}"

    private fun promptPen() {
        val colorLabels = penColors.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("펜 색")
            .setSingleChoiceItems(colorLabels, penColorIdx) { d, which ->
                penColorIdx = which
                d.dismiss()
                val widthLabels = penWidths.map { it.first }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("펜 굵기")
                    .setSingleChoiceItems(widthLabels, penWidthIdx) { d2, w ->
                        penWidthIdx = w
                        d2.dismiss()
                        statusView.text = inkStatusText()
                    }
                    .show()
            }
            .show()
    }

    private fun undoInkStroke() {
        if (activeStroke != null) {
            activeStroke = null
        } else if (inkDone.isNotEmpty()) {
            inkDone.removeAt(inkDone.size - 1)
        } else {
            return
        }
        refreshInkOverlays()
    }

    private fun handleInkTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val child = pageList.findChildViewUnder(ev.x, ev.y) ?: return
                val holder = pageList.getChildViewHolder(child) as? PageHolder ?: return
                val page = holder.boundPage
                val b = pageBoundsCache[page] ?: return   // 아직 렌더 전이면 무시
                if (page < 0 || child.width <= 0) return
                activePage = page
                activeBounds = b
                activeChildLeft = child.left
                activeChildTop = child.top
                activePxPerPt = child.width / b.width
                activeStroke = mutableListOf(toPdfPoint(ev.x, ev.y))
                refreshInkOverlays()
            }
            MotionEvent.ACTION_MOVE -> {
                activeStroke?.add(toPdfPoint(ev.x, ev.y))
                refreshInkOverlays()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val stroke = activeStroke
                if (stroke != null && stroke.size >= 2) {
                    inkDone += InkStrokeData(activePage, stroke.toList(),
                        penColorIdx, penWidths[penWidthIdx].second)
                }
                activeStroke = null
                refreshInkOverlays()
            }
        }
    }

    private fun toPdfPoint(contentX: Float, contentY: Float): PointF {
        val b = activeBounds ?: return PointF(0f, 0f)
        val x = (b.x0 + (contentX - activeChildLeft) / activePxPerPt).coerceIn(b.x0, b.x1)
        val y = (b.y0 + (contentY - activeChildTop) / activePxPerPt).coerceIn(b.y0, b.y1)
        return PointF(x, y)
    }

    /** 화면에 보이는 페이지들의 필기 미리보기 오버레이를 갱신한다. */
    private fun refreshInkOverlays() {
        for (i in 0 until pageList.childCount) {
            val holder = pageList.getChildViewHolder(pageList.getChildAt(i)) as? PageHolder ?: continue
            holder.image.inkStrokes = inkStrokesFor(holder.boundPage)
        }
    }

    private fun inkStrokesFor(page: Int): List<PageImageView.InkPreview> {
        val done = inkDone.filter { it.page == page }.map {
            PageImageView.InkPreview(it.points, penColors[it.colorIdx].second, it.widthPts)
        }
        val active = activeStroke
        return if (active != null && page == activePage) {
            done + PageImageView.InkPreview(active.toList(),
                penColors[penColorIdx].second, penWidths[penWidthIdx].second)
        } else done
    }

    private fun saveInk() {
        val store = currentStore ?: return
        val groups = inkDone.groupBy { Triple(it.page, it.colorIdx, it.widthPts) }
            .map { (key, strokes) ->
                AnnotationWriter.InkGroup(
                    pageIndex = key.first,
                    strokes = strokes.map { it.points },
                    color = penColors[key.second].third,
                    strokeWidth = key.third,
                )
            }
        if (groups.isEmpty()) {
            toast("저장할 필기가 없습니다")
            return
        }
        statusView.text = "저장 중…"
        Thread {
            try {
                val result = store.saveEdit { tmpPath -> AnnotationWriter.addInk(tmpPath, groups) }
                runOnUiThread {
                    if (result.ok) {
                        setInkMode(false)
                        refreshDocument()
                        toast("필기 저장됨 · 백업 ${store.backupCount()}세대 보관")
                    } else {
                        session?.let { statusView.text = statusText(it) }
                        toast("저장 실패: ${result.detail}")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("EverInkViewer", "ink 저장 실패", t)
                runOnUiThread { toast("저장 실패: ${t.message ?: t.javaClass.simpleName}") }
            }
        }.start()
    }

    // ---- 검색 · 이동 · 목차 ----

    private fun promptSearch() {
        val s = session ?: return
        val input = EditText(this).apply {
            hint = "검색어"
            setText(lastQuery)
        }
        AlertDialog.Builder(this)
            .setTitle("문서 내 검색")
            .setView(input)
            .setPositiveButton("검색") { _, _ ->
                val q = input.text.toString().trim()
                if (q.isEmpty()) clearSearch() else runSearch(s, q)
            }
            .setNegativeButton("지우기") { _, _ -> clearSearch() }
            .setNeutralButton("취소", null)
            .show()
    }

    private fun runSearch(s: PdfSession, query: String) {
        lastQuery = query
        statusView.text = "검색 중…"
        Thread {
            val found = LinkedHashMap<Int, List<List<RectF>>>()
            try {
                val total = s.pageCount
                for (p in 0 until total) {
                    if (session !== s) return@Thread   // 문서가 바뀌면 중단
                    val hits = s.searchPage(p, query)
                    if (hits.isNotEmpty()) found[p] = hits
                    if (p % 200 == 199) runOnUiThread { statusView.text = "검색 중… ${p + 1}/$total" }
                }
            } catch (t: Throwable) {
                android.util.Log.w("EverInkViewer", "검색 실패", t)
            }
            runOnUiThread {
                if (session !== s) return@runOnUiThread
                searchHits.clear()
                searchHits.putAll(found)
                pageList.adapter?.notifyDataSetChanged()
                val totalHits = found.values.sumOf { it.size }
                statusView.text = statusText(s) +
                    if (totalHits > 0) " · 🔍${totalHits}건" else ""
                if (found.isEmpty()) {
                    toast("\"$query\" 일치 없음")
                } else {
                    showSearchResults(found)
                }
            }
        }.start()
    }

    private fun showSearchResults(found: Map<Int, List<List<RectF>>>) {
        val pages = found.keys.toList()
        val labels = pages.map { p -> "${p + 1}쪽 · ${found[p]!!.size}건" }
            .let { if (it.size > 200) it.take(200) + "… (${it.size - 200}쪽 더 있음)" else it }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("검색 결과 · ${found.values.sumOf { it.size }}건")
            .setItems(labels) { _, which ->
                if (which < pages.size) scrollToPage(pages[which])
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    private fun clearSearch() {
        lastQuery = ""
        searchHits.clear()
        pageList.adapter?.notifyDataSetChanged()
        session?.let { statusView.text = statusText(it) }
    }

    private fun promptGoto() {
        val s = session ?: return
        val input = EditText(this).apply {
            hint = "1 ~ ${s.pageCount}"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("쪽으로 이동")
            .setView(input)
            .setPositiveButton("이동") { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                scrollToPage((n - 1).coerceIn(0, s.pageCount - 1))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showOutline() {
        val s = session ?: return
        Thread {
            val items = s.outline()
            runOnUiThread {
                if (session !== s) return@runOnUiThread
                if (items.isEmpty()) {
                    toast("이 문서에는 목차가 없습니다")
                    return@runOnUiThread
                }
                val labels = items.map { o ->
                    "  ".repeat(o.depth) + o.title +
                        if (o.page >= 0) " · ${o.page + 1}쪽" else ""
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("목차")
                    .setItems(labels) { _, which ->
                        val page = items[which].page
                        if (page >= 0) scrollToPage(page)
                    }
                    .setNegativeButton("닫기", null)
                    .show()
            }
        }.start()
    }

    private fun scrollToPage(page: Int) {
        (pageList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(page, 0)
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
            setTextColor(InkUi.color(this@ViewerActivity, R.color.ink_dim))
            setPadding(InkUi.dp(this@ViewerActivity, 4f), InkUi.dp(this@ViewerActivity, 28f),
                0, InkUi.dp(this@ViewerActivity, 10f))
        })
        dirs.forEach { dir ->
            val name = File(dir, "name.txt").takeIf { it.exists() }?.readText()?.trim() ?: dir.name
            val pages = "길게 누르면 삭제"
            recentList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = InkUi.card(this@ViewerActivity,
                    InkUi.color(this@ViewerActivity, R.color.paper_surface),
                    InkUi.color(this@ViewerActivity, R.color.outline_soft))
                val cx = InkUi.dp(this@ViewerActivity, 16f)
                val cy = InkUi.dp(this@ViewerActivity, 13f)
                setPadding(cx, cy, cx, cy)
                addView(TextView(this@ViewerActivity).apply {
                    text = name
                    textSize = 15f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(InkUi.color(this@ViewerActivity, R.color.ink_strong))
                })
                addView(TextView(this@ViewerActivity).apply {
                    text = pages
                    textSize = 11.5f
                    setTextColor(InkUi.color(this@ViewerActivity, R.color.ink_dim))
                    setPadding(0, InkUi.dp(this@ViewerActivity, 2f), 0, 0)
                })
                setOnClickListener { openStore(DocumentStore(dir), name) }
                setOnLongClickListener {
                    confirmDeleteRecent(dir, name)
                    true
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(0, 0, 0, InkUi.dp(this@ViewerActivity, 8f))
            })
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

    private inner class PageHolder(val image: PageImageView) : RecyclerView.ViewHolder(image) {
        var boundPage: Int = -1
        var lastTouchX: Float = 0f
        var lastTouchY: Float = 0f
    }

    private inner class PageAdapter(val s: PdfSession) : RecyclerView.Adapter<PageHolder>() {

        override fun getItemCount(): Int = s.pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val iv = PageImageView(parent.context).apply {
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
            bindOverlay(holder, position)
            val cached = pageCache.get(position)
            if (cached != null && !cached.isRecycled) {
                holder.image.setImageBitmap(cached)
                return
            }

            // 자리표시자 높이: 페이지 비율 기준(스크롤 점프 방지)
            val width = if (pageList.width > 0) pageList.width else resources.displayMetrics.widthPixels
            val known = pageBoundsCache[position]
            holder.image.setImageBitmap(null)
            holder.image.minimumHeight =
                (((known?.let { it.height / it.width }) ?: 1.4142f) * width).toInt()

            renderExecutor?.execute {
                if (holder.boundPage != position || session !== s) return@execute
                try {
                    val b = pageBoundsCache[position] ?: s.pageBounds(position).also {
                        pageBoundsCache[position] = it
                    }
                    val bmp = pageCache.get(position)
                        ?: s.renderPage(position, width * renderQuality).also {
                            pageCache.put(position, it)
                        }
                    runOnUiThread {
                        if (holder.boundPage == position && !bmp.isRecycled) {
                            holder.image.minimumHeight = (b.height / b.width * width).toInt()
                            holder.image.setImageBitmap(bmp)
                            bindOverlay(holder, position)
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("EverInkViewer", "page $position render 실패", t)
                }
            }
        }

        /** 검색 하이라이트와 좌표 변환 정보를 뷰에 반영한다. */
        private fun bindOverlay(holder: PageHolder, position: Int) {
            val b = pageBoundsCache[position]
            if (b != null) {
                holder.image.pageX0 = b.x0
                holder.image.pageY0 = b.y0
                holder.image.pageWidthPts = b.width
            }
            holder.image.highlights = searchHits[position]?.flatten() ?: emptyList()
            holder.image.inkStrokes = inkStrokesFor(position)
        }

        override fun onViewRecycled(holder: PageHolder) {
            holder.boundPage = -1
            holder.image.setImageBitmap(null)
            holder.image.highlights = emptyList()
            holder.image.inkStrokes = emptyList()
        }
    }

    companion object {
        private const val CACHE_BYTES = 48 * 1024 * 1024
    }
}
