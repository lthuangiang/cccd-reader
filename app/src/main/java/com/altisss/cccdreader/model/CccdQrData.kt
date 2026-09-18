package com.altisss.cccdreader.model

/**
 * Dữ liệu giải mã từ QR code mặt trước CCCD gắn chip.
 *
 * Định dạng phổ biến hiện nay (các trường phân cách bởi "|"):
 *   [0] Số CCCD (12 số)
 *   [1] Số CMND cũ (9 số, có thể rỗng)
 *   [2] Họ và tên
 *   [3] Ngày sinh (ddMMyyyy)
 *   [4] Giới tính
 *   [5] Nơi thường trú
 *   [6] Ngày cấp thẻ (ddMMyyyy)
 *
 */
data class CccdQrData(
    val idNumber: String,
    val oldIdNumber: String?,
    val fullName: String,
    val dateOfBirthRaw: String,   // ddMMyyyy
    val sex: String,
    val placeOfResidence: String,
    val dateOfIssueRaw: String    // ddMMyyyy
) {
    companion object {
        fun parse(rawQr: String): CccdQrData? {
            val parts = rawQr.split("|")
            if (parts.size < 7) return null
            return CccdQrData(
                idNumber = parts[0].trim(),
                oldIdNumber = parts[1].trim().ifEmpty { null },
                fullName = parts[2].trim(),
                dateOfBirthRaw = parts[3].trim(),
                sex = parts[4].trim(),
                placeOfResidence = parts[5].trim(),
                dateOfIssueRaw = parts[6].trim()
            )
        }
    }

    /** Chuyển ddMMyyyy -> yyMMdd theo chuẩn MRZ (ICAO 9303) */
    fun dobForMrz(): String {
        require(dateOfBirthRaw.length == 8) { "Ngày sinh không đúng định dạng ddMMyyyy" }
        val dd = dateOfBirthRaw.substring(0, 2)
        val mm = dateOfBirthRaw.substring(2, 4)
        val yyyy = dateOfBirthRaw.substring(4, 8)
        return yyyy.substring(2, 4) + mm + dd
    }
}
