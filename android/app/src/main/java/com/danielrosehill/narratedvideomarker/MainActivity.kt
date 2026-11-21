package com.danielrosehill.narratedvideomarker

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Records 720p video and lets the user drop "marks" while recording: tap MARK or press a
 * volume key. Each video NV_<stamp>.mp4 gets a sidecar NV_<stamp>.marks.json in the same
 * user-chosen folder (format spec: docs/marks-format.md). The sidecar is rewritten on every
 * mark, so a crash mid-recording keeps the marks made so far.
 */
class MainActivity : ComponentActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var recordBtn: Button
    private lateinit var markBtn: Button
    private lateinit var statusText: TextView
    private lateinit var flash: View

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var pfd: ParcelFileDescriptor? = null

    private var baseName = ""
    private var videoDoc: DocumentFile? = null
    private var marksDoc: DocumentFile? = null
    private var recordedAt = ""
    private val marks = mutableListOf<JSONObject>()

    // Mark timing: last reported recorded duration plus the time since that report.
    private var lastDurNs = 0L
    private var lastStatusAt = 0L
    private var startAt = 0L

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            if (res.values.all { it }) startCamera()
            else statusText.text = "Camera and microphone permission are needed."
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs.edit().putString("tree", uri.toString()).apply()
                updateIdle()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startCamera()
        else askPermissions.launch(needed)

        // The activity is portrait-locked so the buttons stay put; the video itself follows
        // the phone, via rotation metadata set at the start of each recording.
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN || recording != null) return
                videoCapture?.targetRotation = when (o) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }.enable()
    }

    private fun buildUi() {
        val root = FrameLayout(this)
        previewView = PreviewView(this)
        root.addView(previewView, FrameLayout.LayoutParams(-1, -1))

        flash = View(this).apply { setBackgroundColor(Color.WHITE); alpha = 0f }
        root.addView(flash, FrameLayout.LayoutParams(-1, -1))

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; setShadowLayer(4f, 0f, 0f, Color.BLACK)
            setPadding(32, 96, 32, 16)
        }
        root.addView(statusText, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        val folderBtn = Button(this).apply { text = "Folder"; setOnClickListener { if (recording == null) pickFolder.launch(null) } }
        root.addView(folderBtn, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = 80; rightMargin = 24 })

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(24, 24, 24, 96) }
        recordBtn = Button(this).apply { text = "● REC"; textSize = 22f; setOnClickListener { toggleRecording() } }
        markBtn = Button(this).apply {
            text = "MARK"; textSize = 22f; isEnabled = false
            setOnClickListener { addMark() }
            setOnLongClickListener { undoMark(); true }
        }
        bar.addView(recordBtn, LinearLayout.LayoutParams(0, 260, 1f).apply { rightMargin = 16 })
        bar.addView(markBtn, LinearLayout.LayoutParams(0, 260, 1.4f))
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        setContentView(root)
        updateIdle()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            // 720p: enough for an agent to read a label or see a broken hinge, small enough to upload.
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.higherQualityOrLowerThan(Quality.HD)))
                .setTargetVideoEncodingBitRate(4_000_000)
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, videoCapture)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun tree(): DocumentFile? =
        prefs.getString("tree", null)?.let { DocumentFile.fromTreeUri(this, Uri.parse(it)) }?.takeIf { it.canWrite() }

    private fun updateIdle() {
        val t = tree()
        statusText.text = if (t == null) "Pick a folder to save videos and marks." else "Ready · saving to ${t.name}\nTap MARK or press a volume key while recording. Long-press MARK to undo."
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        recording?.let { it.stop(); recordBtn.isEnabled = false; return }
        val dir = tree() ?: run { pickFolder.launch(null); return }
        val vc = videoCapture ?: return

        val now = OffsetDateTime.now()
        recordedAt = now.toString()
        baseName = "NV_" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.US))
        marks.clear()
        videoDoc = dir.createFile("video/mp4", baseName) ?: return toast("Could not create the video file")
        marksDoc = dir.createFile("application/json", "$baseName.marks")
        pfd = contentResolver.openFileDescriptor(videoDoc!!.uri, "rw")
        val opts = FileDescriptorOutputOptions.Builder(pfd!!).build()

        lastDurNs = 0; lastStatusAt = 0; startAt = 0
        recording = vc.output.prepareRecording(this, opts).withAudioEnabled()
            .start(ContextCompat.getMainExecutor(this)) { ev -> onEvent(ev) }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        writeSidecar(null)
    }

    private fun onEvent(ev: VideoRecordEvent) {
        when (ev) {
            is VideoRecordEvent.Start -> {
                startAt = SystemClock.elapsedRealtimeNanos()
                recordBtn.text = "■ STOP"; markBtn.isEnabled = true
            }
            is VideoRecordEvent.Status -> {
                lastDurNs = ev.recordingStats.recordedDurationNanos
                lastStatusAt = SystemClock.elapsedRealtimeNanos()
                val s = lastDurNs / 1_000_000_000
                statusText.text = String.format(Locale.US, "REC %02d:%02d · %d mark%s", s / 60, s % 60, marks.size, if (marks.size == 1) "" else "s")
            }
            is VideoRecordEvent.Finalize -> {
                val dur = ev.recordingStats.recordedDurationNanos / 1e9
                pfd?.close(); pfd = null
                recording = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                recordBtn.text = "● REC"; recordBtn.isEnabled = true; markBtn.isEnabled = false
                if (ev.hasError() && ev.error == VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                    videoDoc?.delete(); marksDoc?.delete()
                    toast("Nothing recorded")
                } else {
                    writeSidecar(dur)
                    toast("Saved $baseName.mp4 + ${marks.size} marks")
                }
                updateIdle()
            }
            else -> {}
        }
    }

    private fun currentT(): Double? {
        val now = SystemClock.elapsedRealtimeNanos()
        return when {
            lastStatusAt > 0 -> (lastDurNs + (now - lastStatusAt)) / 1e9
            startAt > 0 -> (now - startAt) / 1e9
            else -> null
        }
    }

    private fun addMark() {
        if (recording == null) return
        val t = currentT() ?: return
        marks.add(JSONObject().put("n", marks.size + 1).put("t", Math.round(t * 1000) / 1000.0)
            .put("wall", OffsetDateTime.now().toString()))
        markBtn.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        flash.alpha = 0.6f; flash.animate().alpha(0f).setDuration(250).start()
        markBtn.text = "MARK (${marks.size})"
        writeSidecar(null)
    }

    private fun undoMark() {
        if (recording == null || marks.isEmpty()) return
        marks.removeAt(marks.lastIndex)
        markBtn.text = if (marks.isEmpty()) "MARK" else "MARK (${marks.size})"
        toast("Last mark removed")
        writeSidecar(null)
    }

    private fun writeSidecar(duration: Double?) {
        val doc = marksDoc ?: return
        val json = JSONObject()
            .put("format", "narrated-video-marks")
            .put("version", 1)
            .put("video", videoDoc?.name ?: "$baseName.mp4")
            .put("recorded_at", recordedAt)
            .put("complete", duration != null)
            .put("duration", duration ?: JSONObject.NULL)
            .put("clock", "recorded-duration")
            .put("capture", JSONObject()
                .put("quality", "HD")
                .put("target_rotation_degrees", rotationDegrees()))
            .put("app", JSONObject().put("id", packageName).put("version", appVersion()))
            .put("marks", JSONArray(marks.map { JSONObject(it.toString()) }))
        try {
            contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(json.toString(2).toByteArray()) }
        } catch (e: Exception) {
            toast("Could not write marks: ${e.message}")
        }
    }

    private fun rotationDegrees(): Int = when (videoCapture?.targetRotation) {
        Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0
    }

    private fun appVersion(): String =
        if (Build.VERSION.SDK_INT >= 33) packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0)).versionName ?: "?"
        else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (recording != null && (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)) {
            if (event?.repeatCount == 0) addMark()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
