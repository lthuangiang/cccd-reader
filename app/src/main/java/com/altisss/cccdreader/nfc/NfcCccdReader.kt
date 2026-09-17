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
        val dsCertBase64OfPem: String,  // base64 CỦA CHUỖI PEM (encode thêm 1 lớp nữa lên trên PEM text)
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
            val base64OfPem = Base64.encodeToString(pem.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            return ReadResult(
                dsCertificate = dsCert,
                dsCertBase64Der = base64Der,
                dsCertPem = pem,
                dsCertBase64OfPem = base64OfPem,
                sodRawBase64 = sodBase64,
                fullNameFromChip = fullName
            )
        } finally {
            service.close()
        }
    }

    /**
     * Tạo BAC key từ cccdIdNumber (12 số in trên mặt trước CCCD), dob và doe theo format yyMMdd.
     *
     * QUAN TRỌNG: BAC document number theo MRZ (chuẩn TD1, ICAO 9303) chỉ có 9 ký tự,
     * không phải nguyên 12 số CCCD. Đã verify thực tế trên MRZ mặt sau thẻ thật:
     *   Số CCCD (mặt trước):        080087016029  (12 số)
     *   MRZ document number (mặt sau): 087016029  (9 số - BỎ 3 SỐ ĐẦU của số CCCD)
     * 3 số đầu của CCCD là mã tỉnh/mã dân số, không nằm trong document number field của MRZ.
     *
     * Lưu ý: quy tắc "bỏ 3 số đầu" này được xác nhận trên 1 thẻ mẫu thực tế - nên đối chiếu
     * lại với vài thẻ khác (nhất là thẻ cấp ở tỉnh khác) để chắc chắn đây là quy tắc chung,
     * trước khi dùng production.
     */
    fun buildBacKey(cccdIdNumber: String, dobYyMMdd: String, doeYyMMdd: String): BACKeySpec {
        require(cccdIdNumber.length == 12) { "Số CCCD phải đủ 12 số, đang nhận: '$cccdIdNumber'" }
        val mrzDocumentNumber = cccdIdNumber.substring(3) // bỏ 3 số đầu -> còn lại 9 số
        return BACKey(mrzDocumentNumber, dobYyMMdd, doeYyMMdd)
    }
}