package dev.indoormap

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.indoormap.capture.SurveyViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var currentModel: SurveyViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val model: SurveyViewModel = viewModel(); currentModel = model
            SurveyScreen(model)
        }
    }
    override fun onStop() {
        if (::currentModel.isInitialized && !isChangingConfigurations) currentModel.leaveForeground()
        super.onStop()
    }
}

@Composable
private fun SurveyScreen(model: SurveyViewModel) {
    val context = LocalContext.current
    val state by model.state.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val site by model.siteName.collectAsStateWithLifecycle()
    val mark by model.landmarkName.collectAsStateWithLifecycle()
    var audioEnabled by rememberSaveable { mutableStateOf(false) }
    var cameraGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    DisposableEffect(state.recording) {
        val window = (context as? Activity)?.window
        if (state.recording) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        cameraGranted = it; if (!it) model.message("Không có quyền camera; vẫn có thể thu IMU")
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        audioEnabled = it; if (!it) model.message("Không có quyền microphone; audio tắt")
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { model.writeExport(it) }
    MaterialTheme {
        Scaffold { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Field Twin · Thu khảo sát", style = MaterialTheme.typography.headlineSmall)
                Text("Lưu dữ liệu gốc trên máy. Không upload, không dựng mô hình trong app.")
                OutlinedTextField(site, { model.siteName.value = it }, label = { Text("Tên địa điểm") }, enabled = !state.recording && !busy, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Ghi âm tùy chọn", modifier = Modifier.weight(1f).padding(top = 12.dp))
                    Switch(checked = audioEnabled, enabled = !state.recording && !busy, onCheckedChange = { enabled ->
                        if (!enabled) audioEnabled = false
                        else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) audioEnabled = true
                        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    })
                }
                Text("Chỉ bật ghi âm khi phù hợp tại nơi khảo sát. App dừng thu khi rời foreground.", style = MaterialTheme.typography.bodySmall)
                if (cameraGranted) CameraPanel(model, { capture = it }, Modifier.fillMaxWidth().height(220.dp))
                else OutlinedButton(onClick = { cameraPermission.launch(Manifest.permission.CAMERA) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Cho phép camera để chụp ảnh") }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(state.message)
                        Text("IMU: ${state.sampleCount} · Ảnh: ${state.photoCount} · Mốc: ${state.landmarkCount}")
                        Text(if (state.audioRecording) "Microphone đang ghi" else "Microphone không ghi")
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
                if (!state.recording) Button(onClick = { model.start(audioEnabled) }, enabled = !busy && site.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Bắt đầu phiên thu") }
                else {
                    Button(onClick = {
                        val camera = capture ?: return@Button
                        runCatching {
                            val (session, requested) = model.beginPhoto()
                            camera.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) { model.savePhoto(session, requested, image) }
                                override fun onError(exception: ImageCaptureException) { model.photoFailed(exception.message ?: "Camera lỗi") }
                            })
                        }.onFailure { model.photoFailed(it.message ?: "Camera lỗi") }
                    }, enabled = capture != null && !state.photoPending && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.photoPending) "Đang lưu ảnh…" else "Chụp ảnh gốc") }
                    OutlinedTextField(mark, { model.landmarkName.value = it }, label = { Text("Tên mốc / ghi chú") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { model.landmark(false) }, enabled = mark.isNotBlank() && !busy, modifier = Modifier.weight(1f)) { Text("Thêm mốc") }
                        OutlinedButton(onClick = { model.landmark(true) }, enabled = mark.isNotBlank() && !busy, modifier = Modifier.weight(1f)) { Text("Quay lại mốc") }
                    }
                    Button(onClick = { model.finish() }, enabled = !busy && !state.photoPending, modifier = Modifier.fillMaxWidth()) { Text("Dừng và lưu") }
                }
                Text("Phiên đã lưu", style = MaterialTheme.typography.titleMedium)
                Text("Depth/LiDAR chưa triển khai. Tần số IMU thực và cảnh báo nằm trong gói xuất.", style = MaterialTheme.typography.bodySmall)
                state.sessions.take(10).forEach { session ->
                    OutlinedButton(onClick = { model.prepareExport(session.id) { exporter.launch(it) } },
                        enabled = !state.recording && !busy, modifier = Modifier.fillMaxWidth()) {
                        Text("Xuất ${session.site} · ${session.status}\n${session.id.take(8)}")
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
@Composable
private fun CameraPanel(model: SurveyViewModel, onCapture: (ImageCapture?) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    DisposableEffect(owner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build()
        val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed) try {
                provider = future.get()
                val selector = if (provider!!.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val camera = provider!!.bindToLifecycle(owner, selector, preview, imageCapture)
                val info = Camera2CameraInfo.from(camera.cameraInfo)
                val source = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                val meta = JSONObject().put("available", true).put("camera_id", info.cameraId)
                    .put("timestamp_realtime", source == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
                    .put("timestamp_source", source ?: JSONObject.NULL)
                    .put("intrinsics_status", "hardware_metadata_only_not_calibrated_for_output")
                    .put("camera_to_device_extrinsics", JSONObject.NULL)
                info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.let { values -> meta.put("hardware_intrinsics", JSONArray(values.map { it.toDouble() })) }
                info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let { meta.put("active_array_rect", it.flattenToString()) }
                model.cameraInfo(meta); onCapture(imageCapture)
            } catch (error: Exception) { model.message("Camera không khả dụng: ${error.message}"); onCapture(null) }
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true; onCapture(null); provider?.unbind(preview, imageCapture) }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
