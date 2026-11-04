package dev.indoormap.capture

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private fun session(status: String): File {
        val root = temp.newFolder()
        SessionFiles.write(File(root, "manifest.json"), JSONObject().put("session_id", "synthetic-test").put("status", status).put("warnings", JSONArray()))
        File(root, "imu.jsonl").writeText("{\"sensor\":\"accelerometer\",\"t_ns\":1,\"values\":[0,0,9.8]}\n")
        File(root, "photos.json").writeText("[]"); File(root, "audio.json").writeText("[]")
        return root
    }
    @Test fun exportsRawBytesWithMatchingHashesAndGraphReferences() {
        val root = session("complete"); val zip = File(temp.root, "export.zip")
        SessionFiles.export(root, zip)
        ZipFile(zip).use { archive ->
            val index = JSONObject(archive.getInputStream(archive.getEntry("index.json")).bufferedReader().readText())
            val assets = index.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                assertNotNull(archive.getEntry(asset.getString("path")))
                assertEquals(SessionFiles.hash(File(root, asset.getString("path"))), asset.getString("sha256"))
            }
            val graph = JSONObject(archive.getInputStream(archive.getEntry("evidence-graph.json")).bufferedReader().readText())
            assertEquals(assets.length() + 1, graph.getJSONArray("nodes").length())
            assertEquals(assets.length(), graph.getJSONArray("edges").length())
        }
    }
    @Test(expected = IllegalStateException::class) fun cannotExportActiveSession() {
        SessionFiles.export(session("recording"), File(temp.root, "invalid.zip"))
    }
    @Test fun atomicMetadataCanBeReadAfterReplacement() {
        val file = File(temp.root, "metadata.json")
        SessionFiles.write(file, JSONObject().put("value", 1))
        SessionFiles.write(file, JSONObject().put("value", 2))
        assertEquals(2, SessionFiles.json(file).getInt("value"))
    }
    @Test(expected = IllegalStateException::class) fun missingPhotoCannotBeSilentlyExported() {
        val root = session("complete")
        File(root, "photos.json").writeText("[{\"path\":\"images/missing.jpg\",\"request_t_ns\":1,\"returned_t_ns\":2}]")
        SessionFiles.export(root, File(temp.root, "missing.zip"))
    }
    @Test(expected = IllegalArgumentException::class) fun cannotExportPhotoOutsideSession() {
        val root = session("complete")
        File(root, "photos.json").writeText("[{\"path\":\"../outside.jpg\",\"request_t_ns\":1,\"returned_t_ns\":2}]")
        SessionFiles.export(root, File(temp.root, "traversal.zip"))
    }
}
