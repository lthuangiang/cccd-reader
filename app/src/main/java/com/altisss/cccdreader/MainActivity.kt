package com.altisss.cccdreader

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.altisss.cccdreader.nfc.NfcCccdReader
import com.altisss.cccdreader.qr.QrScanActivity
import com.altisss.cccdreader.util.QrImageDecoder
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var nfcAdapter: NfcAdapter
    private val reader = NfcCccdReader()

    private var idNumber: String? = null
    private var dobMrz: String? = null
    private var lastDsCertBase64: String? = null

    private lateinit var tvQrResult: TextView
    private lateinit var etExpiryDate: EditText
    private lateinit var tvNfcStatus: TextView
    private lateinit var tvDsCertResult: TextView
    private lateinit var btnCopyBase64: Button
    private lateinit var btnScanQr: Button
    private lateinit var btnPickImage: Button

    // Quét bằng camera live -> trả kết quả ngay khi tìm thấy QR hợp lệ
    private val qrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            applyQrResult(
                id = data.getStringExtra(QrScanActivity.EXTRA_ID_NUMBER),
                dob = data.getStringExtra(QrScanActivity.EXTRA_DOB_MRZ),
                name = data.getStringExtra(QrScanActivity.EXTRA_FULL_NAME)
            )
        }
    }

    // Chọn ảnh từ thư viện -> quay lại màn hình chính ngay, xử lý ở đây với trạng thái loading/lỗi/kết quả
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            processPickedImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
            ?: run {
                Toast.makeText(this, "Thiết bị không hỗ trợ NFC", Toast.LENGTH_LONG).show()
                finish()
                return
            }

        tvQrResult = findViewById(R.id.tvQrResult)
        etExpiryDate = findViewById(R.id.etExpiryDate)
        tvNfcStatus = findViewById(R.id.tvNfcStatus)
        tvDsCertResult = findViewById(R.id.tvDsCertResult)
        btnCopyBase64 = findViewById(R.id.btnCopyBase64)
        btnScanQr = findViewById(R.id.btnScanQr)
        btnPickImage = findViewById(R.id.btnPickImage)

        btnScanQr.setOnClickListener {
            qrLauncher.launch(Intent(this, QrScanActivity::class.java))
        }

        btnPickImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnCopyBase64.setOnClickListener {
            val text = lastDsCertBase64 ?: return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DS Cert Base64", text))
            Toast.makeText(this, "Đã copy DS Cert (base64)", Toast.LENGTH_SHORT).show()
        }
    }

    /** Chọn ảnh xong -> hiện loading ngay -> xử lý nền -> báo lỗi hoặc hiện dữ liệu, tất cả tại màn hình chính */
    private fun processPickedImage(uri: Uri) {
        setPickImageUiState(processing = true)
        tvQrResult.text = "Đang xử lý ảnh..."

        thread {
            val result = QrImageDecoder.decodeCccdQrFromUri(contentResolver, uri)
            runOnUiThread {
                setPickImageUiState(processing = false)
                when (result) {
                    is QrImageDecoder.Result.Success -> {
                        val q = result.qrData
                        applyQrResult(id = q.idNumber, dob = q.dobForMrz(), name = q.fullName)
                    }
                    is QrImageDecoder.Result.NotFound -> {
                        tvQrResult.text = if (result.rawTextIfAny != null) {
                            "Đọc được mã nhưng sai định dạng CCCD kỳ vọng.\nRaw: ${result.rawTextIfAny}"
                        } else {
                            "Không tìm thấy QR trong ảnh này. Thử ảnh rõ nét hơn, đủ sáng, không bị loá."
                        }
                    }
                    is QrImageDecoder.Result.Error -> {
                        tvQrResult.text = "Lỗi xử lý ảnh: ${result.message}"
                    }
                }
            }
        }
    }

    private fun setPickImageUiState(processing: Boolean) {
        btnPickImage.isEnabled = !processing
        btnScanQr.isEnabled = !processing
        btnPickImage.text = if (processing) "Đang xử lý..." else "Chọn ảnh CCCD từ thư viện"
    }

    private fun applyQrResult(id: String?, dob: String?, name: String?) {
        idNumber = id
        dobMrz = dob
        tvQrResult.text = "Số CCCD: $id | Tên: $name"
    }

    override fun onResume() {
        super.onResume()
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_MUTABLE
        )
        nfcAdapter.enableForegroundDispatch(this, pendingIntent, null, arrayOf(arrayOf("android.nfc.tech.IsoDep")))
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG) ?: return
        handleTag(tag)
    }

    private fun handleTag(tag: Tag) {
        val doc = idNumber
        val dob = dobMrz
        val doeRaw = etExpiryDate.text.toString().trim()

        if (doc == null || dob == null) {
            tvNfcStatus.text = "Chưa có dữ liệu QR. Vui lòng quét/chọn ảnh QR trước."
            return
        }
        if (doeRaw.length != 8) {
            tvNfcStatus.text = "Vui lòng nhập ngày hết hạn đúng định dạng ddMMyyyy."
            return
        }

        val doeMrz = "${doeRaw.substring(4, 8).substring(2)}${doeRaw.substring(2, 4)}${doeRaw.substring(0, 2)}"

        tvNfcStatus.text = "Đang đọc chip, giữ yên thẻ..."

        Thread {
            try {
                val bacKey = reader.buildBacKey(doc, dob, doeMrz)
                val result = reader.readDsCert(tag, bacKey)
                lastDsCertBase64 = result.dsCertBase64Der

                runOnUiThread {
                    tvNfcStatus.text = "Đọc thành công."
                    tvDsCertResult.text = buildString {
                        append("Tên trên chip: ${result.fullNameFromChip ?: "(không đọc được)"}\n\n")
                        append("DS Cert (PEM):\n${result.dsCertPem}\n")
                        append("\nDS Cert Subject: ${result.dsCertificate.subjectX500Principal}\n")
                        append("DS Cert Issuer:  ${result.dsCertificate.issuerX500Principal}\n")
                        append("Hiệu lực: ${result.dsCertificate.notBefore} -> ${result.dsCertificate.notAfter}\n")
                    }
                    btnCopyBase64.isEnabled = true
                }

                // TODO: Gọi API RAR/Bộ Công an tại đây, gửi result.dsCertBase64Der
                // (và/hoặc result.sodRawBase64 nếu API yêu cầu verify full chain).

            } catch (e: Exception) {
                runOnUiThread {
                    tvNfcStatus.text = "Lỗi đọc chip: ${e.message}"
                }
            }
        }.start()
    }
}