package de.ultimatepdfscanner.app.ui

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import de.ultimatepdfscanner.core.Passport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/** Passport photo from a camera shot: find the face (ML Kit, bundled model, offline) and crop to 35 x 45 mm. */
object PassportCapture {
    suspend fun process(context: Context, file: File): Mat? = withContext(Dispatchers.Default) {
        val image = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_COLOR) // applies the EXIF rotation
        file.delete()
        if (image.empty()) return@withContext null
        val rgba = Mat().also { Imgproc.cvtColor(image, it, Imgproc.COLOR_BGR2RGBA) }
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also { Utils.matToBitmap(rgba, it) }
        rgba.release()
        val detector = FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
        val faces = runCatching { detector.process(InputImage.fromBitmap(bmp, 0)).await() }.getOrNull().orEmpty()
        detector.close()
        bmp.recycle()
        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: run { image.release(); return@withContext null }
        val b = face.boundingBox
        Passport.crop(image, b.left.toDouble(), b.top.toDouble(), b.width().toDouble(), b.height().toDouble()).also { image.release() }
    }
}
