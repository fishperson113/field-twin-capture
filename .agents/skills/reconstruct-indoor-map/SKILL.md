---
name: reconstruct-indoor-map
description: Validate IndoorMap Android capture sessions and turn measured wall geometry or calibrated reconstruction output into a 3D map. Use for session-to-map work in this repository.
---

Read `docs/session-format.md`, `docs/multimodal-representation.md`, `docs/agent-data-contract.md` and `docs/architecture.md` before processing a capture. This independent Android survey app supports an agent digital-twin loop through linked image/IMU/audio/depth and other sensor evidence. Depth/LiDAR are optional capabilities; keep capture light for ordinary phones and heavy processing external. There is no automatic 2D fallback rule.

Use `uv run python pipeline/map_session.py prepare-agent <session-directory> <new-output-directory>` to build an evidence package. Read index.json first, verify the listed SHA-256 hashes, and load only the needed metadata, images and bounded sensor intervals. The packager does not infer geometry or establish camera calibration. Never feed an entire IMU log into an LLM prompt.

Run `python pipeline/map_session.py validate <session-directory>`. Report missing sensors, timestamp gaps, thumbnail images and synthetic data before choosing reconstruction inputs. ZIP files should be inspected for traversal paths before extraction.

Raw Android IMU is device-frame data with gravity. It is not a camera trajectory. Choose an appropriate verified OSS backend with calibration and frame transforms; RoNIN is only a local reference, not required. Save provenance, timestamp domain and uncertainty. No backend or weights are bundled yet; if unavailable, report that blocker and preserve the data. Keep original assets, temporal/frame links, derived artifacts and twin revisions separate; every enhancement must retain input IDs and tool/model provenance.

For MapAnything, use full-resolution images with calibrated intrinsics and synchronized camera poses only when available. Request/callback times from the starter camera intent are an interval, not exposure timestamps. Do not pass Android rotation-vector samples as camera-to-world poses without extrinsic calibration. Preserve metric-scale uncertainty; evaluate the documented 47.4 m corridor only against an independently measured distance.

Export explicit wall segments as `walls.json` with frame `local_z_up_meters`, a truthful source (`manual`, `reconstruction`, or `synthetic`), and start/end/height_m. Run `python pipeline/map_session.py export-walls <walls.json> <output.obj>`. Never turn a walking path into claimed measured walls. Include source, unresolved scale/alignment errors and validation output with the deliverable. Choose further processing, calibration, another modality, additional capture or 2D/3D output according to the task. Preserve contradictory hypotheses rather than inventing certainty. GNIO/SSIO, magnetic loop closure, pose-graph optimization and automatic wall extraction are future adapters, not implemented features.
