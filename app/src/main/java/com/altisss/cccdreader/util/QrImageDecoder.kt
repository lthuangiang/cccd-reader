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
 *
 * QUAN TRỌNG: ảnh có thể là cả tấm thẻ (QR chỉ chiếm 1 góc nhỏ), không riêng ảnh crop sát QR.
 * Nếu resize ảnh xuống quá nhỏ trước khi đưa cho ML Kit, vùng QR sẽ mất chi tiết dù ảnh gốc
 * rõ nét. Do đó giữ độ phân giải khá cao (tối đa 3200px chiều dài nhất) thay vì resize mạnh
 * như trước (2200px) - vẫn đủ an toàn để tránh OOM với ảnh camera thông thường (~4000x3000).
 *
 * Chạy đồng bộ (blocking) - LUÔN gọi trên background thread, không gọi ở main thread.
 */
object QrImageDecoder {

    sealed class Result {
        data class Success(val qrData: CccdQrData) : Result()
        data class NotFound(val rawTextIfAny: String?) : Result()
        data class Error(val message: String) : Result()
    }

    fun decodeCccdQrFromUri(contentResolver: ContentResolver, uri: Uri): Result {
        val bitmap = try {
            loadBitmapLimited(contentResolver, uri) ?: return Result.Error("Không đọc được file ảnh.")
        } catch (t: Throwable) {
            return Result.Error("Lỗi đọc ảnh: ${t.javaClass.simpleName} - ${t.message}")
        }

        // Lần 1: ảnh gốc (đã giới hạn kích thước tải ở mức khá cao, giữ chi tiết QR)
        decodeBitmapBlocking(bitmap)?.let { raw -> return toResult(raw) }

        // Lần 2: ảnh đã tăng cường thật sự (sharpen + tăng tương phản), không chỉ resize
        val enhanced = try {
            enhanceForQr(bitmap)
        } catch (t: Throwable) {
            return Result.NotFound(null)
        }
        decodeBitmapBlocking(enhanced)?.let { raw -> return toResult(raw) }

        return Result.NotFound(null)
    }

    private fun toResult(raw: String): Result {
        val parsed = CccdQrData.parse(raw)
        return if (parsed != null) Result.Success(parsed) else Result.NotFound(raw)
    }

    private fun decodeBitmapBlocking(bitmap: Bitmap): String? {
        val scanner = BarcodeScanning.getClient()
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val barcodes = Tasks.await(scanner.process(image))
            barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
        } catch (t: Throwable) {
            null
        } finally {
            scanner.close()
        }
    }

    /** Đọc ảnh, giới hạn kích thước ở mức khá cao (3200px) để không mất chi tiết QR khi ảnh
     *  là cả tấm thẻ, và tự xoay lại theo EXIF orientation nếu cần. */
    private fun loadBitmapLimited(contentResolver: ContentResolver, uri: Uri): Bitmap? {
        val maxTargetDimension = 3200

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

    /**
     * Tăng cường ảnh thật sự cho lần thử thứ 2: tăng tương phản (ColorMatrix) + sharpen
     * (convolution kernel), không chỉ resize như bản trước. Đây là bước tốn CPU nhất trong
     * app, nên giới hạn kích thước xử lý ở mức vừa phải (tối đa 1800px) để không quá chậm.
     */
    private fun enhanceForQr(src: Bitmap): Bitmap {
        val maxDimension = maxOf(src.width, src.height)
        val workingMax = 1800
        val working = if (maxDimension > workingMax) {
            val scale = workingMax.toFloat() / maxDimension
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else if (maxDimension < 800) {
            // ảnh quá nhỏ (VD: crop sát QR độ phân giải thấp) - phóng to lên trước khi sharpen
            val scale = 1200f / maxDimension
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src

        val contrasted = applyContrast(working, 1.4f)
        return sharpen3x3(contrasted)
    }

    private fun applyContrast(src: Bitmap, contrast: Float): Bitmap {
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val output = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return output
    }

    /** Sharpen kernel 3x3 cổ điển - giúp làm nét lại QR bị mờ do camera chưa lấy nét chuẩn. */
    private fun sharpen3x3(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val out = IntArray(w * h)
        val kernel = intArrayOf(0, -1, 0, -1, 5, -1, 0, -1, 0)

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) {
                    out[y * w + x] = pixels[y * w + x]
                    continue
                }
                var r = 0; var g = 0; var b = 0
                var k = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val p = pixels[(y + dy) * w + (x + dx)]
                        val kv = kernel[k++]
                        r += ((p shr 16) and 0xFF) * kv
                        g += ((p shr 8) and 0xFF) * kv
                        b += (p and 0xFF) * kv
                    }
                }
                r = r.coerceIn(0, 255); g = g.coerceIn(0, 255); b = b.coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }
}