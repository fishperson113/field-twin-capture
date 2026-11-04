# POC thu dữ liệu trên Android

## Cách dùng

1. Cài debug APK từ artifact `app-debug` của GitHub Actions. Android 8.0+; camera/microphone là tùy chọn, không yêu cầu LiDAR.
2. Nhập tên địa điểm. Cấp quyền camera nếu muốn chụp; bật công tắc ghi âm nếu muốn thu audio và cấp quyền microphone. Audio mặc định tắt.
3. Bắt đầu phiên, giữ app ở foreground. IMU ghi trên handler thread; cảm biến không có hoặc đăng ký thất bại được cảnh báo, không thay bằng số 0.
4. Chụp JPEG gốc từ CameraX; đặt tên mốc và bấm thêm mốc. “Quay lại mốc” dùng tên mốc đã thêm, là quan sát thủ công chứ không giải pose graph.
5. Dừng và lưu. Chọn session đã lưu để xuất ZIP qua Android document picker. App không có quyền Internet và không upload dữ liệu.

Rời foreground dừng phiên và đánh dấu interrupted. Sau process death, mở app sẽ nhận diện manifest còn recording và cho xuất dữ liệu đã lưu, với cảnh báo mất phần buffer cuối. Không tự thu nền. Session lưu trong app-private storage; gỡ app sẽ mất dữ liệu chưa xuất. Danh sách UI hiện tối đa 10 session gần nhất. Chưa có thao tác xóa session trong POC.

## Gói xuất v1.1

```text
manifest.json           status, device capabilities, requested rate, events, measured sensor statistics
imu.jsonl               per-sensor monotonic timestamp, raw values, Android accuracy
photos.json             image ID, original dimensions/rotation, request/return interval, camera timestamp/clock
camera.json             camera ID, timestamp source, optional hardware calibration metadata
landmarks.json          named observations with timestamp and optional last-photo reference
loop_closures.json      manual revisits
audio.json              optional PCM track metadata
images/*.jpg
audio/track.pcm         optional 16 kHz mono PCM signed 16-bit little-endian
audio/anchors.jsonl     optional AudioTimestamp BOOTTIME anchors and read intervals
index.json              session-prefixed asset IDs, file sizes, SHA-256, warnings
evidence-graph.json     raw asset/session relationships and photo/audio time spans
EXPORT_README.txt
```

Sensor sampling requests 200 Hz; Android/device can deliver less. Record actual count, first/last timestamp, maximum gap and mean rate. The 100 MB start / 20 MB stop storage thresholds are conservative POC guards, not reconstruction quality thresholds.

Camera image timestamps use the camera clock. If CameraCharacteristics reports REALTIME, metadata identifies the BOOTTIME domain; otherwise the camera clock stays separate. Capture request/return intervals remain available on BOOTTIME. Hardware intrinsics are explicitly not treated as calibrated output intrinsics after crop/rotation. No camera-to-device extrinsics or pose is invented.

Audio uses a separate AudioRecord thread, no video/audio muxer. AudioTimestamp anchors record frame position and BOOTTIME when available, otherwise read intervals only. Exact exposure/audio alignment and audio latency are not validated. A failed audio read is kept as a warning/error in track metadata at stop. PCM is storage-heavy (about 1.9 MB/minute); this POC records only after opt-in.

Export hashes files in chunks and streams ZIP; images/audio are not embedded in JSON. Existing raw bytes are retained. Scene semantics, temporal fusion, calibration, odometry, transcription, depth capture and reconstruction are not implemented.

## Validation and device checks

After extracting a ZIP, run `python pipeline/map_session.py validate <session-directory>` (or prefix `uv run`). Validator accepts v1.0 fixture and v1.1 export, checks monotonic sensor data, metadata references, audio lengths, export hashes and graph endpoints. No data is sent to a model.

CI runs Python tests, Robolectric tests for atomic metadata and ZIP/hash contract, then assembles APK. This does not test real hardware. Before a field trip, test on the actual phone: permissions granted/denied, sensors absent, several JPEG captures during IMU/audio, timestamps/gaps, interruption/relaunch/export, mic error handling, free storage, and pin/heat over a representative session. Keep test captures out of the public repository.
