# Phần thu trên điện thoại và hợp đồng bàn giao

## Ranh giới đã chốt

Điện thoại là nơi thu. Môi trường dựng do người dùng chọn về sau. Đầu ra của app là một gói dữ liệu tự mô tả, độc lập với model, backend và nơi lưu/chạy dựng. App không phải chờ MapAnything, odometry, GPU hoặc agent server mới thu được dữ liệu.

Những nghiên cứu trong `oss-integration.md` và vòng lặp twin trong `multimodal-representation.md` là bối cảnh thiết kế hợp đồng xuất, không phải yêu cầu triển khai hệ dựng ngay. Công cụ Python hiện là công cụ kiểm tra fixture/export; không phải dịch vụ dựng được triển khai.

## Phần cần làm tốt trong app thu

- Phát hiện capability runtime: camera, IMU, microphone và depth tùy thiết bị. Sensor không có phải ghi unavailable, không ghi số 0 thay dữ liệu thiếu. Không yêu cầu LiDAR hoặc máy cao cấp.
- Thu file gốc và metadata: camera ID, kích thước/rotation, timestamp source; IMU raw có đơn vị/frame và tần số thực; audio tùy cấu hình có sample format và clock mapping. Không gọi callback/presentation time là exposure khi chưa chứng minh.
- Giữ các liên kết ngay lúc thu: session/asset/observation IDs, clock domains, capture intervals, camera metadata, calibration có sẵn và khoảng pause/drop/gap. Thiếu extrinsics/calibration phải để unknown, không cần tự dựng pose để xuất.
- Viết dữ liệu ngoài main thread; thiết kế flush, recovery sau interruption, dung lượng trống và trạng thái pause/resume. Cần thử pin/nhiệt và storage trên máy thật trước chốt capture profile.
- Cho người khảo sát thêm mốc, ghi chú và số đo tham chiếu; liên kết tới asset/time thay vì chỉ lưu tên tự do. Không tự suy diễn ghi chú thành ground truth hình học.
- Kiểm tra trước xuất: file tồn tại, paths hợp lệ, timestamps/đơn vị rõ, danh sách capability, gaps, hashes và trạng thái session complete/interrupted. Báo lỗi thu có thể khắc phục, không tự tuyên bố đủ độ chính xác để dựng twin.
- Xuất qua Android document picker tới nơi người dùng chọn; gói có manifest, asset inventory, metadata/links và raw bytes. Không phụ thuộc cloud account, không tự upload.

Đây là yêu cầu triển khai capture, chưa phải chức năng đã hoạt động. Chưa chốt tần số/profile, chiến lược audio hay ngưỡng chất lượng bằng suy đoán; cần đo trên điện thoại thực tế và hỏi người dùng với các quyết định ảnh hưởng thao tác khảo sát.

## Consumer về sau được nhận gì

Raw artifacts và các liên kết biết được lúc thu. Derived artifacts (transcript, odometry, poses, embeddings, point cloud, mesh) do môi trường dựng tạo sau và phải giữ input IDs/provenance. Cùng schema clock/frame không có nghĩa app phải thực hiện fusion. Đổi consumer/model không làm mất bằng chứng gốc.

## Trạng thái hiện tại

POC Kotlin đã có recorder IMU, CameraX JPEG, audio PCM opt-in, mốc/revisit, session list, interruption recovery và ZIP export nối vào UI. Chi tiết và giới hạn tại [poc-capture.md](poc-capture.md). Depth/LiDAR và sensor fusion chưa triển khai. Validator/packager Python được kiểm tra bằng synthetic fixtures; Android code cần CI và thiết bị thật để xác minh.

Bước tiếp theo chỉ thuộc phía thu: kiểm chứng trên điện thoại thực tế, profile khảo sát, pin/nhiệt và chất lượng đồng bộ. Không yêu cầu chọn môi trường dựng/GPU. Người dùng đã yêu cầu khởi tạo GitHub public; chỉ source và synthetic fixtures được publish, không có capture thật hoặc tài liệu đính kèm riêng. Build APK bằng GitHub Actions, không build trên máy hiện tại.
