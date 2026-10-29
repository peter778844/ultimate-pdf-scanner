package de.ultimatepdfscanner.app.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.ui.geometry.Offset
import de.ultimatepdfscanner.core.Word
import java.io.File

/** One annotation in coordinates relative to the upright (displayed) page, 0..1. */
sealed interface Mark {
    val color: Int
    data class Ink(val points: List<Offset>, override val color: Int, val width: Float, val highlighter: Boolean) : Mark // width: share of page width
    data class Note(val at: Offset, val text: String, override val color: Int, val size: Float) : Mark // size: share of page width
}

/** Burns annotations into stored (unrotated) page images. */
object Marks {
    /** Displayed point (page shown turned by [rotation] clockwise) -> relative point in the stored image. */
    fun toStored(p: Offset, rotation: Int): Offset = when (rotation) {
        90 -> Offset(p.y, 1 - p.x)
        180 -> Offset(1 - p.x, 1 - p.y)
        270 -> Offset(1 - p.y, p.x)
        else -> p
    }

    fun burn(file: File, marks: List<Mark>, rotation: Int, quality: Int) {
        val src = BitmapFactory.decodeFile(file.path) ?: return
        val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
        src.recycle()
        val c = Canvas(bmp)
        val w = bmp.width.toFloat()
        val h = bmp.height.toFloat()
        val shownWidth = if (rotation % 180 == 0) w else h
        for (m in marks) when (m) {
            is Mark.Ink -> {
                val paint = Paint().apply {
                    color = m.color
                    style = Paint.Style.STROKE
                    strokeWidth = m.width * shownWidth
                    isAntiAlias = true
                    strokeJoin = Paint.Join.ROUND
                    strokeCap = if (m.highlighter) Paint.Cap.BUTT else Paint.Cap.ROUND
                    if (m.highlighter) { alpha = 115; xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY) } // ink stays readable under the marker
                }
                val path = android.graphics.Path()
                m.points.map { toStored(it, rotation) }.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
                c.drawPath(path, paint)
            }
            is Mark.Note -> {
                val p = toStored(m.at, rotation)
                c.save()
                c.translate(p.x * w, p.y * h)
                c.rotate(-rotation.toFloat())
                c.drawText(m.text, 0f, 0f, Paint().apply { color = m.color; textSize = m.size * shownWidth; isAntiAlias = true })
                c.restore()
            }
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bmp.recycle()
    }

    /** OCR words for typed notes (upright pages only), so they are searchable and in the PDF text layer. */
    fun noteWords(marks: List<Mark>, rotation: Int, width: Int, height: Int): List<Word> {
        if (rotation != 0) return emptyList()
        return marks.filterIsInstance<Mark.Note>().flatMapIndexed { k, n ->
            val size = n.size * width
            val paint = Paint().apply { textSize = size }
            var x = n.at.x * width
            n.text.split(' ').filter { it.isNotEmpty() }.map { t ->
                val tw = paint.measureText(t)
                Word(t, 100f, x.toInt(), (n.at.y * height - size * 0.8f).toInt(), (x + tw).toInt(), (n.at.y * height + size * 0.2f).toInt(), 9000 + k, 0, 0)
                    .also { x += tw + paint.measureText(" ") }
            }
        }
    }
}
