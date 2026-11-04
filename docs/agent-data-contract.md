# Dữ liệu cho agent: đọc ít trước, truy xuất bằng chứng sau

Phone POC v1.1 hiện có code capture/export riêng, xem [poc-capture.md](poc-capture.md). Các phần mô tả v1 dưới đây là thiết kế nền; không phải tất cả modality/derived processing đã triển khai. Gói phone có audio PCM/anchors tùy chọn và camera clock metadata, chưa có fusion.

## Mục tiêu

App độc lập thu và liên kết dữ liệu multimodal để hỗ trợ vòng lặp agent xây digital twin. Xem [biểu diễn chung](multimodal-representation.md) cho mô hình raw → liên kết thời gian/tọa độ → artifact tăng cường → twin revision. 2D/3D là các output theo mục tiêu, không có luật fallback 2D tự động. Agent có thể chọn xử lý lại, calibration, thêm modality hoặc yêu cầu thu bổ sung.

Agent điều phối và kiểm tra; thuật toán hình học/odometry xử lý dữ liệu số. Không yêu cầu LLM tự tích phân hàng triệu mẫu IMU hoặc đo hình học chính xác bằng suy luận văn bản.

## Hai lớp đang có

1. **Session raw v1**: dữ liệu thu ban đầu, theo `session-format.md`. ID, timestamp monotonic, sensor frame và đơn vị phải rõ. File gốc không bị sửa bởi reconstruction.
2. **Gói cho agent v1**: `prepare-agent` sao chép các bằng chứng đã validate vào `raw/`, tạo `index.json` nhỏ và `AGENT_README.md`. Mỗi asset có ID, đường dẫn tương đối, byte size và SHA-256. Index có số lượng, tần số trung bình/gap lớn nhất từng sensor, số ảnh, cờ synthetic và các giới hạn. Công cụ không chạy mô hình, không tự đặt ngưỡng “đủ tốt”.

```powershell
uv run python pipeline/map_session.py prepare-agent examples/corridor outputs/agent-corridor
```

Thư mục output phải mới và nằm ngoài session gốc. Mẫu hiện không có ảnh, nên index báo reconstruction ảnh bị chặn. File `examples/corridor/walls.json` là hình học synthetic độc lập, không tự đưa vào gói bằng chứng như thể đã đo được.

## Quy trình đọc hiệu quả

- Đọc index để biết đang có gì và thiếu gì; không đưa ảnh và IMU đầy đủ vào prompt đầu tiên.
- Đọc metadata mốc và ảnh; chọn tập ảnh phủ không gian và khoảng thời gian liên quan bằng ID. Bản khung chưa tự chọn keyframe/contact sheet hoặc lập chỉ mục theo chunk; cần làm khi có dữ liệu thật để tránh đoán kích thước chunk.
- Giao thuật toán đọc raw từ file, trả thống kê/trajectory/point cloud thành artifact. Agent kiểm tra kết quả và chỉ đọc đoạn log cần chẩn đoán.
- Ghi mọi kết luận bằng tham chiếu asset và nguồn: observed (đo/nhìn trực tiếp), estimated (mô hình suy ra), assumed (giả định được công bố). Không đổi estimated thành observed.

## Metadata cần bổ sung trước reconstruction thật

Đây là yêu cầu thiết kế tiếp theo, chưa phải trường đã được app ghi:

| Dữ liệu | Cần ghi | Vì sao |
|---|---|---|
| Ảnh | ID ổn định, ảnh gốc, kích thước, camera ID, exposure timestamp hoặc khoảng thời gian với độ tin cậy, xoay ảnh | Ghép đúng camera/IMU và bảo toàn ảnh gốc |
| Camera | fx/fy/cx/cy, distortion, image size, nguồn calibration | Tránh dùng sai intrinsics sau resize/crop |
| IMU | sensor model/range, tần số thực, drop/gap, bias và calibration ID | Phát hiện sai đơn vị, sai nhịp, drift |
| Extrinsics | phép biến đổi camera-to-device, convention và nguồn đo | Rotation vector của điện thoại không phải pose camera |
| Mốc/số đo | ID, thời gian, ảnh liên quan, loại quan sát, chiều dài/đơn vị/uncertainty nếu có | Chốt scale, ràng buộc loop closure, giữ bằng chứng |
| Không gian | session/site/floor ID, nguồn xác định tầng, transform giữa session | Không ghép nhiều tầng hoặc nhiều lượt bằng tên mốc trùng nhau |

Khi thêm các trường này phải tăng version schema và cập nhật validator, mẫu và Kotlin contract cùng nhau. Không nhét trường chưa xác định vào v1 với số giả.

## Hợp đồng output cần giữ

Mỗi lần xử lý có run ID, input IDs + hashes, thuật toán/checkpoint/version, cấu hình, transforms và hệ tọa độ, cảnh báo, status `success|partial|blocked`. Derived geometry tách khỏi raw. 3D cần scale/coverage/uncertainty được báo rõ. 2D cũng cần tỷ lệ nếu nói đơn vị mét; bản schematic phải được gắn nhãn schematic. Liên kết mốc/phòng tới bằng chứng và giữ danh sách unresolved issues.

Định dạng production cho mesh và 2D cần chốt khi có consumer; OBJ hiện chỉ là phép xuất wall quads mẫu. Chưa mặc định dùng pixel schema của app dẫn đường cũ cho bản đồ metric mới.
