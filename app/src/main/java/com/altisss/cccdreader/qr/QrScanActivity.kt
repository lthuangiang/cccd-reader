package com.altisss.cccdreader.qr

import android.content.Intent
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previewView = PreviewView(this)
        setContentView(previewView)
        startCamera(previewView)
    }

    private fun startCamera(previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
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
                        .addOnCompleteListener { imageProxy.close() }
                } else {
                    imageProxy.close()
                }
            }

            provider.unbindAll()
            provider.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onBarcodesDetected(barcodes: List<Barcode>) {
        if (handled) return
        val raw = barcodes.firstOrNull { it.rawValue?.contains("|") == true }?.rawValue ?: return
        val qrData = CccdQrData.parse(raw) ?: return

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
