# CCCD Reader (Android) — Đọc DS Cert từ chip CCCD qua NFC

## Luồng hoạt động
1. Quét QR mặt trước CCCD (ML Kit) → lấy **số CCCD** + **ngày sinh**.
2. Người dùng nhập tay **ngày hết hạn thẻ** (in mặt sau CCCD) — QR không chứa trường này.
3. Chạm mặt sau điện thoại vào chip CCCD → app thực hiện **BAC** (Basic Access Control)
   để mở khóa chip, dùng thư viện **JMRTD** (chuẩn ICAO 9303, dùng chung cho hộ chiếu
   điện tử và CCCD gắn chip).
4. Đọc **EF.SOD**, tách **DS Cert (X.509)** ra, xuất dạng Base64 DER / PEM.
5. `dsCertBase64Der` là chuỗi cần gửi lên endpoint xác thực của RAR/Bộ Công an —
   phần gọi API thật được để dạng `TODO` trong `MainActivity.kt`, anh tự bổ sung
   endpoint + auth theo tài liệu tích hợp mà RAR cung cấp.

## Trước khi build, anh cần tự kiểm tra lại các điểm sau

Vì em không có chip CCCD thật để test trực tiếp, những chỗ sau **bắt buộc phải verify**
lại với thẻ thật trước khi đưa vào production:

1. **Định dạng QR** (`CccdQrData.kt`): thứ tự 7 trường phân cách bởi `|` là format phổ
   biến được công bố, nhưng Bộ Công an có thể điều chỉnh — in log raw QR ra để đối chiếu.
2. **BAC vs PACE**: một số đợt phát hành CCCD có thể dùng PACE thay vì BAC. Nếu
   `service.doBAC(bacKey)` liên tục lỗi ở bước xác thực, thử `service.doPACE(...)`
   (JMRTD hỗ trợ, cần đọc thêm `CardAccessFile` để lấy tham số PACE).
3. **Document number cho BAC key**: đang dùng thẳng số CCCD 12 số. Nếu chip yêu cầu
   format MRZ nội bộ khác (đệm ký tự `<`, cắt độ dài, thêm check digit riêng), cần chỉnh
   lại `buildBacKey()`.
4. **Version thư viện JMRTD/SCUBA**: API có thể lệch nhẹ giữa các bản. Đối chiếu
   `PassportService`, `SODFile`, `BACKey` với source thực tế tại
   https://github.com/jmrtd/jmrtd trước khi build release.

## Bảo mật khi tích hợp với RAR

- Dữ liệu đọc được (DS Cert, SOD, tên trên chip) là **dữ liệu định danh cá nhân** —
  đảm bảo kết nối HTTPS khi gửi lên API, không log ra file/analytics của bên thứ 3.
- Không lưu BAC key material (số CCCD + ngày sinh + ngày hết hạn) lâu hơn mức cần thiết
  cho phiên đọc.
- Nếu app dùng cho khách hàng mở tài khoản chứng khoán, cần rà lại với bộ phận pháp chế/
  compliance của ALTISSS về việc thu thập, lưu trữ dữ liệu định danh theo Nghị định
  13/2023/NĐ-CP (bảo vệ dữ liệu cá nhân) trước khi go-live.

## Build local (Android Studio)
Mở project bằng Android Studio, để Gradle tự sync rồi Build > Build APK(s).
Cài lên máy Android thật có NFC (emulator không đọc được NFC thật).

## Build tự động qua GitHub Actions

Project đã có sẵn `.github/workflows/build.yml`. Cách dùng:

1. Push toàn bộ thư mục `cccd-reader/` này lên một GitHub repo (tạo repo mới, `git init`,
   `git add .`, `git commit`, `git push`).
2. Vào tab **Actions** trên GitHub repo → workflow "Build APK" sẽ tự chạy mỗi khi push
   lên nhánh `main`/`master`. Có thể bấm **Run workflow** để chạy thủ công.
3. Sau khi build xong (vài phút), vào lại workflow run đó → mục **Artifacts** ở cuối
   trang → tải file `cccd-reader-debug-apk.zip` (bên trong là `app-debug.apk`).
4. Copy APK vào điện thoại, bật "Cài từ nguồn không xác định" rồi cài đặt bình thường.

**Lưu ý:** Project chưa kèm sẵn Gradle Wrapper (`gradle-wrapper.jar`) vì cần build thử
mới generate được đúng phiên bản — workflow dùng action `gradle/actions/setup-gradle`
để tự cài Gradle 8.6 trên runner thay vì phụ thuộc wrapper, nên không cần lo thiếu file
này. Nếu anh build local bằng Android Studio, IDE sẽ tự tạo wrapper giúp anh khi mở project.
