package com.example.pdfreader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// PDF sample code from
// https://medium.com/@chahat.jain0/rendering-a-pdf-document-in-android-activity-fragment-using-pdfrenderer-442462cb8f9a
// Issues about cache etc. are not at all obvious from documentation, so we should expect people to need this.
// We may wish to provide this code.
class MainActivity : AppCompatActivity() {
    val LOGNAME = "pdf_viewer"
    val FILENAME = "shannon1948.pdf"
    val FILERESID = R.raw.shannon1948

    // manage the pages of the PDF, see below
    lateinit var pdfRenderer: PdfRenderer
    lateinit var parcelFileDescriptor: ParcelFileDescriptor
    var currentPage: PdfRenderer.Page? = null

    // custom ImageView class that captures strokes and draws them over the image
    lateinit var pageImage: PDFimage

    private lateinit var statusTextView: TextView
    private var totalPage: Int? = null
    private var curPageNum = 0

    var pagePathMap: HashMap<Int, MutableList<Path?>> = hashMapOf()
    var pagePaintMap: HashMap<Int, MutableList<Paint?>> = hashMapOf()
    var pageEraseSetMap: HashMap<Int, HashSet<Int>> = hashMapOf()

    var widthHeightRatio = 1080f / 2200
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        var layoutParam = pageImage.layoutParams
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            layoutParam.width = 6000
        }
        else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            layoutParam.width = 1080
        }

        layoutParam.height = (layoutParam.width / widthHeightRatio).toInt()
        pageImage.layoutParams = layoutParam
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val layout = findViewById<LinearLayout>(R.id.pdfLayout)
        layout.isEnabled = true

        pageImage = PDFimage(this)
        layout.addView(pageImage)
        pageImage.minimumWidth = 1000
        pageImage.minimumHeight = 2000


        val returnButton = findViewById<ImageView>(R.id.return_icon)
        val drawButton = findViewById<ImageView>(R.id.draw_icon)
        val highlightButton = findViewById<ImageView>(R.id.highlight_icon)
        val eraserButton = findViewById<ImageView>(R.id.erase_icon)
        val undoButton = findViewById<ImageView>(R.id.undo_icon)
        val redoButton = findViewById<ImageView>(R.id.redo_icon)
        val leftButton = findViewById<RelativeLayout>(R.id.leftButton)
        val rightButton = findViewById<RelativeLayout>(R.id.rightButton)


        val titleText = findViewById<TextView>(R.id.pdf_title)
        titleText.text = FILENAME

        statusTextView = findViewById(R.id.statusText)

        returnButton.setOnClickListener {
            Log.d("Button", "returnButton clicked")
            prevPage()
        }

        drawButton.setOnClickListener {
            Log.d("Button", "drawButton clicked")
            pageImage.setDraw()
        }

        highlightButton.setOnClickListener {
            Log.d("Button", "highlightButton clicked")
            pageImage.setHighlight()
        }

        eraserButton.setOnClickListener {
            Log.d("Button", "eraserButton clicked")
            pageImage.setEraser()
        }

        undoButton.setOnClickListener {
            Log.d("Button", "undoButton clicked")
            pageImage.undo()
        }

        redoButton.setOnClickListener {
            Log.d("Button", "redoButton clicked")
            pageImage.redo()
        }

        leftButton.setOnClickListener {
            Log.d("Button", "leftButton clicked")
            prevPage()
        }

        rightButton.setOnClickListener {
            Log.d("Button", "rightButton clicked")
            nextPage()
        }

        // open page 0 of the PDF
        // it will be displayed as an image in the pageImage (above)
        try {
            openRenderer(this)
            totalPage = pdfRenderer.pageCount
            statusTextView.text = "Page ${curPageNum+1}/${pdfRenderer.pageCount}"
            showPage(curPageNum)
//            closeRenderer()
        } catch (exception: IOException) {
            Log.d(LOGNAME, "Error opening PDF")
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            closeRenderer()
        } catch (ex: IOException) {
            Log.d(LOGNAME, "Unable to close PDF renderer")
        }
    }
    override fun onPause() {
        super.onPause()
        updatePath()
        val coordinatesHashMap = HashMap<Int, List<List<MutableList<Float>>>>()
        for ((key, paths) in pagePathMap) {
            val coordinateLists = mutableListOf<List<MutableList<Float>>>()
            paths?.forEach { path ->
                if (path != null) {
                    val coordinates = pageImage.getPoints(path)
                    coordinateLists.add(coordinates)
                }
            }
            coordinatesHashMap[key] = coordinateLists
        }

        val colorHashMap = HashMap<Int, List<String>>()
        for ((key, colors) in pagePaintMap) {
            val colorList = mutableListOf<String>()
            for (color in colors) {
                if (color != null) {
                    colorList.add(pageImage.paintToStr(color!!))
                }
            }
            colorHashMap[key] = colorList
        }
        val bundle = Bundle().apply {
            Log.d("SystemPause", "Put: ${HashMap(pagePathMap)}")
            Log.d("SystemPause", "$pagePathMap")
            putSerializable("pathmap", coordinatesHashMap)
            putSerializable("paintmap", colorHashMap)
            putSerializable("erasemap", pageEraseSetMap)
            putInt("page", curPageNum)
        }
        intent = Intent(this@MainActivity, this@MainActivity::class.java)
        intent.putExtras(bundle)
        startActivity(intent)
    }

    override fun onResume() {
        super.onResume()
        val bundle = intent.extras
        if (bundle != null) {
            onRestoreInstanceState(bundle)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        updatePath()
        val coordinatesHashMap = HashMap<Int, List<List<MutableList<Float>>>>()
        for ((key, paths) in pagePathMap) {
            val coordinateLists = mutableListOf<List<MutableList<Float>>>()
            paths?.forEach { path ->
                if (path != null) {
                    val coordinates = pageImage.getPoints(path)
                    coordinateLists.add(coordinates)
                }
            }
            coordinatesHashMap[key] = coordinateLists
        }

        val colorHashMap = HashMap<Int, List<String>>()
        for ((key, colors) in pagePaintMap) {
            val colorList = mutableListOf<String>()
            for (color in colors) {
                if (color != null) {
                    colorList.add(pageImage.paintToStr(color!!))
                }
            }
            colorHashMap[key] = colorList
        }
        with (outState) {
            Log.d("SystemPause", "Put: ${HashMap(pagePathMap)}")
            Log.d("SystemPause", "$pagePathMap")
            putSerializable("pathmap", coordinatesHashMap)
            putSerializable("paintmap", colorHashMap)
            putSerializable("erasemap", pageEraseSetMap)
            putInt("page", curPageNum)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(inState: Bundle) {
        super.onRestoreInstanceState(inState)
        with (inState) {
            val coordinatesHashMap = getSerializable("pathmap") as? HashMap<Int, List<List<MutableList<Float>>>>
            val colorHashMap = getSerializable("paintmap") as? HashMap<Int, List<String>>
            pagePathMap.clear()
            pagePaintMap.clear()

            if (coordinatesHashMap != null) {
                for ((key, coordinateLists) in coordinatesHashMap) {
                    val paths = mutableListOf<Path?>()
                    coordinateLists.forEach { coordinates ->
                        val newPath = pageImage.listToPath(coordinates)
                        paths.add(newPath)
                    }
                    pagePathMap[key] = paths
                }
            }

            if (colorHashMap != null) {
                for ((key, colorList) in colorHashMap) {
                    val colors = mutableListOf<Paint?>()
                    for (colorStr in colorList) {
                        val paint = pageImage.strToPaint(colorStr)
                        colors.add(paint)
                    }
                    pagePaintMap[key] = colors
                }
            }
            Log.d("Color", "Colors: $colorHashMap, $pagePaintMap")

            pageEraseSetMap = (getSerializable("erasemap") as? HashMap<Int, HashSet<Int>>)!!
            curPageNum = getInt("page")
            Log.d("SystemResume", "page: $curPageNum")
            statusTextView.text = "Page ${curPageNum + 1}/${totalPage}"
            pageImage.nextPage()
            retrievePath()
            showPage(curPageNum)
        }
    }



    @Throws(IOException::class)
    private fun openRenderer(context: Context) {
        // In this sample, we read a PDF from the assets directory.
        val file = File(context.cacheDir, FILENAME)
        if (!file.exists()) {
            // pdfRenderer cannot handle the resource directly,
            // so extract it into the local cache directory.
            val asset = this.resources.openRawResource(FILERESID)
            val output = FileOutputStream(file)
            val buffer = ByteArray(1024)
            var size: Int
            while (asset.read(buffer).also { size = it } != -1) {
                output.write(buffer, 0, size)
            }
            asset.close()
            output.close()
        }
        parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        // capture PDF data
        // all this just to get a handle to the actual PDF representation
        pdfRenderer = PdfRenderer(parcelFileDescriptor)
    }

    // do this before you quit!
    @Throws(IOException::class)
    private fun closeRenderer() {
        currentPage?.close()
        pdfRenderer.close()
        parcelFileDescriptor.close()
    }

    private fun prevPage(){
        if (curPageNum > 0) {
            updatePath()
            curPageNum -= 1
            statusTextView.text = "Page ${curPageNum + 1}/${totalPage}"
            pageImage.nextPage()
            retrievePath()
            showPage(curPageNum)
        }
    }

    private fun nextPage(){
        if (pdfRenderer.pageCount > curPageNum + 1){
            updatePath()
            curPageNum += 1
            statusTextView.text = "Page ${curPageNum+1}/${totalPage}"
            pageImage.nextPage()
            retrievePath()
            showPage(curPageNum)
        }
    }

    private fun updatePath() {
        Log.d("Path", "add $curPageNum: ${pageImage.paths}")
        pagePathMap[curPageNum] = pageImage.paths.toMutableList()
        pagePaintMap[curPageNum] = pageImage.pathPaints.toMutableList()
        pageEraseSetMap[curPageNum] = pageImage.eraseSet.toHashSet()
    }

    private fun retrievePath() {
        if (pagePathMap.containsKey(curPageNum)) {
            Log.d("Path", "update")
            pageImage.paths = pagePathMap[curPageNum]!!
            pageImage.pathPaints = pagePaintMap[curPageNum]!!
            pageImage.eraseSet = pageEraseSetMap[curPageNum]!!
            Log.d("Path","$curPageNum : ${pagePathMap[curPageNum]}")
        }
    }

    private fun showPage(index: Int) {
        if (pdfRenderer.pageCount <= index) {
            return
        }
        // Close the current page before opening another one.
        try {
            currentPage?.close()
        } catch (ex: IOException) {
            Log.d(LOGNAME, "Unable to close current page")
        }

        // Use `openPage` to open a specific page in PDF.
        currentPage = pdfRenderer.openPage(index)

        if (currentPage != null) {
            // Important: the destination bitmap must be ARGB (not RGB).
            val bitmap = Bitmap.createBitmap(currentPage!!.getWidth(), currentPage!!.getHeight(), Bitmap.Config.ARGB_8888)

            // Here, we render the page onto the Bitmap.
            // To render a portion of the page, use the second and third parameter. Pass nulls to get the default result.
            // Pass either RENDER_MODE_FOR_DISPLAY or RENDER_MODE_FOR_PRINT for the last parameter.
            currentPage!!.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            // Display the page
            pageImage.setImage(bitmap)
            widthHeightRatio = (bitmap.width).toFloat() / bitmap.height
        }
    }
}

