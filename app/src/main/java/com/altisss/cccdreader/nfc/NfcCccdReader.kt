package com.altisss.cccdreader.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Base64
import net.sf.scuba.smartcards.CardService
import org.jmrtd.BACKey
import org.jmrtd.BACKeySpec
import org.jmrtd.PassportService
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.SODFile
import java.security.cert.X509Certificate

/**
 * Đọc chip CCCD qua NFC theo chuẩn ICAO 9303:
 *   1. Mở kết nối IsoDep tới chip
 *   2. Thực hiện BAC (Basic Access Control) để mở khóa chip bằng
 *      documentNumber + dateOfBirth + dateOfExpiry
 *   3. Đọc EF.SOD (Document Security Object)
 *   4. Tách DS Cert (Document Signer Certificate, X.509) từ SOD
 *
 * QUAN TRỌNG - những điểm anh cần tự verify với chip CCCD thật trước khi dùng production:
 *   - documentNumber dùng cho BAC: cần xác nhận CCCD VN dùng đúng số CCCD 12 số hay
 *     một dạng rút gọn/kèm check digit khác trong MRZ nội bộ chip. Một số triển khai
 *     eKYC VN có thể cần đệm số 0 hoặc cắt chuỗi theo cách khác.
 *   - API chính xác của JMRTD có thể lệch nhẹ theo version (0.7.41 dùng trong build.gradle) -
 *     nên build thử và đối chiếu javadoc/README của org.jmrtd trước khi build:
 *     https://github.com/jmrtd/jmrtd
 *   - Với thẻ CCCD, một số triển khai thực tế dùng PACE thay vì BAC tùy version chip -
 *     nếu doBAC() thất bại liên tục, cần thử luồng PACE (JMRTD có hỗ trợ doPACE()).
 */
class NfcCccdReader {

    data class ReadResult(
        val dsCertificate: X509Certificate,
        val dsCertBase64Der: String,   // để gửi thẳng lên API xác thực (raw DER, base64)
        val dsCertPem: String,          // dạng PEM, tiện log/lưu debug
        val sodRawBase64: String,       // toàn bộ SOD gốc, phòng khi RAR cần verify full chain
        val fullNameFromChip: String?
    )

    /**
     * @param tag Tag NFC nhận được từ onNewIntent/onTagDiscovered
     * @param bacKey Được tạo từ dữ liệu QR (số CCCD, ngày sinh) + ngày hết hạn user nhập tay
     */
    @Throws(Exception::class)
    fun readDsCert(tag: Tag, bacKey: BACKeySpec): ReadResult {
        val isoDep = IsoDep.get(tag) ?: throw IllegalStateException("Thẻ không hỗ trợ IsoDep (không phải chip ISO14443-4)")
        isoDep.timeout = 10_000

        val cardService: CardService = net.sf.scuba.smartcards.IsoDepCardService(isoDep)
        val service = PassportService(
            cardService,
            PassportService.NORMAL_MAX_TRANCEIVE_LENGTH,
            PassportService.DEFAULT_MAX_BLOCKSIZE,
            false,
            false
        )

        service.open()
        try {
            service.sendSelectApplet(false)

            // Thực hiện BAC để mở khóa chip
            service.doBAC(bacKey)

            // Đọc EF.SOD - chứa DS Cert
            val sodIn = service.getInputStream(PassportService.EF_SOD)
            val sodBytes = sodIn.readBytes()
            val sod = SODFile(sodBytes.inputStream())

            val dsCert = sod.docSigningCertificate
                ?: throw IllegalStateException("Không tìm thấy DS Cert trong SOD đọc được")

            // Đọc DG1 (thông tin MRZ) chỉ để lấy tên hiển thị cho UI, không bắt buộc
            val fullName = try {
                val dg1In = service.getInputStream(PassportService.EF_DG1)
                val dg1 = DG1File(dg1In.readBytes().inputStream())
                dg1.mrzInfo.secondaryIdentifier?.replace("<", " ")?.trim()
            } catch (e: Exception) {
                null
            }

            val derBytes = dsCert.encoded
            val base64Der = Base64.encodeToString(derBytes, Base64.NO_WRAP)
            val pem = buildString {
                append("-----BEGIN CERTIFICATE-----\n")
                append(Base64.encodeToString(derBytes, Base64.NO_WRAP).chunked(64).joinToString("\n"))
                append("\n-----END CERTIFICATE-----\n")
            }
            val sodBase64 = Base64.encodeToString(sodBytes, Base64.NO_WRAP)

            return ReadResult(
                dsCertificate = dsCert,
                dsCertBase64Der = base64Der,
                dsCertPem = pem,
                sodRawBase64 = sodBase64,
                fullNameFromChip = fullName
            )
        } finally {
            service.close()
        }
    }

    /** Tạo BAC key từ documentNumber (số CCCD), dob và doe theo format yyMMdd */
    fun buildBacKey(documentNumber: String, dobYyMMdd: String, doeYyMMdd: String): BACKeySpec {
        return BACKey(documentNumber, dobYyMMdd, doeYyMMdd)
    }
}
