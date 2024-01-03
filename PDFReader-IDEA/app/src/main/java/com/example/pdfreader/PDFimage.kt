package com.example.pdfreader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.Toast

@SuppressLint("AppCompatCustomView")
class PDFimage  // constructor
    (context: Context?) : ImageView(context) {
    val LOGNAME = "pdf_image"

    // drawing path
    var path: Path? = null
    var paths = mutableListOf<Path?>()
    var pathPaints = mutableListOf<Paint?>()
    var undoStack = mutableListOf<Path?>()
    var undoPaints = mutableListOf<Paint?>()
    var eraseSet = HashSet<Int>()


    // image to display
    var bitmap: Bitmap? = null
    var paint = Paint(Color.RED).apply {
        style = Paint.Style.STROKE
        color = Color.RED
    }
    var paintEnabled = false

    // Copied from sample code 15.PanZoom
    // we save a lot of points because they need to be processed
    // during touch events e.g. ACTION_MOVE
    var x1 = 0f
    var x2 = 0f
    var y1 = 0f
    var y2 = 0f
    var old_x1 = 0f
    var old_y1 = 0f
    var old_x2 = 0f
    var old_y2 = 0f
    var mid_x = -1f
    var mid_y = -1f
    var old_mid_x = -1f
    var old_mid_y = -1f
    var p1_id = 0
    var p1_index = 0
    var p2_id = 0
    var p2_index = 0
    val maxScale = 5.0f
    val minScale = 0.1f
    var curScale = 1.0f

    // store cumulative transformations
    // the inverse matrix is used to align points with the transformations - see below
    var currentMatrix = Matrix()
    var inverse = Matrix()

    fun paintToStr(paint: Paint): String {
        return when (paint.color) {
            Color.BLACK -> "Marker"
            Color.TRANSPARENT -> "Erase"
            else -> "HighLight"
        }
    }

    fun strToPaint(str: String): Paint? {
        return when (str) {
            "HighLight" -> highLightPaint()
            "Marker" -> markerPaint()
            "Erase" -> eraserPaint()
            else -> null
        }
    }

    fun getPoints(path: Path): List<MutableList<Float>> {
        val pointList = mutableListOf<MutableList<Float>>()
        val pm = PathMeasure(path, false)
        val length = pm.length
        var distance = 0f
        val numPoints = 100
        val speed = length / numPoints
        var counter = 0
        val aCoordinates = FloatArray(2)

        while ((distance < length) && (counter < numPoints)) {
            // get point from the path
            pm.getPosTan(distance, aCoordinates, null)
            pointList.add(mutableListOf(aCoordinates[0], aCoordinates[1]))
            counter++
            distance += speed
        }
        return pointList
    }

    fun listToPath(coordinates: List<MutableList<Float>>): Path {
        val path = Path()

        if (coordinates.isNotEmpty()) {
            val startPoint = coordinates[0]
            path.moveTo(startPoint[0], startPoint[1])

            for (i in 1 until coordinates.size) {
                val point = coordinates[i]
                path.lineTo(point[0], point[1])
            }
        }

        return path
    }

    // capture touch events (down/move/up) to create a path
    // and use that to create a stroke that we can draw
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        var inverted = floatArrayOf()
        when (event.pointerCount) {
            1 -> {
                p1_id = event.getPointerId(0)
                p1_index = event.findPointerIndex(p1_id)

                // invert using the current matrix to account for pan/scale
                // inverts in-place and returns boolean
                inverse = Matrix()
                currentMatrix.invert(inverse)

                // mapPoints returns values in-place
                inverted = floatArrayOf(event.getX(p1_index), event.getY(p1_index))
                inverse.mapPoints(inverted)
                x1 = inverted[0]
                y1 = inverted[1]

                if (paintEnabled) {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            Log.d(LOGNAME, "Action down")
                            path = Path()
                            path!!.moveTo(x1, y1)
                            invalidate()
                        }

                        MotionEvent.ACTION_MOVE -> {
                            Log.d(LOGNAME, "Action move")
                            path!!.lineTo(x1, y1)
                            invalidate()
                        }

                        MotionEvent.ACTION_UP -> {
                            Log.d(LOGNAME, "Action up")
                            paths.add(path)
                            pathPaints.add(paint)
                            if (paint.color == Color.TRANSPARENT) {
                                eraseSet.add(paths.size - 1)
                                Log.d("EraseMove", "paths: $paths, paints: $pathPaints, erase: $eraseSet")
                            }
                            undoStack.clear()
                            undoPaints.clear()
                        }

                    }
                }
            }
            2 -> {
                // point 1
                p1_id = event.getPointerId(0)
                p1_index = event.findPointerIndex(p1_id)

                // mapPoints returns values in-place
                inverted = floatArrayOf(event.getX(p1_index), event.getY(p1_index))
                inverse.mapPoints(inverted)

                // first pass, initialize the old == current value
                if (old_x1 < 0 || old_y1 < 0) {
                    x1 = inverted.get(0)
                    old_x1 = x1
                    y1 = inverted.get(1)
                    old_y1 = y1
                } else {
                    old_x1 = x1
                    old_y1 = y1
                    x1 = inverted.get(0)
                    y1 = inverted.get(1)
                }

                // point 2
                p2_id = event.getPointerId(1)
                p2_index = event.findPointerIndex(p2_id)

                // mapPoints returns values in-place
                inverted = floatArrayOf(event.getX(p2_index), event.getY(p2_index))
                inverse.mapPoints(inverted)

                // first pass, initialize the old == current value
                if (old_x2 < 0 || old_y2 < 0) {
                    x2 = inverted.get(0)
                    old_x2 = x2
                    y2 = inverted.get(1)
                    old_y2 = y2
                } else {
                    old_x2 = x2
                    old_y2 = y2
                    x2 = inverted.get(0)
                    y2 = inverted.get(1)
                }

                // midpoint
                mid_x = (x1 + x2) / 2
                mid_y = (y1 + y2) / 2
                old_mid_x = (old_x1 + old_x2) / 2
                old_mid_y = (old_y1 + old_y2) / 2

                // distance
                val d_old =
                    Math.sqrt(Math.pow((old_x1 - old_x2).toDouble(), 2.0) + Math.pow((old_y1 - old_y2).toDouble(), 2.0))
                        .toFloat()
                val d = Math.sqrt(Math.pow((x1 - x2).toDouble(), 2.0) + Math.pow((y1 - y2).toDouble(), 2.0))
                    .toFloat()

                // pan and zoom during MOVE event
                if (event.action == MotionEvent.ACTION_MOVE) {
                    Log.d(LOGNAME, "Multitouch move")
                    // pan == translate of midpoint
                    val dx = mid_x - old_mid_x
                    val dy = mid_y - old_mid_y
                    currentMatrix.preTranslate(dx, dy)
                    Log.d(LOGNAME, "translate: $dx,$dy")

                    // zoom == change of spread between p1 and p2
                    var scale = d / d_old
                    scale = Math.max(0f, scale)
                    if (curScale * scale in minScale..maxScale) {
                        curScale *= scale
                        currentMatrix.preScale(scale, scale, mid_x, mid_y)
                    }
                    Log.d(LOGNAME, "scale: $scale")

                    // reset on up
                } else if (event.action == MotionEvent.ACTION_UP) {
                    old_x1 = -1f
                    old_y1 = -1f
                    old_x2 = -1f
                    old_y2 = -1f
                    old_mid_x = -1f
                    old_mid_y = -1f
                }
            }
            else -> {
            }
        }
        return true
    }

    // set image as background
    fun setImage(bitmap: Bitmap?) {
        this.bitmap = bitmap
    }

    // set brush characteristics
    // e.g. color, thickness, alpha
    fun setBrush(paint: Paint) {
        this.paint = paint
        paint.style = Paint.Style.STROKE
        paintEnabled = true
    }

    private fun highLightPaint(): Paint {
        var highlightPaint = Paint()
        highlightPaint.color = Color.YELLOW
        highlightPaint.alpha = 100
        highlightPaint.strokeWidth = 20f
        highlightPaint.style = Paint.Style.STROKE
        return  highlightPaint
    }

    fun setHighlight() {
        setBrush(highLightPaint())
    }

    private fun markerPaint():Paint {
        var drawPaint = Paint()
        drawPaint.color = Color.BLACK
        drawPaint.strokeWidth = 5f
        drawPaint.style = Paint.Style.STROKE
        return drawPaint
    }

    fun setDraw() {
        setBrush(markerPaint())
    }

    private fun eraserPaint():Paint {
        var eraser = Paint()
        eraser.color = Color.TRANSPARENT
        eraser.strokeWidth = 20f
        eraser.style = Paint.Style.STROKE
        return eraser
    }

    fun setEraser() {
        setBrush(eraserPaint())
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            val lastPath = paths.removeAt(paths.size - 1)
            val lastPaint = pathPaints.removeAt(pathPaints.size - 1)
            undoStack.add(lastPath)
            undoPaints.add(lastPaint)
            if (lastPaint?.color == Color.TRANSPARENT) {
                eraseSet.remove(paths.size)
            }
        }
    }

    fun redo() {
        if (undoStack.isNotEmpty()) {
            val lastPath = undoStack.removeAt(undoStack.size - 1)
            val lastPaint = undoPaints.removeAt(undoPaints.size - 1)
            paths.add(lastPath)
            pathPaints.add(lastPaint)
            if (lastPaint?.color == Color.TRANSPARENT) {
                eraseSet.add(paths.size - 1)
            }
        }
    }

    fun nextPage() {
        paths.clear()
        pathPaints.clear()
        undoStack.clear()
        undoPaints.clear()
        eraseSet.clear()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.concat(currentMatrix)
        // draw background
        if (bitmap != null) {
            setImageBitmap(bitmap)
//            setImageBitmap(Bitmap.createScaledBitmap(bitmap!!, 1200, 1200, false))
        }
        // draw lines over it
        for (i in paths.indices) {
            if (i < pathPaints.size && paths[i] != null  && pathPaints[i] != null) {
                var valid = true
                if (pathPaints[i]?.color != Color.TRANSPARENT) {
                    for (idx in eraseSet) {
                        if (idx > i) {
                            val result = Path()
                            if (result.op(paths[i]!!, paths[idx]!!, Path.Op.INTERSECT)) {
                                if (!result.isEmpty) {
                                    Log.d("ERASE", "$idx")
                                    valid = false
                                    break
                                }
                            }
                        }
                    }
                    if (paths[i] != null && valid) {
                        Log.d("Draw", "idx: $i")
                        canvas.drawPath(paths[i]!!, pathPaints[i]!!)
                    }
                }
            }
        }
        super.onDraw(canvas)
    }
}
