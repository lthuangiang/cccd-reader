package com.altisss.cccdreader.util

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.altisss.cccdreader.model.CccdQrData
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/**
 * Đọc ảnh CCCD từ Uri (chọn từ thư viện) và tách dữ liệu QR ra.
 * Dùng chung cho MainActivity (chọn ảnh) - không dùng cho camera live (QrScanActivity
 * xử lý luồng camera riêng vì cần chạy trên từng frame liên tục).
 *
 * Chạy đồng bộ (blocking) - LUÔN gọi trên background thread, không gọi ở main thread.
 */
object QrImageDecoder {

    sealed class Result {
        data class Success(val qrData: CccdQrData) : Result()
        data class NotFound(val rawTextIfAny: String?) : Result() // đọc được QR nhưng không đúng format, hoặc không thấy QR nào
        data class Error(val message: String) : Result()
    }

    /** Chạy đồng bộ: đọc ảnh -> thử decode -> nếu fail thì tăng cường ảnh rồi thử lại. */
    fun decodeCccdQrFromUri(contentResolver: ContentResolver, uri: Uri): Result {
        val bitmap = try {
            loadBitmapLimited(contentResolver, uri) ?: return Result.Error("Không đọc được file ảnh.")
        } catch (t: Throwable) {
            return Result.Error("Lỗi đọc ảnh: ${t.javaClass.simpleName} - ${t.message}")
        }

        // Lần 1: ảnh gốc (đã giới hạn kích thước tải)
        decodeBitmapBlocking(bitmap)?.let { raw ->
            return toResult(raw)
        }

        // Lần 2: ảnh tăng cường (phóng to + tăng tương phản) - cho ảnh mờ/nhòe
        val enhanced = try {
            enhanceForQr(bitmap)
        } catch (t: Throwable) {
            return Result.NotFound(null)
        }
        decodeBitmapBlocking(enhanced)?.let { raw ->
            return toResult(raw)
        }

        return Result.NotFound(null)
    }

    private fun toResult(raw: String): Result {
        val parsed = CccdQrData.parse(raw)
        return if (parsed != null) Result.Success(parsed) else Result.NotFound(raw)
    }

    /** Trả về rawValue của barcode đầu tiên đọc được, hoặc null nếu không thấy. Chạy đồng bộ. */
    private fun decodeBitmapBlocking(bitmap: Bitmap): String? {
        val scanner = BarcodeScanning.getClient()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val barcodes = Tasks.await(scanner.process(image)) // block - hàm này luôn chạy ở background thread
            barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
        } catch (t: Throwable) {
            null
        } finally {
            scanner.close()
        }
    }

    /** Đọc ảnh, giới hạn kích thước lúc decode (tránh OOM/treo với ảnh gốc quá to từ camera),
     *  và tự xoay lại theo EXIF orientation nếu cần. */
    private fun loadBitmapLimited(contentResolver: ContentResolver, uri: Uri): Bitmap? {
        val maxTargetDimension = 2200

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOptions) }
        val srcW = boundsOptions.outWidth
        val srcH = boundsOptions.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        var sampleSize = 1
        while ((srcW / (sampleSize * 2)) >= maxTargetDimension || (srcH / (sampleSize * 2)) >= maxTargetDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val original = contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        val exif = contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL
        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotationDegrees == 0f) return original

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
    }

    /** Phóng to (nếu còn nhỏ) + tăng tương phản, giúp đọc được QR mờ/nhòe hơn. */
    private fun enhanceForQr(src: Bitmap): Bitmap {
        val maxDimension = maxOf(src.width, src.height)
        val targetMax = 2000
        val scale = if (maxDimension < targetMax) targetMax.toFloat() / maxDimension else 1f
        val scaled = if (scale > 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src

        val contrast = 1.6f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val output = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return output
    }
}