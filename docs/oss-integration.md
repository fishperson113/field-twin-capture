# OSS: worker ngoài điện thoại

Tài liệu nghiên cứu tham khảo cho giai đoạn sau. Phạm vi hiện tại chỉ là app thu và hợp đồng xuất; không triển khai worker hoặc chọn môi trường dựng. Các yêu cầu về GPU phía dưới chỉ áp dụng khi người dùng quyết định làm hệ dựng, không phải blocker cho phần thu.

## MapAnything ưu tiên thử nghiệm

Nguồn chính thức: https://github.com/facebookresearch/map-anything, đọc ngày 2026-11-04. Repo có inference ảnh thuần và input bổ sung calibration, depth, camera poses. IMU và audio không phải input trực tiếp của model. Các modality này cần các công cụ riêng tạo trajectory, temporal/semantic associations rồi liên kết trong evidence graph.

Kế hoạch adapter, **chưa triển khai inference**:

1. Chọn ảnh từ subgraph theo khoảng khảo sát. Giữ ảnh gốc; tạo biến thể resize/crop có transform và intrinsics tương ứng.
2. Có thể thử image-only ngay khi có ảnh đạt yêu cầu, không bắt buộc RoNIN hay camera poses. Nếu cung cấp depth_z thì phải có intrinsics hoặc ray directions; không cung cấp đồng thời cả hai. Nếu có pose ở view nào thì reference view đầu tiên phải có pose.
3. Pose đầu vào theo OpenCV camera-to-world, X phải/Y xuống/Z trước. Chuyển từ Android/device frame bằng extrinsics đã biết; không suy ra từ tên trục.
4. Lưu outputs point cloud/depth, camera estimates, masks/confidence dưới dạng derived artifacts, kèm model revision, input IDs, tham số và transforms. Model confidence không được gọi là sai số đo đã calibration.
5. Đo scale/coverage/consistency với bằng chứng khảo sát độc lập; agent dùng kết quả để chọn bước tăng cường tiếp theo, không tự áp luật fallback 2D.

Repo hỗ trợ các model khác qua interface chung, phù hợp so sánh trên cùng capture. Không hứa một model là tốt nhất cho thiết bị/dữ liệu của team trước benchmark. Code Apache 2.0; weights mặc định có điều kiện giấy phép riêng (CC-BY-NC 4.0 theo nghiên cứu từ nguồn chính thức). Xem lựa chọn `facebook/map-anything-apache` và kiểm tra giấy phép từng checkpoint trước dùng. Chưa tải weights.

Python/PyTorch chạy worker ngoài thiết bị, chọn CUDA nếu có, CPU là khả năng API chứ không phải cam kết tốc độ thực dụng. Chưa chọn nhà cung cấp GPU, ngân sách hoặc cấu hình VRAM. CameraX/SensorManager trên Android chỉ thu file, metadata và kiểm tra nhẹ; audio tùy chọn; depth/LiDAR chỉ khi thiết bị cung cấp. Không yêu cầu mua thiết bị cao cấp để bắt đầu image-based capture.

## Odometry thay thế được

RoNIN trong các repo local là reference đang có, không phải dependency bắt buộc của pipeline mới. SSIO có source train/eval tại https://github.com/amazon-far/SSIO (nguồn nghiên cứu do parent thread cung cấp), cần đối chiếu API/checkpoint/calibration khi thực sự tích hợp. Theo nghiên cứu tại https://github.com/amazon-far/SSIO/blob/main/Doc/training.md do parent chuyển, một số cấu hình dùng attitude tham chiếu để bỏ gravity, rotation/velocity ban đầu để neo graph và velocity tham chiếu để chọn model; self-supervised không đồng nghĩa pipeline điện thoại turnkey không cần tham chiếu. Covariance head PGD chưa train, graph dùng sigma đẳng hướng điều chỉnh được. Xem đây là thí nghiệm, không ground truth metric. GNIO: https://arxiv.org/abs/2603.15281 đã được parent xác minh paper; chưa xác minh code công khai, nên không tạo dependency hoặc hứa adapter hoạt động.

## Audio và cảm biến khác

Chưa chốt engine speech-to-text; transcript phải giữ timestamp spans, speaker/uncertainty khi có, links về audio và các mốc ảnh. Không dùng transcript làm ground truth hình học. Depth, magnetic và các sensor khác cần capability detection, đơn vị/frame/clock riêng. Schema graph chừa modality và provenance; packager v1 hiện chỉ nhập session ảnh/IMU/mốc có sẵn, chưa ingest audio/depth.

## Tham khảo logger cho điện thoại phổ thông

Nguồn nghiên cứu từ parent: https://github.com/OSUPCVLab/mobile-ar-sensor-logger (video, IMU, timestamp frame và metadata camera trên clock chung) và https://github.com/prime-slam/OpenCamera-Sensors (cảnh báo về audio/MediaRecorder có thể làm chèn frame; cân nhắc tắt image stabilization khi thu geometry). Chỉ tham khảo thiết kế, chưa chép code hoặc tích hợp.

Ghi timestamp/clock source cho từng frame; presentation timestamp không tự được coi là exposure time. Audio track phải có clock mapping và dropped/inserted frame diagnostics. Kiểm tra sensor availability, frequency và metadata runtime trên điện thoại thật. Stabilization/crop có thể ảnh hưởng calibration, cần thử trên thiết bị; không tự thay settings hệ thống. ARCore Depth phụ thuộc thiết bị, thường dựa depth-from-motion, không đồng nghĩa LiDAR phần cứng.

Trước bước capture thật cần danh sách 1–2 điện thoại Android team sẽ dùng và nơi chạy worker/GPU; không chặn việc dựng schema/khung, nhưng chưa thể cam kết rate, depth capability, chất lượng ảnh hay thời gian inference khi thiếu các thông tin này.

## Baseline và kiểm chứng tiếp theo

Nghiên cứu bổ sung từ parent, chưa tích hợp hoặc kiểm tra runtime: [GINIO](https://github.com/chankyo123/GINIO) có code/checkpoint MIT, đáng đánh giá độ bền theo hướng cầm máy; chưa xác minh chạy Android. [RTAB-Map](https://github.com/introlab/rtabmap) có tích hợp Android/ARCore để tham khảo baseline thực tế. OpenVINS và ORB-SLAM3 có giấy phép GPL, cần đối chiếu bản/license cụ thể trước chọn dependency cho sản phẩm.

Không nhân confidence của ARCore pose, IMU và các output tái sử dụng cùng quan sát như các nguồn độc lập. Lưu quan hệ derived_from và input IDs để nhận biết phụ thuộc; confidence model không thay covariance được kiểm chuẩn.

Thử nghiệm đầu tiên nên replay gói khảo sát từ các điện thoại team thực sự dùng: đo drift, mức bao phủ, pin/nhiệt khi capture và đối chiếu vài khoảng cách đo vật lý. So sánh các adapter trên cùng dữ liệu và giữ provenance/configuration. Đây là kế hoạch kiểm chứng, chưa có kết quả benchmark hoặc ngưỡng nghiệm thu được chốt. MCAP chỉ là ứng viên container; không thay schema clock/frame và provenance của evidence graph.
