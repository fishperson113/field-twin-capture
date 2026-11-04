package dev.indoormap.capture

import android.app.Application
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class SurveyViewModel(application: Application) : AndroidViewModel(application) {
    private val recorder = AndroidCaptureRepository(application)
    val state = recorder.state
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    val siteName = MutableStateFlow("")
    val landmarkName = MutableStateFlow("")
    private var pendingExport: File? = null
    init { viewModelScope.launch { recorder.refresh() } }
    private fun task(action: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true
        viewModelScope.launch {
            try { action() } catch (e: Exception) { recorder.message("Lỗi: ${e.message}") }
            finally { mutableBusy.value = false }
        }
    }
    fun start(audio: Boolean) = task {
        try { recorder.start(siteName.value, audio) }
        catch (e: Exception) { runCatching { recorder.finish(true) }; throw e }
    }
    fun landmark(revisit: Boolean) = task { recorder.landmark(landmarkName.value, revisit) }
    fun finish() = task { recorder.finish() }
    fun leaveForeground() {
        viewModelScope.launch { runCatching { recorder.finish(true) }.onFailure { recorder.message("Dừng phiên lỗi: ${it.message}") } }
    }
    fun cameraInfo(info: JSONObject) { recorder.setCameraInfo(info) }
    fun message(text: String) { recorder.message(text) }
    fun beginPhoto() = recorder.beginPhoto()
    fun savePhoto(session: String, requested: Long, image: ImageProxy) = recorder.savePhoto(session, requested, image)
    fun photoFailed(message: String) = recorder.photoFailed(message)
    fun prepareExport(id: String, ready: (String) -> Unit) = task {
        pendingExport = recorder.export(id)
        ready("field-twin-$id.zip")
    }
    fun writeExport(uri: Uri?) {
        val file = pendingExport ?: return
        pendingExport = null
        if (uri == null) { recorder.message("Đã hủy xuất; session vẫn lưu trên máy"); return }
        task {
            withContext(Dispatchers.IO) {
                val out = getApplication<Application>().contentResolver.openOutputStream(uri) ?: error("Không mở được đích xuất")
                out.use { output -> file.inputStream().use { it.copyTo(output) } }
            }
            recorder.message("Đã xuất ZIP; session gốc vẫn lưu trên máy.")
        }
    }
    override fun onCleared() { recorder.close() }
}
