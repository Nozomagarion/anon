package fr.anon.app

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class PhotoCanvas(context: Context) : View(context) {
    var bitmap: Bitmap? = null
    var faces = emptyList<FaceRegion>()
    var selected = emptySet<Int>()
    var manual = false
    var onFaceTap: (Int) -> Unit = {}
    var onRegion: (Float, Float, Float, Float) -> Unit = { _, _, _, _ -> }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageRect = RectF()
    private var startX = 0f; private var startY = 0f
    private var endX = 0f; private var endY = 0f
    private var drawing = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap ?: return
        val ratio = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val w = image.width * ratio; val h = image.height * ratio
        imageRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        paint.style = Paint.Style.FILL
        canvas.drawBitmap(image, null, imageRect, paint)
        for (face in faces) {
            paint.color = if (face.id in selected) Color.rgb(217, 252, 118) else Color.WHITE
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * resources.displayMetrics.density
            canvas.drawRoundRect(RectF(imageRect.left + face.left * w, imageRect.top + face.top * h,
                imageRect.left + face.right * w, imageRect.top + face.bottom * h), 8f, 8f, paint)
        }
        if (drawing) {
            paint.color = Color.rgb(217, 252, 118); paint.style = Paint.Style.STROKE
            canvas.drawRect(min(startX, endX), min(startY, endY), max(startX, endX), max(startY, endY), paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || bitmap == null || imageRect.width() <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!imageRect.contains(event.x, event.y)) return false
                startX = event.x; startY = event.y; endX = startX; endY = startY
                drawing = manual
                parent.requestDisallowInterceptTouchEvent(true)
                invalidate(); return true
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x.coerceIn(imageRect.left, imageRect.right)
                endY = event.y.coerceIn(imageRect.top, imageRect.bottom)
                invalidate(); return true
            }
            MotionEvent.ACTION_UP -> {
                parent.requestDisallowInterceptTouchEvent(false)
                val x = ((event.x - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
                val y = ((event.y - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
                if (drawing) {
                    val sx = (startX - imageRect.left) / imageRect.width()
                    val sy = (startY - imageRect.top) / imageRect.height()
                    if (abs(sx - x) > .025f && abs(sy - y) > .025f) onRegion(min(sx, x), min(sy, y), max(sx, x), max(sy, y))
                } else faces.filter { it.contains(x, y) }.minByOrNull { it.width * it.height }?.let { onFaceTap(it.id) }
                drawing = false; invalidate(); performClick(); return true
            }
            MotionEvent.ACTION_CANCEL -> { drawing = false; parent.requestDisallowInterceptTouchEvent(false); invalidate() }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
