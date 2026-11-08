package com.musab.twoimg2pdf

import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val PAGE_W_MM = 148.5f
    private val PAGE_H_MM = 105f
    private val MARGIN_MM = 4f
    private val GAP_MM = 4f
    private val DPI = 300
    private val SHARPEN = 0.6f

    private var uris: List<Uri> = emptyList()
    private lateinit var img1: ImageView
    private lateinit var img2: ImageView
    private lateinit var status: TextView
    private lateinit var btnPdf: Button

    private val picker = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(2)
    ) { list ->
        if (list.size == 2) {
            uris = list
            img1.setImageURI(list[0]); img2.setImageURI(list[1])
            btnPdf.isEnabled = true
            status.text = "تم اختيار الصورتين"
        } else if (list.isNotEmpty()) {
            status.text = "اختر صورتين بالضبط"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun iv() = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.LTGRAY)
            layoutParams = LinearLayout.LayoutParams(0, (200 * resources.displayMetrics.density).toInt(), 1f)
                .apply { setMargins(4, 4, 4, 4) }
        }
        img1 = iv(); img2 = iv(); row.addView(img1); row.addView(img2)

        val btnPick = Button(this).apply { text = "اختيار صورتين" }
        btnPdf = Button(this).apply { text = "إنشاء PDF"; isEnabled = false }
        status = TextView(this).apply { gravity = Gravity.CENTER; setPadding(0, pad, 0, 0) }

        btnPick.setOnClickListener {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        btnPdf.setOnClickListener { createPdf() }

        root.addView(row); root.addView(btnPick); root.addView(btnPdf); root.addView(status)
        setContentView(root)
    }

    private fun createPdf() {
        btnPdf.isEnabled = false
        status.text = "جارٍ المعالجة..."
        Thread {
            try {
                val outUri = buildPdf()
                runOnUiThread {
                    status.text = "تم الحفظ في مجلد Downloads"
                    btnPdf.isEnabled = true
                    startActivity(Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(outUri, "application/pdf")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }.let { Intent.createChooser(it, "فتح PDF") })
                }
            } catch (e: Exception) {
                runOnUiThread { status.text = "خطأ: ${e.message}"; btnPdf.isEnabled = true }
            }
        }.start()
    }

    private fun mmToPt(mm: Float) = mm / 25.4f * 72f
    private fun ptToPx(pt: Float) = (pt / 72f * DPI).roundToInt()

    private fun buildPdf(): Uri {
        val pageW = mmToPt(PAGE_W_MM).roundToInt()
        val pageH = mmToPt(PAGE_H_MM).roundToInt()
        val margin = mmToPt(MARGIN_MM)
        val gap = mmToPt(GAP_MM)
        val slotW = (pageW - 2 * margin - gap) / 2f
        val slotH = pageH - 2 * margin

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        uris.forEachIndexed { i, uri ->
            val src = decode(uri)
            val slotPxW = ptToPx(slotW); val slotPxH = ptToPx(slotH)
            val ratio = min(slotPxW / src.width.toFloat(), slotPxH / src.height.toFloat())
            val tw = max(1, (src.width * ratio).roundToInt())
            val th = max(1, (src.height * ratio).roundToInt())
            val scaled = resize(src, tw, th)
            val sharp = sharpen(scaled, SHARPEN)

            val dw = tw * 72f / DPI; val dh = th * 72f / DPI
            val left = margin + i * (slotW + gap) + (slotW - dw) / 2f
            val top = margin + (slotH - dh) / 2f
            c.drawBitmap(sharp, null, RectF(left, top, left + dw, top + dh), paint)
        }
        doc.finishPage(page)

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "images_${System.currentTimeMillis()}.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val out = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("تعذّر إنشاء الملف")
        contentResolver.openOutputStream(out)!!.use { doc.writeTo(it) }
        doc.close()
        return out
    }

    private fun decode(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (max(info.size.width, info.size.height) > 4096) decoder.setTargetSampleSize(2)
        }
    }

    private fun resize(bmp: Bitmap, tw: Int, th: Int): Bitmap {
        var cur = bmp
        while (cur.width * 1.5f < tw) {
            cur = Bitmap.createScaledBitmap(cur, (cur.width * 1.5f).roundToInt(), (cur.height * 1.5f).roundToInt(), true)
        }
        return Bitmap.createScaledBitmap(cur, tw, th, true)
    }

    private fun sharpen(src: Bitmap, a: Float): Bitmap {
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        val out = px.copyOf()
        val center = 1f + 4f * a
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val c = px[i]; val l = px[i - 1]; val r = px[i + 1]; val u = px[i - w]; val d = px[i + w]
            fun ch(s: Int): Int {
                val v = ((c shr s) and 255) * center -
                        a * (((l shr s) and 255) + ((r shr s) and 255) + ((u shr s) and 255) + ((d shr s) and 255))
                return v.roundToInt().coerceIn(0, 255)
            }
            out[i] = (c and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }
}
