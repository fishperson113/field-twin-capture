package dev.indoormap.capture

data class SessionSummary(val id: String, val site: String, val status: String)
data class RecorderState(
    val sessionId: String? = null,
    val recording: Boolean = false,
    val photoPending: Boolean = false,
    val sampleCount: Long = 0,
    val photoCount: Int = 0,
    val landmarkCount: Int = 0,
    val audioRecording: Boolean = false,
    val message: String = "Sẵn sàng. Dữ liệu chỉ lưu trên máy.",
    val sessions: List<SessionSummary> = emptyList(),
)
