package dev.indoormap.capture

import android.content.Context
import android.hardware.*
import android.os.*
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.time.Instant
import java.util.UUID

class AndroidCaptureRepository(context: Context) : SensorEventListener {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val base = File(app.filesDir, "sessions").also { it.mkdirs() }
    private val guard = Any()
    private val thread = HandlerThread("field-imu").also { it.start() }
    private val handler = Handler(thread.looper)
    private val mutable = MutableStateFlow(RecorderState())
    val state = mutable.asStateFlow()
    private var root: File? = null
    private var writer: BufferedWriter? = null
    private var manifest = JSONObject()
    private var photos = JSONArray()
    private var landmarks = JSONArray()
    private var closures = JSONArray()
    private var audioTracks = JSONArray()
    private var audio: AudioCapture? = null
    private var cameraInfo = JSONObject().put("available", false)
    private var lastFlush = 0L
    private val stats = mutableMapOf<String, SensorStats>()
    private val sensorTypes = linkedMapOf(Sensor.TYPE_ACCELEROMETER to "accelerometer", Sensor.TYPE_GYROSCOPE to "gyroscope",
        Sensor.TYPE_MAGNETIC_FIELD to "magnetometer", Sensor.TYPE_ROTATION_VECTOR to "rotation_vector")
    private data class SensorStats(var count: Long = 0, var first: Long = 0, var last: Long = 0, var maxGap: Long = 0, var nonMonotonic: Int = 0)

    suspend fun refresh() = withContext(Dispatchers.IO) {
        // A recording manifest from another process cannot still have an active writer here.
        synchronized(guard) {
            for (dir in base.listFiles().orEmpty().filter { it.isDirectory }) {
                val file = File(dir, "manifest.json")
                if (!file.exists()) continue
                runCatching {
                    val value = SessionFiles.json(file)
                    if (value.optString("status") == "recording" && dir != root) {
                        value.put("status", "interrupted").put("recovery_note", "Previous process ended; last buffered samples may be missing")
                        SessionFiles.write(file, value)
                    }
                    if (value.optBoolean("export_blocked", false) && dir != root) {
                        value.put("export_blocked", false).put("status", "interrupted").put("recovery_note", "Previous writer stopped with process; audio metadata may be incomplete")
                        SessionFiles.write(file, value)
                    }
                }
            }
            updateSessions()
        }
    }

    private fun updateSessions() {
        val sessions = base.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { dir ->
            runCatching { val m = SessionFiles.json(File(dir, "manifest.json")); SessionSummary(dir.name, m.optString("site_name"), m.optString("status")) }.getOrNull()
        }.sortedByDescending { File(base, it.id).lastModified() }
        mutable.value = mutable.value.copy(sessions = sessions)
    }

    fun setCameraInfo(info: JSONObject) = synchronized(guard) {
        cameraInfo = info
        root?.let { if (mutable.value.recording) SessionFiles.write(File(it, "camera.json"), info) }
    }
    fun message(text: String) { mutable.value = mutable.value.copy(message = text) }

    suspend fun start(site: String, enableAudio: Boolean) = withContext(Dispatchers.IO) {
        synchronized(guard) {
            check(!mutable.value.recording) { "Session đang thu" }
            require(site.isNotBlank()) { "Nhập tên địa điểm" }
            check(base.usableSpace > 100L * 1024 * 1024) { "Cần ít nhất 100 MB trống để bắt đầu" }
            val dir = File(base, UUID.randomUUID().toString()); check(dir.mkdirs())
            root = dir; photos = JSONArray(); landmarks = JSONArray(); closures = JSONArray(); audioTracks = JSONArray(); stats.clear()
            val capabilities = JSONArray()
            sensorTypes.forEach { (type, name) ->
                val sensor = manager.getDefaultSensor(type)
                capabilities.put(JSONObject().put("sensor", name).put("available", sensor != null)
                    .put("name", sensor?.name ?: JSONObject.NULL).put("vendor", sensor?.vendor ?: JSONObject.NULL)
                    .put("min_delay_us", sensor?.minDelay ?: JSONObject.NULL).put("max_range", sensor?.maximumRange ?: JSONObject.NULL))
            }
            manifest = JSONObject().put("schema_version", "1.1").put("session_id", dir.name).put("site_name", site.trim())
                .put("created_at", Instant.now().toString()).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("android_sdk", Build.VERSION.SDK_INT).put("synthetic", false).put("clock", "elapsedRealtimeNanos")
                .put("coordinate_frame", "android_device").put("requested_imu_hz", 200).put("status", "recording")
                .put("capabilities", capabilities).put("sensors", JSONArray(sensorTypes.filterKeys { manager.getDefaultSensor(it) != null }.values.toList()))
                .put("audio_requested", enableAudio).put("depth", JSONObject().put("status", "not_implemented"))
                .put("recording_events", JSONArray()).put("warnings", JSONArray().put("POC: no pose estimation, calibration or reconstruction has run")
                    .put("Foreground only: leaving the app stops the session as interrupted"))
            writer = File(dir, "imu.jsonl").bufferedWriter()
            SessionFiles.write(File(dir, "camera.json"), cameraInfo)
            mutable.value = RecorderState(sessionId = dir.name, recording = true, sessions = mutable.value.sessions, message = "Đang thu; giữ app ở foreground")
            event("start"); persist()
            sensorTypes.forEach { (type, name) ->
                val sensor = manager.getDefaultSensor(type)
                if (sensor == null || !manager.registerListener(this@AndroidCaptureRepository, sensor, 5000, 0, handler)) {
                    manifest.getJSONArray("warnings").put("Sensor unavailable or registration failed: $name")
                }
            }
            if (enableAudio) {
                try { audio = AudioCapture(dir).also { it.start() }; mutable.value = mutable.value.copy(audioRecording = true) }
                catch (e: Exception) { audio = null; manifest.getJSONArray("warnings").put("Audio unavailable: ${e.message}"); message("IMU đang thu; audio không khởi tạo được: ${e.message}") }
            }
            persist(); updateSessions()
        }
    }

    private fun event(type: String) { manifest.getJSONArray("recording_events").put(JSONObject().put("type", type).put("t_ns", SystemClock.elapsedRealtimeNanos())) }
    private fun persist() {
        val dir = root ?: return
        SessionFiles.write(File(dir, "landmarks.json"), landmarks)
        SessionFiles.write(File(dir, "photos.json"), photos)
        SessionFiles.write(File(dir, "loop_closures.json"), closures)
        SessionFiles.write(File(dir, "audio.json"), audioTracks)
        SessionFiles.write(File(dir, "manifest.json"), manifest)
    }

    override fun onSensorChanged(e: SensorEvent): Unit = synchronized(guard) {
        if (!mutable.value.recording) return
        val name = sensorTypes[e.sensor.type] ?: return
        val record = stats.getOrPut(name) { SensorStats(first = e.timestamp) }
        if (record.count > 0 && e.timestamp <= record.last) { record.nonMonotonic++; return }
        try {
            val values = JSONArray(); e.values.forEach { values.put(it.toDouble()) }
            writer?.apply { write(JSONObject().put("sensor", name).put("t_ns", e.timestamp).put("values", values).put("accuracy", e.accuracy).toString()); newLine() }
            if (record.count > 0) record.maxGap = maxOf(record.maxGap, e.timestamp - record.last)
            record.last = e.timestamp; record.count++
            val now = SystemClock.elapsedRealtime()
            if (now - lastFlush >= 1000) {
                writer?.flush(); lastFlush = now
                mutable.value = mutable.value.copy(sampleCount = stats.values.sumOf { it.count })
                if (base.usableSpace < 20L * 1024 * 1024) handler.post { emergencyStop("Dung lượng còn dưới 20 MB") }
            }
        } catch (error: Exception) { handler.post { emergencyStop("Không ghi được IMU: ${error.message}") } }
        Unit
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) { }

    private fun emergencyStop(reason: String) {
        // Separate from the callback so unregister/close can complete normally.
        runCatching { finishInternal(true, reason) }.onFailure { message("Lỗi dừng: ${it.message}") }
    }

    suspend fun landmark(label: String, revisit: Boolean) = withContext(Dispatchers.IO) {
        synchronized(guard) {
            check(mutable.value.recording); require(label.isNotBlank()) { "Nhập tên mốc" }
            val t = SystemClock.elapsedRealtimeNanos()
            if (revisit) {
                val found = (0 until landmarks.length()).map { landmarks.getJSONObject(it) }.lastOrNull { it.getString("label") == label.trim() }
                    ?: error("Chưa có mốc trùng tên; thêm mốc trước")
                closures.put(JSONObject().put("landmark_id", found.getString("id")).put("t_ns", t).put("source", "manual"))
            } else {
                landmarks.put(JSONObject().put("id", UUID.randomUUID().toString()).put("label", label.trim()).put("t_ns", t)
                    .put("related_photo_id", if (photos.length() > 0) photos.getJSONObject(photos.length()-1).getString("id") else JSONObject.NULL)
                    .put("association", "last_photo_reference_not_spatial_confirmation"))
            }
            persist(); mutable.value = mutable.value.copy(landmarkCount = landmarks.length(), message = if (revisit) "Đã ghi quay lại mốc" else "Đã lưu mốc")
        }
    }

    fun beginPhoto(): Pair<String, Long> = synchronized(guard) {
        check(mutable.value.recording && !mutable.value.photoPending)
        mutable.value = mutable.value.copy(photoPending = true)
        (root ?: error("No session")).name to SystemClock.elapsedRealtimeNanos()
    }
    fun photoFailed(reason: String) { mutable.value = mutable.value.copy(photoPending = false, message = "Chụp ảnh lỗi: $reason") }
    fun savePhoto(sessionId: String, requested: Long, image: ImageProxy) = synchronized(guard) {
        try {
            check(mutable.value.recording && root?.name == sessionId) { "Session đã dừng; ảnh chưa được thêm" }
            check(image.format == android.graphics.ImageFormat.JPEG) { "Camera không trả JPEG: ${image.format}" }
            val dir = root!!; val id = UUID.randomUUID().toString(); val path = "images/$id.jpg"
            File(dir, "images").mkdirs()
            val buffer = image.planes[0].buffer
            File(dir, path).outputStream().buffered().use { out ->
                val bytes = ByteArray(minOf(buffer.remaining(), 65536))
                while (buffer.hasRemaining()) { val size = minOf(buffer.remaining(), bytes.size); buffer.get(bytes, 0, size); out.write(bytes, 0, size) }
            }
            val realtime = cameraInfo.optBoolean("timestamp_realtime", false)
            photos.put(JSONObject().put("id", id).put("path", path).put("request_t_ns", requested)
                .put("returned_t_ns", SystemClock.elapsedRealtimeNanos()).put("camera_t_ns", image.imageInfo.timestamp)
                .put("camera_clock_id", if (realtime) "elapsedRealtimeNanos" else "camera:${cameraInfo.optString("camera_id", "unknown")}")
                .put("timestamp_quality", if (realtime) "camera_image_timestamp_realtime" else "camera_clock_not_aligned")
                .put("quality", "original_jpeg").put("width", image.width).put("height", image.height)
                .put("rotation_degrees", image.imageInfo.rotationDegrees).put("crop_rect", image.cropRect.flattenToString())
                .put("camera_metadata_path", "camera.json"))
            persist(); mutable.value = mutable.value.copy(photoCount = photos.length(), message = "Đã lưu ảnh gốc")
        } catch (e: Exception) { message("Không lưu được ảnh: ${e.message}") }
        finally { image.close(); mutable.value = mutable.value.copy(photoPending = false) }
    }

    suspend fun finish(interrupted: Boolean = false) = withContext(Dispatchers.IO) { finishInternal(interrupted, if (interrupted) "App left foreground" else "User stopped") }
    private fun finishInternal(interrupted: Boolean, reason: String) {
        synchronized(guard) {
            if (!mutable.value.recording) return
            manager.unregisterListener(this)
            mutable.value = mutable.value.copy(recording = false, audioRecording = false)
            var failed = interrupted
            try { audio?.let { audioTracks.put(it.stop()); if (it.failure != null) failed = true } }
            catch (e: Exception) { failed = true; manifest.put("export_blocked", true); manifest.getJSONArray("warnings").put("Audio stop failed: ${e.message}") }
            audio = null
            try { writer?.flush(); writer?.close() } catch (e: Exception) { failed = true; manifest.getJSONArray("warnings").put("IMU close failed: ${e.message}") }
            writer = null; event(if (failed) "interrupted" else "stop")
            val summary = JSONObject()
            stats.forEach { (name, v) -> summary.put(name, JSONObject().put("count", v.count).put("first_t_ns", v.first).put("last_t_ns", v.last)
                .put("max_gap_ns", v.maxGap).put("nonmonotonic_discarded", v.nonMonotonic)
                .put("mean_hz", if (v.last > v.first) (v.count - 1) * 1e9 / (v.last - v.first) else JSONObject.NULL)) }
            manifest.put("sensor_summary", summary).put("status", if (failed) "interrupted" else "complete").put("stop_reason", reason)
                .put("finished_at", Instant.now().toString())
            persist(); mutable.value = mutable.value.copy(sampleCount = stats.values.sumOf { it.count }, message = if (failed) "Đã dừng; session có cảnh báo, vẫn có thể xuất" else "Đã lưu session")
            updateSessions()
        }
    }

    suspend fun export(id: String): File = withContext(Dispatchers.IO) {
        synchronized(guard) {
            check(!mutable.value.recording) { "Dừng session trước khi xuất" }
            require(id.matches(Regex("[a-fA-F0-9-]{36}"))) { "Invalid session ID" }
            val dir = File(base, id); check(dir.isDirectory)
            File(app.cacheDir, "$id.zip").also { SessionFiles.export(dir, it) }
        }
    }

    fun close() { runCatching { finishInternal(true, "Recorder closed") }; thread.quitSafely() }
}
