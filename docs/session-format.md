# Session v1

Legacy v1.0 fixture is described below. Current phone POC exports v1.1, documented in `poc-capture.md`: original CameraX JPEG + clock metadata, optional PCM audio + BOOTTIME anchors, persisted session status and export inventory/graph. Validator supports both versions. The thumbnail camera intent below refers only to the archived Java draft, not the current Kotlin recorder.

A ZIP contains these files at its root: `manifest.json`, `imu.jsonl`, `landmarks.json`, `photos.json`, `loop_closures.json`, and `images/`. No uploads occur in the starter app.

`manifest.json`: schema_version `1.0`, session_id, ISO created_at (wall clock for display), device, synthetic, coordinate_frame `android_device`, clock `elapsedRealtimeNanos`, requested_imu_hz, sensors, recording_events. Android sensor timestamps and elapsedRealtimeNanos share a monotonic boot-time domain. Never mix them with Unix timestamps. Requested frequency is not guaranteed; derive actual rate per sensor from recorded timestamps.

Each `imu.jsonl` line contains sensor, integer t_ns, values, accuracy. Accelerometer is x/y/z in m/s² including gravity, gyroscope rad/s, magnetometer µT. Android device axes: x right, y towards top, z out of screen. Rotation-vector values follow Android SensorEvent layout (x,y,z, optional scalar w and heading accuracy), not an assumed wxyz quaternion. Streams are asynchronous; timestamps must increase per sensor. Resampling/calibration belongs in the odometry adapter.

Landmarks: id, label, t_ns. Loop closures: landmark_id referencing an existing landmark, t_ns, source `manual`. These are observations, not solved coordinates.

Photos: relative path, request_t_ns, returned_t_ns, timestamp_quality `capture_interval_only`, quality `thumbnail`. Current camera intent returns a low-resolution reference; there is no calibrated intrinsics, exact exposure timestamp or camera pose. CameraX capture is required before meaningful multi-view reconstruction.

Future derived products remain separate from raw data: trajectory with t_ns, position_m, camera-to-local quaternion_xyzw, covariance and model provenance; reconstruction point cloud; optimized pose graph; walls.json. Local frame is right-handed, Z up, meters, session origin at first calibrated pose. Any cross-session transform must be explicit.

`walls.json` contains frame `local_z_up_meters`, source `manual|reconstruction|synthetic`, walls array: start/end each three finite coordinates in meters and positive height_m. Export produces vertical zero-thickness OBJ wall quads; it does not create floor/ceiling or a watertight mesh.
