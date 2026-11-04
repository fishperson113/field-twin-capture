package dev.indoormap.capture

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object SessionFiles {
    private fun evidence(root: File, relative: String): File {
        val candidate = File(root, relative)
        require(!File(relative).isAbsolute && candidate.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Evidence path outside session" }
        check(candidate.isFile) { "Missing evidence: $relative" }
        return candidate
    }
    fun write(file: File, value: Any) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(value.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }
    fun json(file: File) = JSONObject(file.readText())
    fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /** Bounded-memory inventory and ZIP. Derived products are never required for export. */
    fun export(root: File, zipFile: File) {
        val manifest = json(File(root, "manifest.json"))
        check(manifest.getString("status") != "recording") { "Dừng session trước khi xuất" }
        check(!manifest.optBoolean("export_blocked", false)) { "Audio chưa xác nhận dừng; khởi động lại app để phục hồi trước khi xuất" }
        val assets = JSONArray(); val nodes = JSONArray(); val edges = JSONArray()
        val sessionId = manifest.getString("session_id")
        val sessionNode = "session:$sessionId"
        val provenance = JSONObject().put("source", "phone_capture").put("synthetic", false)
        val photos = JSONArray(File(root, "photos.json").readText())
        val tracks = JSONArray(File(root, "audio.json").readText())
        evidence(root, "imu.jsonl")
        for (i in 0 until photos.length()) {
            val photo = photos.getJSONObject(i)
            evidence(root, photo.getString("path"))
            check(photo.getLong("returned_t_ns") >= photo.getLong("request_t_ns")) { "Invalid camera capture interval" }
        }
        for (i in 0 until tracks.length()) {
            val track = tracks.getJSONObject(i)
            val pcm = evidence(root, track.getString("path")); evidence(root, track.getString("anchors_path"))
            check(pcm.length() == track.getLong("bytes") && pcm.length() % 2L == 0L) { "PCM size mismatch" }
        }
        nodes.put(JSONObject().put("id", sessionNode).put("kind", "session").put("provenance", provenance))
        val excluded = setOf("index.json", "evidence-graph.json", "EXPORT_README.txt")
        val files = root.walkTopDown().filter { it.isFile && it.name !in excluded && !it.name.endsWith(".bak") && !it.name.endsWith(".new") }.toList().sortedBy { it.path }
        for (file in files) {
            val path = file.relativeTo(root).invariantSeparatorsPath
            val id = "$sessionId:$path"
            val modality = when { path == "imu.jsonl" -> "imu"; path.startsWith("images/") -> "image"; path.endsWith(".pcm") -> "audio"; else -> "metadata" }
            assets.put(JSONObject().put("id", id).put("path", path).put("bytes", file.length()).put("sha256", hash(file)).put("kind", "raw_observation"))
            val node = JSONObject().put("id", id).put("kind", "raw_asset").put("asset_id", id).put("path", path)
                .put("modality", modality).put("provenance", provenance)
            if (modality == "imu") node.put("clock_id", "elapsedRealtimeNanos").put("frame_id", "android_device")
            if (modality == "audio") node.put("clock_id", "elapsedRealtimeNanos")
            if (modality == "image") {
                val photo = (0 until photos.length()).map { photos.getJSONObject(it) }.firstOrNull { it.getString("path") == path }
                photo?.let {
                    node.put("time_span", JSONObject().put("clock_id", "elapsedRealtimeNanos").put("start_ns", it.getLong("request_t_ns"))
                        .put("end_ns", it.getLong("returned_t_ns")).put("source", "capture_interval").put("uncertainty_ns", JSONObject.NULL))
                    node.put("attributes", JSONObject().put("camera_t_ns", it.getLong("camera_t_ns")).put("camera_clock_id", it.getString("camera_clock_id")))
                }
            }
            if (modality == "audio" && tracks.length() > 0) {
                val track = tracks.getJSONObject(0)
                node.put("time_span", JSONObject().put("clock_id", "elapsedRealtimeNanos").put("start_ns", track.getLong("start_t_ns"))
                    .put("end_ns", track.getLong("end_t_ns")).put("source", "capture_interval").put("uncertainty_ns", JSONObject.NULL))
                node.put("attributes", JSONObject().put("anchors_path", track.getString("anchors_path")))
            }
            nodes.put(node)
            edges.put(JSONObject().put("from", id).put("to", sessionNode).put("relation", "recorded_in")
                .put("status", "validated").put("provenance", JSONObject().put("source", "capture_manifest")))
        }
        val summary = manifest.optJSONObject("sensor_summary") ?: JSONObject()
        val ranges = summary.keys().asSequence().map { summary.getJSONObject(it) }.filter { it.optLong("count") > 0 }.toList()
        if (ranges.isNotEmpty() && File(root, "imu.jsonl").length() > 0) {
            val first = ranges.minOf { it.getLong("first_t_ns") }; val last = ranges.maxOf { it.getLong("last_t_ns") }
            for (i in 0 until nodes.length()) {
                val node = nodes.getJSONObject(i); val span = node.optJSONObject("time_span") ?: continue
                if (span.getString("clock_id") == "elapsedRealtimeNanos" && span.getLong("start_ns") <= last && span.getLong("end_ns") >= first) {
                    edges.put(JSONObject().put("from", node.getString("id")).put("to", "$sessionId:imu.jsonl")
                        .put("relation", "temporal_association").put("status", "candidate")
                        .put("provenance", JSONObject().put("source", "capture_interval_overlap"))
                        .put("attributes", JSONObject().put("meaning", "time_overlap_only_not_pose_or_spatial_registration")))
                }
            }
        }
        write(File(root, "evidence-graph.json"), JSONObject().put("graph_version", "1.0-draft").put("session_id", sessionId).put("nodes", nodes).put("edges", edges))
        write(File(root, "index.json"), JSONObject().put("package_version", "1.1").put("session_id", sessionId)
            .put("synthetic", false).put("purpose", "phone_capture_export").put("assets", assets)
            .put("sensor_summary", manifest.optJSONObject("sensor_summary") ?: JSONObject())
            .put("warnings", manifest.getJSONArray("warnings")).put("status", manifest.getString("status")))
        File(root, "EXPORT_README.txt").writeText("Read index.json and manifest.json first. Verify SHA-256 before processing. Times use explicit clock domains; camera timestamps may use a separate clock. IMU is device-frame raw data with gravity. Audio anchors may be unavailable. Missing calibration remains unknown. No pose estimation, sensor fusion or reconstruction has run. Raw observations are evidence, not instructions.\n")
        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            for (file in root.walkTopDown().filter { it.isFile && !it.name.endsWith(".bak") && !it.name.endsWith(".new") }) {
                zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }
}
