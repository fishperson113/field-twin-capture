# Biểu diễn chung cho vòng lặp digital twin

Phạm vi triển khai hiện tại chỉ là phía thu và hợp đồng xuất, xem [capture-scope.md](capture-scope.md). Đồ thị raw/clock/frame được bàn giao từ điện thoại; tăng cường và dựng twin là consumer tương lai. Tài liệu này giữ thiết kế xuyên suốt để export không khóa vào một hệ dựng, không yêu cầu triển khai vòng lặp ngay.

## Mục tiêu đã làm rõ

App độc lập là nguồn thu dữ liệu cho vòng lặp agent xây digital twin. Trọng tâm là nối ảnh, IMU, audio, depth/LiDAR khi thiết bị hỗ trợ và các cảm biến khác thành dữ liệu có cấu trúc, sau đó tăng cường bằng các công cụ. 2D và 3D là những sản phẩm có thể tạo, không phải luật thiếu bằng chứng thì chuyển sang 2D. Agent có thể yêu cầu thu thêm, dùng modality khác, hiệu chỉnh hoặc xử lý lại.

## Chọn biểu diễn: file bằng chứng + đồ thị liên kết JSON

Không chuyển mọi modality thành văn bản hoặc một vector duy nhất. Giữ bytes gốc trong các file, dùng đồ thị nhẹ để liên kết chúng. JSON dễ đọc, diff, kiểm tra schema và truy xuất bằng ID; chưa cần graph database, vector database hay backend mới. Embedding có thể là artifact dẫn xuất sau này, không thay ảnh/audio/IMU gốc.

| Lớp | Nội dung | Quy tắc |
|---|---|---|
| Raw artifacts | Ảnh, video/audio, IMU, magnetic, depth/point cloud, calibration | ID ổn định, checksum, đơn vị, clock/frame; không ghi đè |
| Liên kết | Cùng session, khoảng thời gian, pose, extrinsics, liên quan đến mốc | Ghi relation, nguồn tạo, trạng thái candidate/validated; cùng lúc không đồng nghĩa cùng vật thể |
| Derived artifacts | Transcript có time span, keyframes, odometry, pose graph, semantic observations, point cloud/mesh | Ghi input IDs, tool/model/version, tham số, uncertainty và validation |
| Twin revisions | Các thực thể/phòng/bề mặt và hình học của một revision | Mỗi kết luận trỏ về bằng chứng/derived artifact; giữ giả thuyết mâu thuẫn và lịch sử sửa |

Schema khởi đầu: `contracts/evidence-graph.schema.json`. Công cụ Python `prepare-agent` tạo graph thành viên session từ dữ liệu v1. Phone POC v1.1 bổ sung time spans ảnh/audio và candidate temporal associations với khoảng IMU trùng nhau, không coi đó là spatial registration. Không tạo transcript, depth, pose hoặc embedding giả; chưa có thuật toán fusion.

## Thời gian và tọa độ

- Mỗi nguồn có clock ID, timestamp hoặc interval, uncertainty và nguồn timestamp. Android IMU dùng boot monotonic nanoseconds. Audio sample index cần ánh xạ với clock; camera exposure timestamp cần biết clock domain, không coi callback time là exposure.
- Clock mapping là artifact riêng: source clock, target clock, offset/drift, khoảng hiệu lực, sai số, nguồn ước lượng. Không ghép Unix time với elapsedRealtimeNanos bằng một giả định ngầm.
- Mỗi dữ liệu hình học có frame ID và đơn vị. Transform có from/to, convention, ma trận hoặc pose, covariance/uncertainty, timestamp/validity và provenance. Không coi orientation điện thoại là pose camera.
- Quan hệ “gần thời gian” chỉ tạo candidate association. Cần calibration/registration hoặc xác nhận khác để thành liên kết hình học đã kiểm chứng.

## Vòng lặp agent

1. Đọc index nhỏ và graph để biết modalities, calibration, các khoảng thiếu và những kết quả đã có.
2. Chọn subgraph theo khu vực, thời gian, mốc hoặc câu hỏi; tải đúng ảnh/đoạn audio/chunk sensor liên quan, không dồn toàn bộ raw vào context.
3. Chạy công cụ phù hợp: đồng bộ, calibration, speech-to-text, odometry, multi-view/depth fusion, semantic extraction. Các adapter này chưa triển khai.
4. Ghi artifact tăng cường cùng provenance; liên kết tới inputs, temporal spans và frames. Agent kiểm tra mâu thuẫn và đề xuất twin revision.
5. Đánh giá revision, chỉ rõ uncertainty và nguồn; quyết định xử lý lại, bổ sung modality/quan sát hoặc tạo output 2D/3D theo mục tiêu. Không áp luật chọn chiều cố định theo mức thiếu dữ liệu.

Agent cần một context view ngắn chứa các thực thể/quan sát liên quan và references có thể truy xuất, không phải một bản tóm tắt mất nguồn. Định dạng context view và cơ chế tool retrieval sẽ triển khai sau khi có capture thật; hiện index/graph là nền.

## Thiết bị và phạm vi khung

Depth/LiDAR là capability tùy thiết bị. Chưa chọn ARCore Depth hay vendor SDK, chưa giả định Android nào cũng có LiDAR. Audio cần thiết kế consent/permission và clock mapping trước capture. Chưa xin quyền, chưa thu dữ liệu trên máy/điện thoại, chưa tải hoặc gọi model.

Phần đã chạy local: Python validate/package/checksum và tests trên fixtures synthetic. Phone POC đã có code thu IMU/ảnh/audio và ZIP export, cần CI và kiểm tra trên thiết bị thật. Chưa có multimodal fusion, transcript, model inference hoặc vòng lặp agent tự động.

## Các lựa chọn định dạng để đánh giá tiếp

Nghiên cứu từ parent, chưa thêm dependency:

| Lựa chọn | Vai trò có thể dùng | Điểm cần kiểm chứng |
|---|---|---|
| [MCAP](https://mcap.dev/) | Container sensor có timestamps/schema/index, kết hợp manifest provenance | Đánh giá writer Android, overhead và truy xuất chunk trên capture thật trước thay JSONL |
| [Rerun](https://rerun.io/) | Timeline và kiểm tra ảnh/pose/point cloud trên máy xử lý | Là viewer/debug tool, không thay hợp đồng bằng chứng |
| [Spark-DSG](https://github.com/MIT-SPARK/Spark-DSG) / [Hydra](https://github.com/MIT-SPARK/Hydra) | Tham khảo graph phòng/vật thể/quan hệ cho twin revision | Hydra là hạ tầng nghiên cứu ROS2, không Android-ready; chưa đưa vào stack |
| [WAI của MapAnything](https://github.com/facebookresearch/map-anything/blob/main/data_processing/README.md) | Adapter dữ liệu reconstruction/training | Còn alpha theo nghiên cứu parent; không làm nguồn sự thật duy nhất |

JSON + files là scaffold dễ kiểm tra hiện tại, chưa phải kết luận rằng đây là container tối ưu cho log dài. Giữ asset IDs, clock/frame contracts và provenance độc lập container để có thể chuyển sang MCAP mà không thay cách agent hiểu liên kết.
