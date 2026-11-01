package jp.example.mangaorganizer

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.widget.*
import androidx.documentfile.provider.DocumentFile
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

class MainActivity : Activity() {
    private val pickSource = 100
    private val pickDestination = 101
    private var sourceTree: Uri? = null
    private var destinationTree: Uri? = null
    private var pdfs: List<DocumentFile> = emptyList()
    private var index = 0
    private var currentPdf: DocumentFile? = null

    private lateinit var status: TextView
    private lateinit var ocrText: TextView
    private lateinit var artistInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var sourceButton: Button
    private lateinit var destinationButton: Button

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
            text = "PDFの1ページ目を日本語OCRで読み取り、作画担当者を確認してフォルダへコピーします。"
            textSize = 14f
        })

        sourceButton = makeButton("① PDFの保存元フォルダを選択") { pickFolder(pickSource) }
        destinationButton = makeButton("② 整理先フォルダを選択") { pickFolder(pickDestination) }
        val scanButton = makeButton("③ PDFを読み込む") { loadPdfList() }
        titleInput = EditText(this).apply { hint = "作品名" }
        artistInput = EditText(this).apply { hint = "作画担当者（不明なら空欄）" }
        val copyButton = makeButton("このPDFを作画担当者フォルダへコピー") { copyCurrentPdf() }
        val nextButton = makeButton("次のPDFを読み取る") { processNext() }
        status = TextView(this).apply { text = "保存元と整理先を選択してください。" }
        ocrText = TextView(this).apply {
            text = "OCR結果がここに表示されます。"
            textSize = 13f
            setPadding(8, 12, 8, 12)
        }

        listOf<View>(
            sourceButton, destinationButton, scanButton, status, titleInput,
            artistInput, copyButton, nextButton,
            TextView(this).apply { text = "読み取った文字"; textSize = 18f },
            ocrText
        ).forEach { content.addView(it, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 10 }) }

        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        setContentView(root)
    }

    private fun makeButton(label: String, action: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { action() } }

    private fun pickFolder(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, requestCode)
    }

    @Deprecated("Storage Access Framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val flags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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
        val uri = sourceTree
        if (uri == null || destinationTree == null) {
            toast("保存元と整理先の両方を選択してください。")
            return
        }
        val folder = DocumentFile.fromTreeUri(this, uri)
        pdfs = folder?.listFiles()?.filter {
            it.isFile && ((it.type ?: "") == "application/pdf" ||
                (it.name ?: "").lowercase().endsWith(".pdf"))
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
        currentPdf = pdfs[index]
        val file = currentPdf ?: return
        status.text = "(${index + 1}/${pdfs.size}) ${file.name}"
        titleInput.setText(file.name?.removeSuffix(".pdf") ?: "")
        artistInput.setText("")
        ocrText.text = "PDFを読み取っています…"
        renderAndRecognize(file.uri)
    }

    private fun renderAndRecognize(uri: Uri) {
        try {
            val pfd: ParcelFileDescriptor =
                contentResolver.openFileDescriptor(uri, "r") ?: error("PDFを開けません")
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount == 0) error("ページがありません")
            val page = renderer.openPage(0)
            val scale = (1400f / page.width).coerceAtMost(3f)
            val bitmap = Bitmap.createBitmap(
                (page.width * scale).toInt().coerceAtLeast(1),
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            renderer.close()
            pfd.close()

            val recognizer = TextRecognition.getClient(
                JapaneseTextRecognizerOptions.Builder().build()
            )
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
        val outputName = safeName(title.ifBlank {
            originalName.removeSuffix(".pdf")
        }) + ".pdf"
        if (artistFolder.findFile(outputName) != null) {
            toast("同名ファイルがあるため中止しました。作品名を変更してください。")
            return
        }
        try {
            val output = artistFolder.createFile("application/pdf", outputName)
                ?: error("保存先ファイルを作成できません")
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

    private fun safeName(value: String): String {
        val cleaned = value.replace(Regex("[\\\\/:*?\\\"<>|]"), "_").trim().trim('.')
        return cleaned.take(80).ifBlank { "未分類" }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
