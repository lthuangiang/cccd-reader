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
 */
class NfcCccdReader {

    data class ReadResult(
        val dsCertificate: X509Certificate,
        val dsCertBase64Der: String,
        val dsCertPem: String,
        val sodRawBase64: String,
        val fullNameFromChip: String?
    )

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

    /**
     * Tạo BAC key từ cccdIdNumber (12 số in trên mặt trước CCCD), dob và doe theo format yyMMdd.
     *
     * QUAN TRỌNG: BAC document number theo MRZ (chuẩn TD1, ICAO 9303) chỉ có 9 ký tự,
     * không phải nguyên 12 số CCCD. Đã verify thực tế trên MRZ mặt sau thẻ thật:
     *   Số CCCD (mặt trước):           080087016029  (12 số)
     *   MRZ document number (mặt sau):    087016029  (9 số - BỎ 3 SỐ ĐẦU của số CCCD)
     */
    fun buildBacKey(cccdIdNumber: String, dobYyMMdd: String, doeYyMMdd: String): BACKeySpec {
        require(cccdIdNumber.length == 12) { "Số CCCD phải đủ 12 số, đang nhận: '$cccdIdNumber'" }
        val mrzDocumentNumber = cccdIdNumber.substring(3)
        return BACKey(mrzDocumentNumber, dobYyMMdd, doeYyMMdd)
    }
}