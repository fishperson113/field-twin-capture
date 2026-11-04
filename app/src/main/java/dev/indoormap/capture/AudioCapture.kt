package dev.indoormap.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** PCM + timestamp anchors; no codec encoder delay or claim of exact sample alignment. */
class AudioCapture(private val root: File) {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile var failure: String? = null
        private set
    var startNs: Long = 0; private set
    var endNs: Long = 0; private set
    @Volatile var bytesWritten: Long = 0; private set

    @SuppressLint("MissingPermission")
    fun start() {
        val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(minimum > 0) { "Microphone không hỗ trợ PCM 16 kHz" }
        val bufferSize = maxOf(minimum * 2, 16384)
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); error("Không khởi tạo được microphone") }
        recorder = audio
        try { audio.startRecording() } catch (e: Exception) { audio.release(); recorder = null; throw e }
        startNs = SystemClock.elapsedRealtimeNanos(); running.set(true)
        worker = Thread({
            try {
                File(root, "audio").mkdirs()
                File(root, "audio/track.pcm").outputStream().buffered().use { output ->
                    File(root, "audio/anchors.jsonl").bufferedWriter().use { anchors ->
                        val buffer = ByteArray(bufferSize)
                        while (running.get()) {
                            val before = SystemClock.elapsedRealtimeNanos()
                            val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                            val after = SystemClock.elapsedRealtimeNanos()
                            if (count < 0) { if (running.get()) error("AudioRecord read error: $count"); break }
                            if (count == 0) continue
                            output.write(buffer, 0, count); bytesWritten += count
                            val stamp = AudioTimestamp()
                            val result = audio.getTimestamp(stamp, AudioTimestamp.TIMEBASE_BOOTTIME)
                            val row = JSONObject().put("read_start_ns", before).put("read_end_ns", after)
                                .put("written_pcm_frames", bytesWritten / 2).put("timestamp_available", result == AudioRecord.SUCCESS)
                            if (result == AudioRecord.SUCCESS) row.put("frame_position", stamp.framePosition).put("boot_t_ns", stamp.nanoTime)
                            anchors.write(row.toString()); anchors.newLine(); anchors.flush(); output.flush()
                        }
                    }
                }
            } catch (e: Exception) { failure = e.message ?: e.javaClass.simpleName }
        }, "field-audio").also { it.start() }
    }

    fun stop(): JSONObject {
        running.set(false)
        try { recorder?.stop() } catch (_: Exception) { }
        worker?.join(3000)
        val stillRunning = worker?.isAlive == true
        // Do not export a file while a writer may still own it.
        check(!stillRunning) { "Audio chưa dừng; thử lưu lại sau" }
        recorder?.release(); recorder = null; worker = null
        endNs = SystemClock.elapsedRealtimeNanos()
        return JSONObject().put("id", "audio-track").put("path", "audio/track.pcm")
            .put("anchors_path", "audio/anchors.jsonl").put("encoding", "pcm_s16le").put("sample_rate_hz", 16000)
            .put("channels", 1).put("start_t_ns", startNs).put("end_t_ns", endNs).put("clock_id", "elapsedRealtimeNanos")
            .put("bytes", bytesWritten).put("timestamp_quality", "audio_timestamp_anchors_or_read_intervals")
            .put("error", failure ?: JSONObject.NULL)
    }
}
