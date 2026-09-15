package com.altisss.cccdreader.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.altisss.cccdreader.model.CccdQrData
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Quét QR mặt trước CCCD để lấy số CCCD + ngày sinh (dùng làm BAC key material).
 *
 * Có 2 cách vào dữ liệu QR:
 *   1. Camera live (mặc định khi mở màn hình) - ML Kit tự phân tích từng frame.
 *   2. Chọn ảnh có sẵn từ thư viện (nút "Chọn ảnh CCCD từ thư viện") - hữu ích khi
 *      camera live khó lấy nét (thẻ bị loá, tay run...), hoặc test lại với ảnh đã chụp sẵn.
 *      Nếu ML Kit không đọc được ảnh gốc, tự động thử lại với ảnh đã tăng cường
 *      (phóng to + tăng tương phản) trước khi báo lỗi.
 *
 * Ngày hết hạn (không có trong QR) được truyền vào từ Intent extra do người dùng nhập tay
 * ở màn hình trước.
 */
class QrScanActivity : AppCompatActivity() {

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val barcodeScanner = BarcodeScanning.getClient()
    private var handled = false
    private lateinit var previewView: PreviewView
    private lateinit var tvDebug: TextView
    private var framesProcessed = 0

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            tvDebug.text = "Chưa cấp quyền Camera. Vẫn có thể dùng nút 'Chọn ảnh từ thư viện' bên dưới."
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            processImageFromGallery(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        previewView = PreviewView(this)
        root.addView(previewView)

        // Text debug ở trên cùng - báo trạng thái quét / lỗi
        tvDebug = TextView(this).apply {
            setBackgroundColor(0x88000000.toInt())
            setTextColor(Color.WHITE)
            text = "Đang khởi động camera..."
            setPadding(16, 16, 16, 16)
        }
        root.addView(tvDebug, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })

        // Thanh nút dưới cùng: chọn ảnh từ thư viện thay vì chỉ dựa vào camera live
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0x88000000.toInt())
            setPadding(16, 16, 16, 16)
        }
        val btnPickImage = Button(this).apply {
            text = "Chọn ảnh CCCD từ thư viện"
            setOnClickListener { pickImageLauncher.launch("image/*") }
        }
        bottomBar.addView(btnPickImage, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(bottomBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ============== CAMERA LIVE ==============

    private fun startCamera() {
        tvDebug.text = "Đang mở camera..."
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null && !handled) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        barcodeScanner.process(image)
                            .addOnSuccessListener { barcodes -> onBarcodesDetected(barcodes) }
                            .addOnFailureListener { e ->
                                runOnUiThread { tvDebug.text = "Lỗi ML Kit: ${e.message}" }
                            }
                            .addOnCompleteListener {
                                framesProcessed++
                                runOnUiThread {
                                    if (!handled) tvDebug.text = "Đang quét... (frame #$framesProcessed) đưa QR vào giữa khung hình"
                                }
                                imageProxy.close()
                            }
                    } else {
                        imageProxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                tvDebug.text = "Đưa QR mặt trước CCCD vào khung hình, hoặc bấm nút chọn ảnh bên dưới"
            } catch (e: Exception) {
                tvDebug.text = "Lỗi mở camera: ${e.message}. Vẫn dùng được nút chọn ảnh bên dưới."
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ============== CHỌN ẢNH TỪ THƯ VIỆN ==============

    private fun processImageFromGallery(uri: Uri) {
        tvDebug.text = "Đang đọc ảnh..."
        Thread {
            try {
                val bitmap = loadBitmapRespectingExif(uri) ?: run {
                    runOnUiThread { tvDebug.text = "Không đọc được file ảnh." }
                    return@Thread
                }

                // Thử lần 1: ảnh gốc
                tryDecodeBitmap(bitmap) { success ->
                    if (!success) {
                        // Thử lần 2: ảnh đã tăng cường (phóng to + tăng tương phản)
                        // - hữu ích khi ảnh mờ/nhòe, giống trường hợp QR chụp không nét
                        runOnUiThread { tvDebug.text = "Không đọc được ảnh gốc, đang thử tăng cường ảnh..." }
                        val enhanced = enhanceForQr(bitmap)
                        tryDecodeBitmap(enhanced) { success2 ->
                            if (!success2) {
                                runOnUiThread {
                                    tvDebug.text = "Không đọc được QR trong ảnh này. Thử chụp/chọn ảnh rõ nét hơn, " +
                                            "đủ sáng, không bị loá."
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { tvDebug.text = "Lỗi xử lý ảnh: ${e.message}" }
            }
        }.start()
    }

    /** Đọc ảnh từ Uri và tự xoay lại theo EXIF orientation nếu cần */
    private fun loadBitmapRespectingExif(uri: Uri): Bitmap? {
        val input1: InputStream = contentResolver.openInputStream(uri) ?: return null
        val original = BitmapFactory.decodeStream(input1)
        input1.close()
        if (original == null) return null

        val exifStream: InputStream = contentResolver.openInputStream(uri) ?: return original
        val exif = ExifInterface(exifStream)
        exifStream.close()
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val rotationDegrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (rotationDegrees == 0f) return original

        val matrix = android.graphics.Matrix().apply { postRotate(rotationDegrees) }
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
    }

    /** Phóng to ảnh (nếu còn nhỏ) + tăng tương phản, giúp ML Kit dễ đọc QR mờ hơn */
    private fun enhanceForQr(src: Bitmap): Bitmap {
        val maxDimension = maxOf(src.width, src.height)
        val targetMax = 2000
        val scale = if (maxDimension < targetMax) targetMax.toFloat() / maxDimension else 1f
        val scaled = if (scale > 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src

        // Tăng tương phản bằng ColorMatrix (đơn giản, không cần RenderScript)
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
        val canvas = android.graphics.Canvas(output)
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(scaled, 0f, 0f, paint)
        return output
    }

    private fun tryDecodeBitmap(bitmap: Bitmap, onResult: (Boolean) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        barcodeScanner.process(image)
            .addOnSuccessListener { barcodes ->
                if (barcodes.isNotEmpty()) {
                    onBarcodesDetected(barcodes)
                    onResult(true)
                } else {
                    onResult(false)
                }
            }
            .addOnFailureListener { onResult(false) }
    }

    // ============== XỬ LÝ KẾT QUẢ CHUNG (camera live + ảnh chọn) ==============

    private fun onBarcodesDetected(barcodes: List<Barcode>) {
        if (handled) return
        if (barcodes.isEmpty()) return

        val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue ?: return

        val qrData = CccdQrData.parse(raw)
        if (qrData == null) {
            runOnUiThread {
                tvDebug.text = "Quét được QR nhưng sai định dạng kỳ vọng.\nRaw: $raw"
            }
            return
        }

        handled = true
        val result = Intent().apply {
            putExtra(EXTRA_ID_NUMBER, qrData.idNumber)
            putExtra(EXTRA_DOB_MRZ, qrData.dobForMrz())
            putExtra(EXTRA_FULL_NAME, qrData.fullName)
        }
        runOnUiThread {
            setResult(RESULT_OK, result)
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        barcodeScanner.close()
    }

    companion object {
        const val EXTRA_ID_NUMBER = "extra_id_number"
        const val EXTRA_DOB_MRZ = "extra_dob_mrz"
        const val EXTRA_FULL_NAME = "extra_full_name"
    }
}