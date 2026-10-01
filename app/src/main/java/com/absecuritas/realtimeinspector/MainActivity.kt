package com.absecuritas.realtimeinspector

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var loginPanel: LinearLayout
    private lateinit var startPanel: LinearLayout
    private lateinit var inspectionPanel: View
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var scanHint: TextView
    private lateinit var lastScan: TextView
    private lateinit var companyText: TextView
    private lateinit var inspectorText: TextView
    private lateinit var deviceText: TextView
    private lateinit var locationSummary: TextView
    private lateinit var locationSummary2: TextView
    private lateinit var inspectorText2: TextView
    private lateinit var userIdInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var deviceIdText: TextView
    private lateinit var locationDetails: TextView

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val processing = AtomicBoolean(false)
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var mobileToken = ""
    private var companyName = ""
    private var userName = ""
    private var userId = ""
    private var deviceId = ""
    private var inspectionStarted = false
    private var inspectionSessionToken = ""
    private var inspectionSessionId = 0
    private var alertShowing = false

    companion object {
        // Screen-QR detection thresholds (0-255 luminance around the QR code).
        private const val SCREEN_MIN_LUMA = 246.0
        private const val SCREEN_MAX_STD = 5.0
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // Storage permission is only needed on Android 9 and below; gallery saving is skipped if it is denied.
        if (result[Manifest.permission.CAMERA] == true || hasCameraPermission()) startCamera() else showStatus("CAMERA PERMISSION REQUIRED", false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        bindViews()
        deviceId = getSharedPreferences("rti_mobile", MODE_PRIVATE).getString("device_id", "") ?: ""
        if (deviceId.isBlank()) {
            val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "DEVICE"
            val normalized = androidId.replace("[^A-Za-z0-9]".toRegex(), "").uppercase(Locale.US).takeLast(16)
            deviceId = "DEV-" + normalized
            getSharedPreferences("rti_mobile", MODE_PRIVATE).edit().putString("device_id", deviceId).apply()
        }
        deviceIdText.text = deviceId
        restoreSession()
    }

    private fun bindViews() {
        loginPanel = findViewById(R.id.loginPanel)
        startPanel = findViewById(R.id.startPanel)
        inspectionPanel = findViewById(R.id.inspectionPanel)
        previewView = findViewById(R.id.previewView)
        statusText = findViewById(R.id.statusText)
        scanHint = findViewById(R.id.scanHint)
        lastScan = findViewById(R.id.lastScan)
        companyText = findViewById(R.id.companyText)
        inspectorText = findViewById(R.id.inspectorText)
        deviceText = findViewById(R.id.deviceText)
        locationSummary = findViewById(R.id.locationSummary)
        locationSummary2 = findViewById(R.id.locationSummary2)
        inspectorText2 = findViewById(R.id.inspectorText2)
        userIdInput = findViewById(R.id.userIdInput)
        passwordInput = findViewById(R.id.passwordInput)
        deviceIdText = findViewById(R.id.deviceIdText)
        locationDetails = findViewById(R.id.locationDetails)
        findViewById<Button>(R.id.loginButton).setOnClickListener { login() }
        findViewById<Button>(R.id.startInspectionButton).setOnClickListener { startInspection() }
        findViewById<Button>(R.id.stopInspectionButton).setOnClickListener { stopInspection() }
        findViewById<Button>(R.id.switchInspectorButton).setOnClickListener { switchInspector() }
    }

    private fun restoreSession() {
        mobileToken = getSharedPreferences("rti_mobile", MODE_PRIVATE).getString("token", "") ?: ""
        if (mobileToken.isBlank()) {
            showLogin()
            return
        }
        Thread {
            try {
                val code = stateRequest()
                if (code.first in 200..299) {
                    val json = JSONObject(code.second)
                    applySession(json)
                    inspectionSessionToken = json.optString("inspection_session_token", "")
                    inspectionSessionId = json.optInt("inspection_session_id", 0)
                    val active = json.optBoolean("inspection_active", false)
                    mainHandler.post { if (active && inspectionSessionToken.isNotBlank()) { showInspection(); ensureCamera() } else showStart() }
                } else mainHandler.post { showLogin() }
            } catch (_: Exception) { mainHandler.post { showLogin() } }
        }.start()
    }

    private fun login() {
        val uid = userIdInput.text.toString().trim()
        val pw = passwordInput.text.toString()
        val did = deviceId
        if (uid.isBlank() || pw.isBlank() || did.isBlank()) {
            Toast.makeText(this, "User ID and Password are required", Toast.LENGTH_LONG).show()
            return
        }
        showLoginStatus("CONNECTING TO COMPANY...", true)
        Thread {
            try {
                val payload = JSONObject().apply { put("device_id", did); put("user_id", uid); put("password", pw) }
                val result = postJson(AppConfig.SERVER_BASE_URL + "/api/mobile/login", payload, null)
                if (result.first in 200..299) {
                    val json = JSONObject(result.second)
                    mobileToken = json.optString("mobile_token")
                    companyName = json.optString("company_name")
                    userName = json.optString("user_name")
                    userId = json.optString("inspector_id", uid)
                    deviceId = json.optString("device_id", did)
                    getSharedPreferences("rti_mobile", MODE_PRIVATE).edit().putString("token", mobileToken).putString("device_id", deviceId).apply()
                    mainHandler.post { showStart() }
                } else {
                    val msg = try { JSONObject(result.second).optString("message", "Login failed") } catch (_: Exception) { "Login failed" }
                    mainHandler.post { showLoginStatus(msg, false) }
                }
            } catch (_: Exception) { mainHandler.post { showLoginStatus("NETWORK ERROR — CHECK INTERNET", false) } }
        }.start()
    }

    private fun showLogin() {
        loginPanel.visibility = View.VISIBLE
        startPanel.visibility = View.GONE
        inspectionPanel.visibility = View.GONE
    }

    private fun showStart() {
        inspectionStarted = false
        inspectionPanel.visibility = View.GONE
        loginPanel.visibility = View.GONE
        startPanel.visibility = View.VISIBLE
        companyText.text = companyName.ifBlank { "Company" }
        inspectorText.text = "Inspector / User: $userName  •  ID: $userId"
        deviceText.text = "Device ID: $deviceId"
        findViewById<TextView>(R.id.startStatus).text = "Ready. Press Start Inspection when you reach the inspection area."
        refreshLocations()
    }

    private fun startInspection() {
        if (mobileToken.isBlank()) { showLogin(); return }
        findViewById<Button>(R.id.startInspectionButton).isEnabled = false
        Thread {
            try {
                val result = postJson(AppConfig.SERVER_BASE_URL + "/api/mobile/start-inspection", JSONObject(), mobileToken)
                if (result.first in 200..299) {
                    val json = JSONObject(result.second)
                    inspectionSessionToken = json.optString("inspection_session_token")
                    inspectionSessionId = json.optInt("inspection_session_id", 0)
                    inspectionStarted = true
                    mainHandler.post {
                        findViewById<Button>(R.id.startInspectionButton).isEnabled = true
                        showInspection()
                        ensureCamera()
                        refreshLocations()
                    }
                } else {
                    val msg = try { JSONObject(result.second).optString("message", "Unable to start inspection") } catch (_: Exception) { "Unable to start inspection" }
                    mainHandler.post { findViewById<Button>(R.id.startInspectionButton).isEnabled = true; Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                }
            } catch (_: Exception) {
                mainHandler.post { findViewById<Button>(R.id.startInspectionButton).isEnabled = true; Toast.makeText(this, "NETWORK ERROR — CHECK INTERNET", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun showInspection() {
        inspectionStarted = true
        loginPanel.visibility = View.GONE
        startPanel.visibility = View.GONE
        inspectionPanel.visibility = View.VISIBLE
        previewView.visibility = View.VISIBLE
        inspectorText2.text = "Inspector: $userName  •  ID: $userId\nDevice: $deviceId"
        scanHint.text = "Align the QR code inside the scan box"
        showStatus("READY — SCAN QR", true)
        refreshLocations()
    }

    private fun switchInspector() {
        stopCamera()
        val token = mobileToken
        mobileToken = ""
        inspectionStarted = false
        inspectionSessionToken = ""
        inspectionSessionId = 0
        getSharedPreferences("rti_mobile", MODE_PRIVATE).edit().remove("token").apply()
        if (token.isNotBlank()) Thread { try { postJson(AppConfig.SERVER_BASE_URL + "/api/mobile/logout", JSONObject(), token) } catch (_: Exception) {} }.start()
        showLogin()
    }

    private fun stopInspection(manual: Boolean = true) {
        val token = mobileToken
        val session = inspectionSessionToken
        inspectionStarted = false
        inspectionSessionToken = ""
        inspectionSessionId = 0
        stopCamera()
        previewView.visibility = View.GONE
        inspectionPanel.visibility = View.GONE
        if (manual && token.isNotBlank() && session.isNotBlank()) {
            Thread {
                try {
                    val r = postJson(AppConfig.SERVER_BASE_URL + "/api/mobile/end-inspection", JSONObject().apply { put("inspection_session_token", session) }, token)
                    if (r.first in 200..299) mainHandler.post { showUploadedPopup("✓ Inspection Ended", "Inspection data uploaded successfully.") }
                    else mainHandler.post { Toast.makeText(this, "Could not confirm upload. Check internet.", Toast.LENGTH_LONG).show() }
                } catch (_: Exception) { mainHandler.post { Toast.makeText(this, "Could not confirm upload. Check internet.", Toast.LENGTH_LONG).show() } }
            }.start()
        }
        showStart()
    }

    private fun refreshLocations() {
        if (mobileToken.isBlank()) return
        Thread {
            try {
                val r = stateRequest()
                if (r.first !in 200..299) return@Thread
                val json = JSONObject(r.second)
                applySession(json)
                val active = json.optBoolean("inspection_active", false)
                val token = json.optString("inspection_session_token", "")
                val sid = json.optInt("inspection_session_id", 0)
                if (active && token.isNotBlank()) { inspectionSessionToken = token; inspectionSessionId = sid }
                val scanned = json.optInt("scanned_points", 0)
                val pending = json.optInt("pending_points", 0)
                val total = json.optInt("total_points", 0)
                val summary = "TOTAL POINTS: $total    •    SCANNED: $scanned    •    PENDING: $pending"
                val arr = json.optJSONArray("locations")
                val scannedNames = StringBuilder(); val pendingNames = StringBuilder()
                if (arr != null) for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val label = item.optString("point_code") + " - " + item.optString("name")
                    if (item.optInt("scanned") == 1) { if (scannedNames.isNotEmpty()) scannedNames.append("\n"); scannedNames.append("✓ ").append(label) }
                    else { if (pendingNames.isNotEmpty()) pendingNames.append("\n"); pendingNames.append("• ").append(label) }
                }
                val details = StringBuilder()
                if (pendingNames.isNotEmpty()) details.append("PENDING\n").append(pendingNames)
                if (scannedNames.isNotEmpty()) {
                    if (details.isNotEmpty()) details.append("\n\n")
                    details.append("SCANNED\n").append(scannedNames)
                }
                val detailsText = details.toString()
                mainHandler.post {
                    locationSummary.text = summary
                    locationSummary2.text = summary
                    locationDetails.text = detailsText
                    if (inspectionStarted && json.optBoolean("inspection_complete", false)) completeInspection()
                }
            } catch (_: Exception) {}
        }.start()
    }

    private fun applySession(json: JSONObject) {
        companyName = json.optString("company_name", companyName)
        userName = json.optString("inspector_name", json.optString("user_name", userName))
        userId = json.optString("inspector_id", json.optString("user_id", userId))
    }

    private fun stateRequest(): Pair<Int, String> = getJson(AppConfig.SERVER_BASE_URL + "/api/mobile/state", mobileToken)

    private fun hasCameraPermission() = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun needsLegacyStoragePermission() = Build.VERSION.SDK_INT < 29 &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    private fun ensureCamera() {
        val needed = ArrayList<String>()
        if (!hasCameraPermission()) needed.add(Manifest.permission.CAMERA)
        if (needsLegacyStoragePermission()) needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        if (needed.isEmpty()) startCamera() else cameraPermission.launch(needed.toTypedArray())
    }

    private fun stopCamera() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        camera = null
    }

    private fun sessionExpired() {
        mobileToken = ""
        inspectionStarted = false
        inspectionSessionToken = ""
        inspectionSessionId = 0
        stopCamera()
        getSharedPreferences("rti_mobile", MODE_PRIVATE).edit().remove("token").apply()
        showLogin()
        showLoginStatus("Session expired. Please login again.", false)
    }

    private fun startCamera() {
        if (!inspectionStarted) return
        mainHandler.post { previewView.visibility = View.VISIBLE }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
            val scanner = BarcodeScanning.getClient(options)
            val resolution = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val mediaImage = imageProxy.image
                if (mediaImage == null || processing.get() || !inspectionStarted) { imageProxy.close(); return@setAnalyzer }
                val rotation = imageProxy.imageInfo.rotationDegrees
                val image = InputImage.fromMediaImage(mediaImage, rotation)
                scanner.process(image)
                    .addOnSuccessListener(cameraExecutor) { barcodes ->
                        val barcode = barcodes.firstOrNull()
                        val raw = barcode?.rawValue?.trim().orEmpty()
                        if (raw.startsWith("RTI-", true) && processing.compareAndSet(false, true)) {
                            try {
                                val frame = try { upright(imageProxy.toBitmap(), rotation) } catch (_: Exception) { null }
                                handleQr(raw, frame, barcode?.boundingBox)
                            } catch (_: Exception) { processing.set(false) }
                        }
                    }
                    .addOnFailureListener(cameraExecutor) { }
                    .addOnCompleteListener(cameraExecutor) { imageProxy.close() }
            }
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            showStatus("READY — SCAN QR", true)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun handleQr(rawValue: String, frame: Bitmap?, box: Rect?) {
        // Block QR codes shown on a screen (forwarded image / screenshot): a real point QR is printed on paper.
        if (frame != null && box != null && looksLikeScreenQr(frame, box)) {
            saveToGallery(frame, "REJECTED", "REJECTED - SCREEN/PHOTO QR")
            mainHandler.post {
                showStatus("✗ SCREEN / PHOTO QR NOT ALLOWED", false)
                scanHint.text = "Scan the original QR code at the inspection point"
            }
            mainHandler.postDelayed({ processing.set(false) }, 2500)
            return
        }
        mainHandler.post { scanHint.text = "Sending inspection..."; statusText.text = "CONNECTING TO SERVER..." }
        Thread {
            try {
                val payload = JSONObject().apply { put("qr_token", rawValue); put("device_id", deviceId); put("inspection_session_token", inspectionSessionToken) }
                val response = postJson(AppConfig.SERVER_BASE_URL + "/api/mobile/scan", payload, mobileToken)
                mainHandler.post { handleServerResponse(response.first, response.second, frame) }
            } catch (_: Exception) {
                mainHandler.post { showStatus("NETWORK ERROR — CHECK INTERNET", false); scanHint.text = "Ready for next inspection point"; processing.set(false) }
            }
        }.start()
    }

    private fun upright(src: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return src
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    // True when the area around the QR is perfectly white and uniform (typical of a QR image shown on a phone screen).
    // Thresholds are constants below; tune them after a field test if genuine paper QR codes get rejected.
    private fun looksLikeScreenQr(bmp: Bitmap, box: Rect): Boolean {
        val w = box.width()
        if (w < 40) return false
        val pad = (w * 0.30f).toInt()
        val step = maxOf(2, w / 40)
        var n = 0; var sum = 0.0; var sumSq = 0.0
        var y = box.top - pad
        while (y <= box.bottom + pad) {
            var x = box.left - pad
            while (x <= box.right + pad) {
                val inBox = x >= box.left && x <= box.right && y >= box.top && y <= box.bottom
                if (!inBox && x in 0 until bmp.width && y in 0 until bmp.height) {
                    val p = bmp.getPixel(x, y)
                    val l = 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                    n++; sum += l; sumSq += l * l
                }
                x += step
            }
            y += step
        }
        if (n < 30) return false
        val mean = sum / n
        val std = Math.sqrt(maxOf(0.0, sumSq / n - mean * mean))
        return mean >= SCREEN_MIN_LUMA && std <= SCREEN_MAX_STD
    }

    private fun saveToGallery(src: Bitmap, tag: String, label: String) {
        val inspector = "$userName ($userId)"; val device = deviceId; val stamp = now()
        Thread {
            try {
                val bmp = src.copy(Bitmap.Config.ARGB_8888, true)
                val canvas = Canvas(bmp)
                val size = (bmp.width * 0.035f).coerceAtLeast(18f)
                val bg = Paint().apply { color = Color.argb(170, 0, 0, 0) }
                val tp = Paint().apply { color = Color.WHITE; textSize = size; isAntiAlias = true; isFakeBoldText = true }
                canvas.drawRect(0f, bmp.height - size * 3.2f, bmp.width.toFloat(), bmp.height.toFloat(), bg)
                canvas.drawText(label, size * 0.5f, bmp.height - size * 1.9f, tp)
                canvas.drawText("$inspector | $device | $stamp", size * 0.5f, bmp.height - size * 0.6f, tp)
                val name = "RTI_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + "_" + tag + ".jpg"
                writeJpeg(bmp, name)
                bmp.recycle()
            } catch (_: Exception) {}
        }.start()
    }

    private fun writeJpeg(bmp: Bitmap, name: String) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/AB Securitas")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } else {
            if (needsLegacyStoragePermission()) return
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "AB Securitas")
            dir.mkdirs()
            val f = File(dir, name)
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), arrayOf("image/jpeg"), null)
        }
    }

    private fun handleServerResponse(httpCode: Int, body: String, frame: Bitmap?) {
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val result = json.optString("result", "")
        val pointName = json.optString("point_name", "Inspection Point")
        lastScan.text = "$pointName — ${now()}"
        if (frame != null && httpCode in 200..299 && (result.equals("ACCEPTED", true) || result.equals("REPEAT", true) || result.equals("REPEAT_ALERT", true))) {
            saveToGallery(frame, result.uppercase(Locale.US), "$pointName | $result")
        }
        when {
            httpCode in 200..299 && result.equals("ACCEPTED", true) -> showStatus("✓ ACCEPTED", true)
            httpCode in 200..299 && result.equals("REPEAT", true) -> showStatus("REPEAT ${json.optInt("repeat_count", 0)} / 1", false)
            httpCode in 200..299 && result.equals("REPEAT_ALERT", true) -> {
                showStatus("⚠ REPEAT ALERT", false)
                repeatAlert(json.optString("message", "This point has already been repeated once."))
            }
            httpCode == 401 -> { sessionExpired(); processing.set(false); return }
            httpCode == 429 -> { showStatus("TOO FAST — SCAN THE REAL QR AT THE POINT", false); beep(ToneGenerator.TONE_SUP_ERROR, 400) }
            else -> showStatus(json.optString("message", "SERVER ERROR ($httpCode)"), false)
        }
        scanHint.text = "Align the QR code inside the scan box"
        refreshLocations()
        if (json.optBoolean("inspection_complete", false)) {
            mainHandler.postDelayed({ completeInspection() }, 500)
        } else if (!alertShowing) {
            mainHandler.postDelayed({ processing.set(false) }, 1000)
        }
    }

    private fun repeatAlert(message: String) {
        if (alertShowing) return
        alertShowing = true
        try { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700) } catch (_: Exception) {}
        AlertDialog.Builder(this)
            .setTitle("Repeat Alert")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss(); alertShowing = false; processing.set(false) }
            .show()
    }

    private fun beep(tone: Int, ms: Int) {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 100).startTone(tone, ms) } catch (_: Exception) {}
    }

    private fun showUploadedPopup(title: String, message: String) {
        if (isFinishing) return
        beep(ToneGenerator.TONE_CDMA_CONFIRM, 800)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun completeInspection() {
        if (!inspectionStarted) return
        inspectionStarted = false
        stopCamera()
        val session = inspectionSessionToken
        inspectionSessionToken = ""
        inspectionSessionId = 0
        previewView.visibility = View.GONE
        inspectionPanel.visibility = View.GONE
        showStart()
        processing.set(false)
        showUploadedPopup("✓ Inspection Completed", "All points have been scanned.\nInspection data uploaded successfully.")
        if (session.isNotBlank() && mobileToken.isNotBlank()) {
            val token = mobileToken
            Thread { try { postJson(AppConfig.SERVER_BASE_URL + "/api/mobile/end-inspection", JSONObject().apply { put("inspection_session_token", session) }, token) } catch (_: Exception) {} }.start()
        }
    }

    private fun postJson(url: String, payload: JSONObject, token: String?): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10000; readTimeout = 10000; doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8"); setRequestProperty("Accept", "application/json")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return Pair(code, body)
    }

    private fun getJson(url: String, token: String): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 10000; readTimeout = 10000
            setRequestProperty("Accept", "application/json"); setRequestProperty("Authorization", "Bearer $token")
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        return Pair(code, body)
    }

    private fun showLoginStatus(message: String, success: Boolean) {
        findViewById<TextView>(R.id.loginStatus).text = message
        findViewById<TextView>(R.id.loginStatus).setTextColor(ContextCompat.getColor(this, if (success) R.color.navy else R.color.red))
    }

    private fun showStatus(message: String, success: Boolean) {
        statusText.text = message
        statusText.setTextColor(ContextCompat.getColor(this, if (success) R.color.green else R.color.red))
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    override fun onDestroy() { stopCamera(); super.onDestroy(); cameraExecutor.shutdown() }
}
