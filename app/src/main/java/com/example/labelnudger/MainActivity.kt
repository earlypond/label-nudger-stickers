
package com.example.labelnudger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintJob
import android.print.PrintManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.labelnudger.ui.theme.LabelNudgerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

private const val STICKERS_JSON_URL = "https://blueislandpress.github.io/label-nudger-stickers/index.json"

private data class StickerItem(
    val name: String,
    val url: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LabelNudgerTheme {
                LabelNudgerApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
fun LabelNudgerApp(modifier: Modifier = Modifier) {
    Surface(modifier = modifier) {
        PrintScreen()
    }
}

@Composable
fun PrintScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    var shiftXmm by rememberSaveable { mutableStateOf(0.0) }
    var shiftYmm by rememberSaveable { mutableStateOf(0.0) }
    val stepMm = 0.5

    var copies by rememberSaveable { mutableStateOf("1") }
    var activePrintJob by rememberSaveable { mutableStateOf<PrintJob?>(null) }

    var selectedPdfUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var wizardStep by rememberSaveable { mutableStateOf(0) } // 0=Select PDF, 1=Nudge & Print, 2=Stickers
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        selectedPdfUri = uri
        if (uri != null) wizardStep = 1
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't allow persistable permissions; ignore.
            }
        }
    }

    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val extraTopPadding = (screenHeightDp.dp * 0.05f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                bottom = 16.dp,
                top = 16.dp + extraTopPadding
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Top row: Cancel Print and Back (no Cancel)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Cancel the currently running print job (if any)
            if (activePrintJob != null) {
                Button(onClick = {
                    try {
                        activePrintJob?.cancel()
                    } catch (_: Exception) {
                    }
                    activePrintJob = null
                }) {
                    Text("Cancel Print")
                }
            }

            // Back button on any step except the first
            if (wizardStep != 0) {
                Button(onClick = { wizardStep = 0 }) {
                    Text("Back")
                }
            }
        }

        Text(text = "Label Nudger")
        Text(text = "Global calibration applies to all PDFs")

        if (wizardStep == 0) {
            Text(text = "Step 1 of 2: Choose a PDF")

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Select PDF from phone")
            }

            Button(
                onClick = { wizardStep = 2 },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stickers")
            }

            Text(text = if (selectedPdfUri == null) "No PDF selected" else "PDF selected ✅")

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Tip: Use Stickers to pick a name (downloaded automatically).")
        } else if (wizardStep == 2) {
            Text(text = "Stickers")

            val stickersState = remember { mutableStateOf<List<StickerItem>>(emptyList()) }
            val loadingState = remember { mutableStateOf(true) }
            val errorState = remember { mutableStateOf<String?>(null) }

            LaunchedEffect(Unit) {
                loadingState.value = true
                errorState.value = null
                try {
                    val list = fetchStickersList(STICKERS_JSON_URL)
                    stickersState.value = list
                } catch (e: Exception) {
                    errorState.value = e.message ?: "Could not load stickers"
                } finally {
                    loadingState.value = false
                }
            }

            if (loadingState.value) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val err = errorState.value
                if (err != null) {
                    Text(text = "Couldn't load stickers.")
                    Text(text = err)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "(For now the app uses a placeholder URL. We’ll plug in your real website later.)")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(stickersState.value) { item ->
                            OutlinedButton(
                                onClick = {
                                    // Download PDF then go to nudge screen.
                                    // We do the network/file work off the main thread.
                                    // While downloading, show a toast.
                                    Toast.makeText(context, "Downloading ${item.name}…", Toast.LENGTH_SHORT).show()
                                    // Launch a one-off background download.
                                    // (LaunchedEffect isn't ideal for a click, so we use a coroutine helper.)
                                    // We keep it simple: do it inline using withContext.

                                    // NOTE: This onClick is not suspend, so we call a helper that starts work.
                                    downloadStickerPdfAndSelect(
                                        context = context,
                                        item = item,
                                        onSelected = { uri ->
                                            selectedPdfUri = uri
                                            wizardStep = 1
                                        },
                                        onError = { msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = item.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

        } else if (wizardStep == 1) {
            Text(text = "Step 2 of 2: Nudge and print")

            // Center the nudge controls in the middle of the screen.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(text = "Nudge (step = ${stepMm}mm)")

                    NudgeCircleButton(label = "Up") {
                        shiftYmm -= stepMm
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NudgeCircleButton(label = "Left") {
                            shiftXmm -= stepMm
                        }
                        Column(
                            modifier = Modifier.width(140.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = formatDirectionOnly(shiftXmm, "Left", "Right"))
                            Text(text = formatDirectionOnly(shiftYmm, "Up", "Down"))
                        }
                        NudgeCircleButton(label = "Right") {
                            shiftXmm += stepMm
                        }
                    }

                    NudgeCircleButton(label = "Down") {
                        shiftYmm += stepMm
                    }

                    Button(onClick = {
                        shiftXmm = 0.0
                        shiftYmm = 0.0
                    }) {
                        Text("Reset to 0")
                    }
                }
            }

            // Bottom controls: copies + print
            OutlinedTextField(
                value = copies,
                onValueChange = { newValue ->
                    copies = newValue.filter { it.isDigit() }.take(4)
                },
                label = { Text("Copies") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = {
                    val uri = selectedPdfUri
                    if (uri == null) {
                        Toast.makeText(context, "Select a PDF first", Toast.LENGTH_SHORT).show()
                        wizardStep = 0
                        return@Button
                    }

                    val copiesInt = copies.toIntOrNull()?.coerceIn(1, 9999) ?: 1
                    activePrintJob = printShiftedPdf(
                        context = context,
                        pdfUri = uri,
                        shiftXmm = shiftXmm,
                        shiftYmm = shiftYmm,
                        copies = copiesInt
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Print")
            }

            Text(text = if (selectedPdfUri == null) "No PDF selected" else "PDF selected ✅")

            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Tip: In the print dialog, choose Actual size / 100% and disable Fit to page.")
        }
    }
}


@Composable
private fun NudgeCircleButton(
    label: String,
    onClick: () -> Unit
) {
    // A nice round "ring" button. We use OutlinedButton so it has a clean circular border.
    OutlinedButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = Modifier.size(72.dp),
        contentPadding = PaddingValues(0.dp)
    ) {
        Text(label)
    }
}

private fun formatDirectionOnly(
    valueMm: Double,
    negativeLabel: String,
    positiveLabel: String
): String {
    val rounded = round(valueMm * 10.0) / 10.0
    val absRounded = abs(rounded)

    return when {
        rounded < 0 -> "$negativeLabel: $absRounded mm"
        rounded > 0 -> "$positiveLabel: $absRounded mm"
        else -> "0.0 mm"
    }
}

private fun formatAxisWithDirection(
    valueMm: Double,
    axisLabel: String,
    negativeLabel: String,
    positiveLabel: String
): String {
    val rounded = round(valueMm * 10.0) / 10.0
    val absRounded = abs(rounded)

    val label = when {
        rounded < 0 -> negativeLabel
        rounded > 0 -> positiveLabel
        else -> axisLabel
    }

    // When it's exactly 0, we show "X: 0.0 mm" / "Y: 0.0 mm".
    // Otherwise, show "Left: 0.5 mm" / "Up: 1.0 mm" etc.
    return "$label: $absRounded mm"
}

private fun formatMm(value: Double): String {
    val rounded = round(value * 10.0) / 10.0
    val sign = if (rounded > 0) "+" else ""
    return "$sign$rounded"
}

@Preview(showBackground = true)
@Composable
fun PrintScreenPreview() {
    LabelNudgerTheme {
        LabelNudgerApp(modifier = Modifier.fillMaxSize())
    }
}

private fun mmToPoints(mm: Double): Float {
    // 1 inch = 25.4 mm, 1 point = 1/72 inch
    return (mm * 72.0 / 25.4).toFloat()
}

private fun printShiftedPdf(
    context: Context,
    pdfUri: Uri,
    shiftXmm: Double,
    shiftYmm: Double,
    copies: Int
): PrintJob {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
    val jobName = "Label Nudger"

    val adapter = ShiftedPdfPrintAdapter(
        context = context,
        pdfUri = pdfUri,
        shiftXPoints = mmToPoints(shiftXmm),
        shiftYPoints = mmToPoints(shiftYmm),
        copies = copies
    )

    val printAttributes = PrintAttributes.Builder()
        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
        // Label sheets must print at 100% (no scaling). We keep margins minimal.
        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
        .build()

    return printManager.print(jobName, adapter, printAttributes)
}

private class ShiftedPdfPrintAdapter(
    private val context: Context,
    private val pdfUri: Uri,
    private val shiftXPoints: Float,
    private val shiftYPoints: Float,
    private val copies: Int
) : PrintDocumentAdapter() {

    private var pdfRenderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private var sourcePageCount: Int = 0
    private var totalOutputPages: Int = 0

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }

        try {
            // Open the PDF so we can report page count.
            pfd?.close()
            pdfRenderer?.close()

            pfd = when (pdfUri.scheme) {
                "content" -> context.contentResolver.openFileDescriptor(pdfUri, "r")
                "file" -> {
                    val f = File(requireNotNull(pdfUri.path) { "Invalid file uri" })
                    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
                }
                else -> context.contentResolver.openFileDescriptor(pdfUri, "r")
            }
            if (pfd == null) {
                callback.onLayoutFailed("Couldn't open PDF")
                return
            }

            pdfRenderer = PdfRenderer(pfd!!)
            sourcePageCount = pdfRenderer?.pageCount ?: 0
            totalOutputPages = sourcePageCount * max(1, copies)

            val info = PrintDocumentInfo.Builder("label_nudger_output.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(totalOutputPages)
                .build()

            callback.onLayoutFinished(info, true)
        } catch (e: Exception) {
            callback.onLayoutFailed(e.message)
        }
    }

    override fun onWrite(
        pageRanges: Array<PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback
    ) {
        val renderer = pdfRenderer
        if (renderer == null) {
            callback.onWriteFailed("PDF not ready")
            return
        }

        // We write a new PDF that the Android print system will send to the printer.
        val pdfDocument = android.graphics.pdf.PdfDocument()

        try {
            val pagesToWrite = mutableListOf<Int>()
            for (i in 0 until totalOutputPages) {
                if (PageRangeUtils.contains(pageRanges, i)) pagesToWrite.add(i)
            }

            for (outPageIndex in pagesToWrite) {
                if (cancellationSignal.isCanceled) {
                    pdfDocument.close()
                    callback.onWriteCancelled()
                    return
                }

                val srcPageIndex = if (sourcePageCount == 0) 0 else (outPageIndex % sourcePageCount)

                val srcPage = renderer.openPage(srcPageIndex)
                val pageWidth = srcPage.width
                val pageHeight = srcPage.height

                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo
                    .Builder(pageWidth, pageHeight, outPageIndex + 1)
                    .create()

                val outPage = pdfDocument.startPage(pageInfo)

                // Render at 600 DPI for crisp printing.
                // Pdf points are based on 72 dpi, so scale up by (600/72).
                val targetDpi = 300f
                val scale = targetDpi / 72f

                val bmpW = (pageWidth * scale).toInt().coerceAtLeast(1)
                val bmpH = (pageHeight * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)

                // Render the PDF page into the high-res bitmap.
                val renderMatrix = Matrix().apply { setScale(scale, scale) }
                srcPage.render(bitmap, null, renderMatrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                // Draw the high-res bitmap back onto the output PDF page.
                // We scale it down to the original PDF page size, and then apply the nudge shift in points.
                val drawMatrix = Matrix().apply {
                    setScale(1f / scale, 1f / scale)
                    postTranslate(shiftXPoints, shiftYPoints)
                }
                outPage.canvas.drawBitmap(bitmap, drawMatrix, null)

                pdfDocument.finishPage(outPage)

                bitmap.recycle()
                srcPage.close()
            }

            FileOutputStream(destination.fileDescriptor).use { outStream ->
                pdfDocument.writeTo(outStream)
            }

            pdfDocument.close()
            callback.onWriteFinished(pageRanges)
        } catch (e: Exception) {
            try {
                pdfDocument.close()
            } catch (_: Exception) {
            }
            callback.onWriteFailed(e.message)
        }
    }

    override fun onFinish() {
        try { pdfRenderer?.close() } catch (_: Exception) {}
        try { pfd?.close() } catch (_: Exception) {}
        pdfRenderer = null
        pfd = null
    }
}

private object PageRangeUtils {
    fun contains(ranges: Array<PageRange>, page: Int): Boolean {
        for (range in ranges) {
            if (range.start <= page && page <= range.end) return true
        }
        return false
    }
}
private suspend fun fetchStickersList(jsonUrl: String): List<StickerItem> = withContext(Dispatchers.IO) {
    val conn = URL(jsonUrl).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 10_000
    conn.readTimeout = 10_000

    try {
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val arr = JSONArray(body)
        val out = mutableListOf<StickerItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val name = obj.optString("name").trim()
            val url = obj.optString("url").trim()
            if (name.isNotEmpty() && url.isNotEmpty()) {
                out.add(StickerItem(name = name, url = url))
            }
        }
        out
    } finally {
        conn.disconnect()
    }
}

private fun downloadStickerPdfAndSelect(
    context: Context,
    item: StickerItem,
    onSelected: (Uri) -> Unit,
    onError: (String) -> Unit
) {
    // Run a coroutine tied to the main thread via LaunchedEffect-like scope.
    // We avoid adding extra architecture; this is a simple app.
    GlobalScope.launch(Dispatchers.Main) {
        try {
            val uri = downloadPdfToCache(context, item.url, item.name)
            onSelected(uri)
        } catch (e: Exception) {
            onError(e.message ?: "Download failed")
        }
    }
}

private suspend fun downloadPdfToCache(
    context: Context,
    pdfUrl: String,
    name: String
): Uri = withContext(Dispatchers.IO) {
    val safeName = name
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
        .ifBlank { "sticker" }

    val outFile = File(context.cacheDir, "${safeName}_65up.pdf")

    val conn = URL(pdfUrl).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 15_000
    conn.readTimeout = 15_000

    try {
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code")
        }
        conn.inputStream.use { input ->
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        Uri.fromFile(outFile)
    } finally {
        conn.disconnect()
    }
}