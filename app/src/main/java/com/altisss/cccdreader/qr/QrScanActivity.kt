package com.altisss.cccdreader.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.altisss.cccdreader.model.CccdQrData
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Quét QR mặt trước CCCD bằng camera live (ML Kit phân tích từng frame).
 *
 * QUAN TRỌNG: ép độ phân giải phân tích lên tối thiểu ~1920x1440 qua ResolutionSelector.
 * Mặc định CameraX ImageAnalysis chọn độ phân giải khá thấp (thường quanh 640x480) nếu
 * không cấu hình - đủ cho QR đơn giản nhưng KHÔNG đủ chi tiết cho QR dày đặc như trên CCCD
 * (QR CCCD chứa nhiều dữ liệu -> nhiều module nhỏ -> cần độ phân giải cao hơn mới đọc được).
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
            tvDebug.text = "Chưa cấp quyền Camera. Vào Cài đặt > Ứng dụng > cấp quyền Camera, hoặc quay lại dùng nút 'Chọn ảnh từ thư viện' ở màn hình chính."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        previewView = PreviewView(this)
        root.addView(previewView)

        tvDebug = TextView(this).apply {
            setBackgroundColor(0x88000000.toInt())
            setTextColor(Color.WHITE)
            text = "Đang khởi động camera..."
            setPadding(16, 16, 16, 16)
        }
        root.addView(tvDebug, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP })

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        tvDebug.text = "Đang mở camera..."
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                // Ép độ phân giải cao hơn mặc định để đọc được QR dày đặc (nhiều module nhỏ)
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1920, 1440),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolutionSelector)
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
                                    if (!handled) tvDebug.text = "Đang quét... (frame #$framesProcessed) đưa QR vào giữa khung hình, giữ cách thẻ khoảng 10-15cm"
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
                tvDebug.text = "Đưa QR mặt trước CCCD vào khung hình"
            } catch (e: Exception) {
                tvDebug.text = "Lỗi mở camera: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

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