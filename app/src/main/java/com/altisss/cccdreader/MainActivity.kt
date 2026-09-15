package com.altisss.cccdreader

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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

    private val qrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val data = result.data ?: return@registerForActivityResult
            idNumber = data.getStringExtra(QrScanActivity.EXTRA_ID_NUMBER)
            dobMrz = data.getStringExtra(QrScanActivity.EXTRA_DOB_MRZ)
            val name = data.getStringExtra(QrScanActivity.EXTRA_FULL_NAME)
            tvQrResult.text = "Số CCCD: $idNumber | Tên: $name"
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

        findViewById<Button>(R.id.btnScanQr).setOnClickListener {
            qrLauncher.launch(Intent(this, QrScanActivity::class.java))
        }

        btnCopyBase64.setOnClickListener {
            val text = lastDsCertBase64 ?: return@setOnClickListener
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("DS Cert Base64", text))
            Toast.makeText(this, "Đã copy DS Cert (base64)", Toast.LENGTH_SHORT).show()
        }
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
            tvNfcStatus.text = "Chưa có dữ liệu QR. Vui lòng quét QR trước."
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
                // Theo quy tắc bảo mật, việc gọi API thật cần anh tự thêm endpoint +
                // xác nhận rõ ràng trước khi gửi dữ liệu định danh của khách hàng đi.

            } catch (e: Exception) {
                runOnUiThread {
                    tvNfcStatus.text = "Lỗi đọc chip: ${e.message}"
                }
            }
        }.start()
    }
}
