package jp.example.mangaorganizer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Xml
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import org.xmlpull.v1.XmlPullParser
import java.net.URL
import java.net.URLEncoder

private data class NdlCandidate(val title: String, val creators: List<String>, val link: String) {
    fun label() = "$title\n${creators.joinToString("、").ifBlank { "著者情報なし" }}"
}

class MainActivity : Activity() {
    private val pickSource = 100
    private val pickDestination = 101
    private var sourceTree: Uri? = null
    private var destinationTree: Uri? = null
    private var pdfs: List<DocumentFile> = emptyList()
    private var index = 0
    private var currentPdf: DocumentFile? = null
    private var candidates: List<NdlCandidate> = emptyList()

    private lateinit var status: TextView
    private lateinit var ocrText: TextView
    private lateinit var candidateInfo: TextView
    private lateinit var candidateSpinner: Spinner
    private lateinit var artistInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var sourceButton: Button
    private lateinit var destinationButton: Button
    private lateinit var searchButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(TextView(this).apply {
            text = "Manga Organizer"
            textSize = 24f
        })
        content.addView(TextView(this).apply {
            text = "PDFの1ページ目を日本語OCRで読み取り、作品名からNDL Searchで書誌候補を検索します。作画担当者は内容を確認して入力してください。"
            textSize = 14f
        })

        sourceButton = button("① PDFの保存元フォルダを選択") { pickFolder(pickSource) }
        destinationButton = button("② 整理先フォルダを選択") { pickFolder(pickDestination) }
        val loadButton = button("③ PDFを読み込む") { loadPdfList() }
        status = TextView(this).apply { text = "保存元と整理先を選択してください。" }
        titleInput = EditText(this).apply { hint = "作品名（検索語）" }
        searchButton = button("④ 作品名でNDL Searchを検索") { searchNdl() }
        candidateSpinner = Spinner(this)
        candidateInfo = TextView(this).apply {
            text = "検索候補の情報がここに表示されます。"
            textSize = 13f
        }
        val useCreator = button("候補の著者情報を作画担当者欄に仮入力") {
            val creator = candidates.getOrNull(candidateSpinner.selectedItemPosition)?.creators?.firstOrNull()
            if (creator.isNullOrBlank()) toast("著者情報がありません。作画担当者を手入力してください。")
            else {
                artistInput.setText(creator)
                toast("著者情報を仮入力しました。作画担当者と一致するか必ず確認してください。")
            }
        }
        artistInput = EditText(this).apply { hint = "作画担当者（不明なら空欄→未分類）" }
        val copyButton = button("このPDFを作画担当者フォルダへコピー") { copyCurrentPdf() }
        val nextButton = button("次のPDFを読み取る") { processNext() }
        ocrText = TextView(this).apply {
            text = "OCR結果がここに表示されます。"
            textSize = 13f
            setPadding(8, 12, 8, 12)
        }

        val views = listOf<View>(
            sourceButton, destinationButton, loadButton, status,
            TextView(this).apply { text = "作品名" }, titleInput, searchButton,
            TextView(this).apply { text = "NDL Search候補（作品名・著者情報）" },
            candidateSpinner, candidateInfo, useCreator,
            TextView(this).apply { text = "作画担当者" }, artistInput,
            copyButton, nextButton,
            TextView(this).apply { text = "読み取った文字（OCR）"; textSize = 18f }, ocrText
        )
        views.forEach { content.addView(it, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 10 }) }

        candidateSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                candidateInfo.text = "候補が選択されていません。"
            }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val c = candidates.getOrNull(position)
                candidateInfo.text = if (c == null) "候補なし" else
                    "作品名：${c.title}\n著者情報：${c.creators.joinToString("、").ifBlank { "記載なし" }}\n${c.link}\n\n注意：NDLの著者情報は原作者・編者等の場合があり、作画担当者とは限りません。"
            }
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { action() } }

    private fun pickFolder(code: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, code)
    }

    @Deprecated("Storage Access Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        contentResolver.takePersistableUriPermission(uri, flags)
        if (requestCode == pickSource) {
            sourceTree = uri
            sourceButton.text = "保存元フォルダ：選択済み"
        } else if (requestCode == pickDestination) {
            destinationTree = uri
            destinationButton.text = "整理先フォルダ：選択済み"
        }
    }

    private fun loadPdfList() {
        if (sourceTree == null || destinationTree == null) {
            toast("保存元と整理先の両方を選択してください。")
            return
        }
        pdfs = DocumentFile.fromTreeUri(this, sourceTree!!)?.listFiles()?.filter {
            it.isFile && ((it.type ?: "") == "application/pdf" || (it.name ?: "").lowercase().endsWith(".pdf"))
        } ?: emptyList()
        index = 0
        status.text = "${pdfs.size}件のPDFが見つかりました。"
        if (pdfs.isEmpty()) toast("選択フォルダ直下にPDFがありません。") else processNext()
    }

    private fun processNext() {
        if (index >= pdfs.size) {
            currentPdf = null
            status.text = "確認完了（${pdfs.size}件）。"
            return
        }
        val file = pdfs[index]
        currentPdf = file
        status.text = "(${index + 1}/${pdfs.size}) ${file.name}"
        titleInput.setText(file.name?.removeSuffix(".pdf") ?: "")
        artistInput.setText("")
        candidates = emptyList()
        candidateInfo.text = "作品名でNDL Searchを検索してください。"
        setCandidateList(listOf("検索候補はありません"))
        ocrText.text = "PDFを読み取っています…"
        renderAndRecognize(file.uri)
    }

    private fun setCandidateList(labels: List<String>) {
        candidateSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun searchNdl() {
        val query = titleInput.text.toString().trim()
        if (query.isBlank()) {
            toast("作品名を入力してください。")
            return
        }
        searchButton.isEnabled = false
        status.text = "NDL Searchを検索しています…"
        Thread {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://ndlsearch.ndl.go.jp/api/opensearch?title=$encoded&cnt=10")
                val connection = url.openConnection().apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "MangaOrganizer/0.2 Android")
                }
                val found = connection.getInputStream().use { parseNdl(it) }
                runOnUiThread {
                    candidates = found
                    setCandidateList(if (found.isEmpty()) listOf("候補が見つかりませんでした") else found.map { it.label() })
                    status.text = "NDL Search：${found.size}件の候補が見つかりました。"
                    searchButton.isEnabled = true
                    if (found.isEmpty()) candidateInfo.text = "別の表記や短い作品名でも検索してください。"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "NDL Search検索エラー：${e.localizedMessage ?: "通信に失敗しました"}"
                    searchButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun parseNdl(input: java.io.InputStream): List<NdlCandidate> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, "UTF-8")
        val result = mutableListOf<NdlCandidate>()
        var inItem = false
        var title = ""
        var link = ""
        val creators = mutableListOf<String>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':')
                    if (name == "item") {
                        inItem = true
                        title = ""
                        link = ""
                        creators.clear()
                    } else if (inItem && name == "title") {
                        title = parser.nextText().trim()
                    } else if (inItem && name == "creator") {
                        parser.nextText().trim().takeIf { it.isNotBlank() }?.let { creators.add(it) }
                    } else if (inItem && name == "link") {
                        link = parser.getAttributeValue(null, "href") ?: parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name.substringAfter(':') == "item" && inItem) {
                    if (title.isNotBlank()) result.add(NdlCandidate(title, creators.toList(), link))
                    inItem = false
                }
            }
            event = parser.next()
        }
        return result
    }

    private fun renderAndRecognize(uri: Uri) {
        try {
            val pfd: ParcelFileDescriptor = contentResolver.openFileDescriptor(uri, "r") ?: error("PDFを開けません")
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) error("ページがありません")
            val page = renderer.openPage(0)
            val scale = (1400f / page.width).coerceAtMost(3f)
            val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            pfd.close()
            val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    ocrText.text = result.text.ifBlank { "文字を認識できませんでした。" }
                    bitmap.recycle()
                    recognizer.close()
                }
                .addOnFailureListener { e ->
                    ocrText.text = "OCRエラー：${e.localizedMessage}"
                    bitmap.recycle()
                    recognizer.close()
                }
        } catch (e: Exception) {
            ocrText.text = "PDF読み取りエラー：${e.localizedMessage}"
        }
    }

    private fun copyCurrentPdf() {
        val file = currentPdf
        val destUri = destinationTree
        if (file == null || destUri == null) {
            toast("対象PDFまたは整理先がありません。")
            return
        }
        val folderName = safeName(artistInput.text.toString().trim().ifBlank { "未分類" })
        val title = titleInput.text.toString().trim()
        val parent = DocumentFile.fromTreeUri(this, destUri) ?: return
        val artistFolder = parent.findFile(folderName) ?: parent.createDirectory(folderName)
        if (artistFolder == null) {
            toast("フォルダを作成できませんでした。")
            return
        }
        val originalName = file.name ?: "document.pdf"
        val outputName = safeName(title.ifBlank { originalName.removeSuffix(".pdf") }) + ".pdf"
        if (artistFolder.findFile(outputName) != null) {
            toast("同名ファイルがあるため中止しました。作品名を変更してください。")
            return
        }
        try {
            val output = artistFolder.createFile("application/pdf", outputName) ?: error("保存先ファイルを作成できません")
            contentResolver.openInputStream(file.uri).use { input ->
                contentResolver.openOutputStream(output.uri).use { out ->
                    if (input == null || out == null) error("ファイルを開けません")
                    input.copyTo(out)
                }
            }
            toast("コピーしました：$folderName/$outputName")
            index++
            processNext()
        } catch (e: Exception) {
            toast("コピー失敗：${e.localizedMessage}")
        }
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[\\\\/:*?\\\"<>|]"), "_").trim().trim('.').take(80).ifBlank { "未分類" }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
