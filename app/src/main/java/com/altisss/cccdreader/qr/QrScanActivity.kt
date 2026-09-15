package com.altisss.cccdreader.qr

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
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
import com.altisss.cccdreader.model.CccdQrData
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Quét QR mặt trước CCCD để lấy số CCCD + ngày sinh (dùng làm BAC key material).
 * Ngày hết hạn (không có trong QR) được truyền vào từ Intent extra do người dùng nhập tay
 * ở màn hình trước, hoặc có thể mở rộng thêm OCR MRZ ở đây nếu cần tự động hoàn toàn.
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
            Toast.makeText(this, "Cần quyền Camera để quét QR. Vào Cài đặt > Ứng dụng > cấp quyền Camera.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        previewView = PreviewView(this)
        root.addView(previewView)

        // Text debug nhỏ ở trên cùng để biết đang ở trạng thái nào - anh có thể xoá sau khi chạy ổn
        tvDebug = TextView(this).apply {
            setBackgroundColor(0x88000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
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
                tvDebug.text = "Đưa QR mặt trước CCCD vào khung hình"
            } catch (e: Exception) {
                tvDebug.text = "Lỗi mở camera: ${e.message}"
                Toast.makeText(this, "Lỗi mở camera: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onBarcodesDetected(barcodes: List<Barcode>) {
        if (handled) return
        if (barcodes.isEmpty()) return

        // Lấy barcode đầu tiên đọc được raw text, không lọc cứng theo "|" nữa -
        // để lỡ định dạng QR thực tế khác giả định thì vẫn thấy được dữ liệu thô để debug.
        val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue ?: return

        val qrData = CccdQrData.parse(raw)
        if (qrData == null) {
            // Quét được QR nhưng không đúng định dạng 7 trường kỳ vọng -> hiện raw để anh đối chiếu
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
        setResult(RESULT_OK, result)
        finish()
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