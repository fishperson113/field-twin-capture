# Stack và phạm vi sau khi đọc repo hiện có

Ngày 2026-11-04. Phạm vi: dựng khung và thống nhất stack, không build APK trên máy này. Ba repo được đọc để tham khảo, không chỉnh sửa.

Phạm vi mới nhất: chỉ phát triển phần thu trên điện thoại và hợp đồng xuất, xem [capture-scope.md](capture-scope.md). Các dòng worker/odometry/reconstruction bên dưới chỉ là lựa chọn tham khảo cho consumer về sau, chưa cần triển khai hoặc chọn môi trường dựng.

## Stack cho app khảo sát độc lập

Người dùng đã chốt app tách biệt với `internal-hospital-map`. Trọng tâm là biểu diễn dữ liệu multimodal được liên kết và tăng cường để agent làm việc trong vòng lặp digital twin, xem [multimodal-representation.md](multimodal-representation.md). Điện thoại Android phổ thông thu dữ liệu nhẹ; xử lý nặng ngoài thiết bị. Audio, depth/LiDAR là modality theo capability, không bắt buộc phần cứng cao cấp. Không có luật fallback 2D tự động.

| Phần | Chọn | Lý do từ repo hiện có |
|---|---|---|
| App thu dữ liệu | Kotlin, Jetpack Compose Material 3 | Đồng bộ `internal-hospital-map`, tránh thêm một app Java khác stack |
| State và tác vụ nền | ViewModel, StateFlow, Coroutines, manual DI | Giữ cách tổ chức đã có, chưa thêm Hilt hoặc nhiều module |
| IMU | Android SensorManager | Timestamp monotonic, log raw theo sensor, kiểm tra tần số thực |
| Camera | CameraX | Repo Android đã có CameraX 1.4.2; cần ảnh gốc, intrinsics và timestamp trước reconstruction |
| Lưu và trao đổi | File cục bộ, JSON manifest + JSONL IMU + ảnh, xuất ZIP | Không cần backend mới hay database cho phiên khảo sát ban đầu |
| Pipeline offline | Python, quản lý bằng uv | Đồng bộ hướng pipeline của `MLAI_Hackathon`; khi tích hợp repo đó dùng Python 3.13 và môi trường của repo |
| Odometry | Interface thay thế được, chưa bắt buộc backend | RoNIN hiện có là reference; đánh giá model hiện đại phù hợp trước khi chọn |
| Reconstruction | Adapter MapAnything trên máy/GPU riêng | Chưa tải weights, chưa chạy inference; GNIO/SSIO là nghiên cứu giai đoạn sau |
| Agent | Skill trong repo đọc session đã chuẩn hóa | Không cần ElevenLabs, agent server hoặc API mới để dựng khung |
| Output 3D | OBJ mẫu để kiểm tra geometry; định dạng production chốt khi nối viewer | Không coi mesh mẫu là bản đồ tái dựng từ dữ liệu thật |

## Những gì đã đọc được

- `C:\workspace\internal-hospital-map\README.md`, `app/build.gradle.kts`, `gradle/libs.versions.toml`: Kotlin/Compose, MVVM/StateFlow, manual DI, CameraX, OpenGL ES 2.0. Cấu hình repo hiện có AGP 9.1.1, Kotlin 2.2.10, CameraX 1.4.2. Đây là thông tin hiện trạng, chưa sao chép bộ version sang khung mới.
- `C:\workspace\MLAI_Hackathon\AGENTS.md`, `_context/README.md`, `_context/04_pipeline/io_spec.md`, `pyproject.toml`: app và pipeline ở hai repo, Python 3.13/uv. Schema floorplan hiện có dùng pixel, X sang phải/Y xuống; map mới theo mét cần adapter chuyển hệ tọa độ và scale rõ ràng, không thay schema cũ âm thầm.
- `C:\workspace\MLAI_Hackathon\src/backend/ronin.js`: inference ONNX, 200 Hz, window 200, stride 10, gyro + acceleration gồm gravity đã quay về gravity-aligned frame. Input backend mang quy ước W3C của web; không gửi trực tiếp vector native Android vào đó.
- `C:\workspace\internal-hospital-map\app/src/main/java/com/example/hospital/data/api/RoninClient.kt`: có client RoNIN để xem lại khi chọn online/offline.
- `C:\workspace\ronin\README.md`, `source/data_glob_speed.py`, danh sách `pretrained/`: đã có source và pretrained assets. Chuẩn bị dữ liệu gồm bias/calibration và phép quay quaternion trước feature gyro/acceleration. Chưa chạy code hay mở checkpoint.

## Trạng thái khung đang giữ để review

`app/` đã triển khai POC Kotlin/Compose với recorder IMU, CameraX JPEG, audio PCM opt-in, mốc/revisit và xuất session có checksum/graph. Xem `poc-capture.md`. Các draft trước giữ local ngoài source set và không publish. Chưa có fusion hoặc worker dựng.

Version hiện ghim: Kotlin và Compose compiler 2.1.10, AGP 8.9.2, Gradle 8.11.1, SDK 35, JVM target 17; CI chạy JDK 21. Compose BOM 2024.09.00, activity-compose 1.10.1, lifecycle 2.8.7, coroutines 1.10.1, CameraX 1.4.2. Chọn bộ version bảo thủ cho khung, chưa tuyên bố build thành công. Python mẫu không dependency, pyproject dùng uv, Python >=3.11 để chạy với runtime hiện có; khi tái dùng code repo MLAI cần môi trường Python 3.13 riêng của repo đó.

## Quyết định đã rõ và phần để sau

App riêng, project `indoor-map`; không sửa repo Android cũ. Môi trường dựng, consumer output và tiêu chí reconstruction để người dùng chọn sau. Recorder POC đã nối; profile khởi đầu yêu cầu IMU 200 Hz, JPEG thủ công, PCM 16 kHz mono khi người dùng bật audio (mặc định tắt). Đây là profile thử nghiệm, không ngưỡng đảm bảo chất lượng. Cần điện thoại thực tế để đo và điều chỉnh trước field trip.

Không mở rộng backend, không chạy mô hình tốn credit, không xuất bản, không build APK trên máy này. APK sẽ build bằng GitHub Actions. Trước khi yêu cầu mới này đến, đã thử Gradle nhưng timeout lúc tải distribution; chưa compile và chưa tạo APK.

## Build APK trên GitHub Actions

Đã tham khảo `.github/workflows/build-apk.yml` của `internal-hospital-map`: checkout v4, Temurin 21, setup-gradle v4, assembleDebug, upload-artifact v4. Khung Kotlin có workflow tương ứng tại `.github/workflows/build-apk.yml`, dùng Gradle 8.11.1 theo AGP 8.9.2 hiện tại, cài SDK 35 và lưu artifact `app-debug` trong 14 ngày. Trigger: push vào main, pull request, hoặc workflow_dispatch.

Người dùng đã yêu cầu tạo repo GitHub public và push POC, cho phép Actions kiểm tra export contract và build debug APK. Trạng thái chạy thực tế xem tab Actions. Artifact debug dành cho thử nghiệm; chưa cấu hình signing release hoặc phát hành. Không build APK local.
