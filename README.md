# Indoor Map

**Phạm vi hiện tại: app điện thoại thu thập dữ liệu và hợp đồng xuất.** Điện thoại thu ảnh/IMU và các modality tùy chọn, kiểm tra chất lượng thu, giữ metadata rồi xuất gói độc lập. Hệ dựng/tăng cường/digital twin nằm ngoài app; người dùng sẽ chọn môi trường và triển khai sau. Chưa cần worker, GPU, backend hay kết nối model để phát triển phần thu.

App khảo sát Android độc lập, tập trung biểu diễn dữ liệu multimodal để agent xây digital twin theo vòng lặp: dữ liệu gốc → liên kết thời gian/tọa độ → dữ liệu tăng cường → twin revision. Ảnh, IMU, audio và depth/LiDAR tùy thiết bị được liên kết bằng evidence graph, không ép tất cả thành văn bản. Không có luật thiếu bằng chứng thì tự chuyển sang 2D.

Stack phần thu: Kotlin/Compose + ViewModel/StateFlow, CameraX/SensorManager cho điện thoại phổ thông. Python/uv hiện chỉ phục vụ kiểm tra hợp đồng xuất bằng fixture. MapAnything và các OSS dựng hình chỉ là nghiên cứu cho consumer tương lai, chưa phải hạng mục triển khai. Bắt đầu từ [ranh giới phần thu](docs/capture-scope.md), [stack](docs/stack.md) và [hợp đồng dữ liệu](docs/multimodal-representation.md).

Không build APK trên máy này. Đã chuẩn bị [GitHub Actions](.github/workflows/build-apk.yml) để build debug APK và lưu artifact `app-debug`, giống cách repo Kotlin đang dùng. Chưa push/chạy CI. Hướng dẫn build cục bộ bên dưới chỉ là tham chiếu cho phát triển về sau.

## Chạy Android

Mở thư mục này bằng Android Studio, cài Android SDK Platform 35 và Build Tools 35.0.0, dùng JDK 17 hoặc 21. Đã kèm Gradle wrapper chính thức 8.11.1 + Android Gradle Plugin 8.9.2. Chạy `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`). Chạy app trên điện thoại có accelerometer và gyroscope.

POC đã triển khai Kotlin recorder: IMU, ảnh JPEG CameraX, mốc/quay lại mốc, audio PCM tùy chọn, lưu session, nhận diện interruption và xuất ZIP có checksum/graph qua document picker. Xem [cách dùng và giới hạn POC](docs/poc-capture.md). Chưa tích hợp fusion, depth/LiDAR, inference hay dựng twin. Chưa kiểm chứng trên điện thoại thật; trạng thái build xem GitHub Actions.

## Chạy pipeline ngay, không cần dependency

```powershell
python pipeline/map_session.py validate examples/corridor
python pipeline/map_session.py export-walls examples/corridor/walls.json outputs/corridor.obj
python pipeline/map_session.py prepare-agent examples/corridor outputs/agent-corridor-new
python -m unittest discover -s pipeline -v
```

Fixture hành lang 47,4 m là **synthetic**, dùng kiểm tra hợp đồng dữ liệu và xuất geometry; không chứng minh độ chính xác của reconstruction. Mở OBJ bằng Blender hoặc trình xem 3D hỗ trợ OBJ.

Có thể thêm tiền tố `uv run` cho các lệnh Python trên. Packager yêu cầu thư mục output mới; xuất `index.json`, `evidence-graph.json`, hướng dẫn agent và raw assets có SHA-256. Graph hiện mới liên kết thành viên session, chưa làm temporal alignment, audio/depth ingest hoặc sensor fusion. Schema dự thảo tại [contracts/evidence-graph.schema.json](contracts/evidence-graph.schema.json).

Đã chạy local: 12 test Python, kiểm tra graph schema/references và skill. Android unit tests và build APK chỉ chạy trên GitHub Actions, không build APK local. Cần kiểm tra vật lý trên điện thoại trước dùng khảo sát thật.

Skill agent: [.agents/skills/reconstruct-indoor-map/SKILL.md](.agents/skills/reconstruct-indoor-map/SKILL.md). Skill nằm trong repo, không thay đổi cấu hình global.
