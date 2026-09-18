# CCCD Reader (Android) — Đọc DS Cert từ chip CCCD qua NFC

## Luồng hoạt động (tóm tắt)

**QR → BAC key → chạm NFC → mở khóa chip (doBAC) → đọc bytes thô EF.SOD → JMRTD parse ra DS Cert → xuất Base64 → gửi API cho server xác thực**

Chi tiết từng bước:

1. **Quét QR mặt trước CCCD** (ML Kit) → lấy **số CCCD (12 số)** + **ngày sinh**.
   Người dùng nhập tay **ngày hết hạn thẻ** (in mặt sau) — QR không chứa trường này.

2. **Dựng BAC key** từ 3 thành phần theo chuẩn MRZ (ICAO 9303):
   document number + ngày sinh + ngày hết hạn (`buildBacKey()` trong `NfcCccdReader.kt`).
   Lưu ý document number của MRZ chỉ lấy **9 số** (bỏ 3 số đầu của số CCCD 12 số).

3. **Chạm mặt sau điện thoại vào chip CCCD** → mở kết nối `IsoDep`, khởi tạo
   `PassportService` (JMRTD), rồi gọi **`service.doBAC(bacKey)`** để **mở khóa chip**.

4. **Đọc bytes thô của EF.SOD**: `service.getInputStream(PassportService.EF_SOD).readBytes()`
   — lấy nguyên khối byte của Document Security Object trực tiếp từ chip.

5. **JMRTD parse SOD → tách DS Cert**: `SODFile(sodBytes).docSigningCertificate`
   trả về **DS Cert (Document Signer Certificate, X.509)**.

6. **Xuất Base64**: DS Cert được encode ra `dsCertBase64Der` (raw DER, base64) — kèm
   thêm bản PEM và Base64 của SOD gốc phòng khi server cần verify full chain.

7. **Gửi API cho server xác thực**: `dsCertBase64Der` là chuỗi cần gửi lên endpoint xác thực.


## Bảo mật khi tích hợp với server xác thực

- Dữ liệu đọc được (DS Cert, SOD, tên trên chip) là **dữ liệu định danh cá nhân** —
  đảm bảo kết nối HTTPS khi gửi lên API, không log ra file/analytics của bên thứ 3.
- Không lưu BAC key material (số CCCD + ngày sinh + ngày hết hạn) lâu hơn mức cần thiết
  cho phiên đọc.
- Nếu app dùng cho khách hàng mở tài khoản chứng khoán, cần rà lại với bộ phận pháp chế/
  compliance về việc thu thập, lưu trữ dữ liệu định danh theo Nghị định
  13/2023/NĐ-CP (bảo vệ dữ liệu cá nhân) trước khi go-live.