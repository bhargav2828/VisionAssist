package com.visionassist.app

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.content.Context
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.visionassist.app.ui.theme.VisionAssistTheme
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    companion object {
        private const val SERVICE_TYPE = "_visionassist._tcp."
        private const val LISTENING_TOKEN = "vision_listen"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var speechRecognizer: SpeechRecognizer? = null

    private var appInForeground = false
    private var hasGreeted = false
    private var isListening = false
    private var isSpeaking = false
    private var isProcessing = false
    private var resumeListeningAfterSpeech = false

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    @Volatile private var serverBaseUrl: String? = null
    private var isDiscoveringServer = false
    private var lastDiscoveryAnnouncement = 0L

    private var lastDescription = ""

    // Camera objects used by Demo Mode.
    private var activeImageCapture: ImageCapture? = null
    private var activeCameraProvider: ProcessCameraProvider? = null

    // Screen state.
    private val statusState =
        mutableStateOf("Starting Vision Assist...")

    private val descriptionState =
        mutableStateOf("Your image description will appear here.")

    private val processingState =
        mutableStateOf(false)

    private val demoModeState =
        mutableStateOf(false)

    private val capturedBitmapState =
        mutableStateOf<Bitmap?>(null)

    private val savedPhotoState =
        mutableStateOf("")

    private val permissionNeededState =
        mutableStateOf(true)

    private val heardCommandState =
        mutableStateOf("No voice command detected yet.")

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {

            refreshPermissionState()

            if (!permissionNeededState.value) {
                statusState.value = "Permissions granted."
                startGreetingOrListening()
            } else {
                statusState.value =
                    "Camera and microphone permissions are required."
            }
        }

    // Keep the display awake whenever Vision Assist is visible.
    // Android automatically stops applying this flag when the activity is no longer visible.
    private fun setScreenAwake(keepAwake: Boolean) {
        if (keepAwake) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Deliberately do not clear this flag while the app is open.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // A blind user may not know whether the app is still open if the screen sleeps.
        // Keep the display on for the entire time this activity is visible.
        setScreenAwake(true)

        tts = TextToSpeech(this) { result ->

            mainHandler.post {

                if (result == TextToSpeech.SUCCESS) {

                    val languageResult =
                        tts?.setLanguage(Locale.US)

                    ttsReady =
                        languageResult != TextToSpeech.LANG_MISSING_DATA &&
                                languageResult != TextToSpeech.LANG_NOT_SUPPORTED

                    tts?.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {

                            override fun onStart(utteranceId: String?) {
                                mainHandler.post {
                                    isSpeaking = true
                                    setScreenAwake(true)
                                }
                            }

                            override fun onDone(utteranceId: String?) {
                                mainHandler.post {
                                    isSpeaking = false
                                    if (!isProcessing) setScreenAwake(false)

                                    if (
                                        resumeListeningAfterSpeech &&
                                        appInForeground &&
                                        !isProcessing
                                    ) {
                                        resumeListeningAfterSpeech = false
                                        scheduleListening(350)
                                    }
                                }
                            }

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) {
                                mainHandler.post {
                                    isSpeaking = false
                                    if (!isProcessing) setScreenAwake(false)

                                    if (
                                        resumeListeningAfterSpeech &&
                                        appInForeground &&
                                        !isProcessing
                                    ) {
                                        resumeListeningAfterSpeech = false
                                        scheduleListening(500)
                                    }
                                }
                            }

                            override fun onError(
                                utteranceId: String?,
                                errorCode: Int
                            ) {
                                onError(utteranceId)
                            }
                        }
                    )

                    if (appInForeground) {
                        startGreetingOrListening()
                    }

                } else {
                    statusState.value =
                        "Text-to-speech is unavailable."
                }
            }
        }

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        startServerDiscovery(announce = true)

        setContent {

            VisionAssistTheme {

                VisionAssistScreen(
                    status = statusState.value,
                    description = descriptionState.value,
                    processing = processingState.value,
                    demoMode = demoModeState.value,
                    capturedBitmap = capturedBitmapState.value,
                    savedPhoto = savedPhotoState.value,
                    showPermissionButton = permissionNeededState.value,
                    heardCommand = heardCommandState.value,

                    onDemoModeChange = { enabled ->

                        if (!isProcessing) {

                            demoModeState.value = enabled

                            if (!enabled) {

                                activeCameraProvider?.unbindAll()

                                activeCameraProvider = null
                                activeImageCapture = null

                                statusState.value =
                                    "Normal hands-free mode enabled."

                            } else {

                                statusState.value =
                                    "Demonstration mode enabled. Camera starting."
                            }
                        }
                    },

                    onCameraReady = { capture, provider ->
                        activeImageCapture = capture
                        activeCameraProvider = provider
                    },

                    onRequestPermissions = {
                        requestPermissionsIfNeeded()
                    },

                    onDescribe = {
                        captureAndDescribe()
                    },

                    onRepeat = {
                        repeatDescription()
                    },

                    onStopSpeaking = {
                        stopSpeakingAndListen()
                    },

                    onClose = {
                        finish()
                    }
                )
            }
        }

        refreshPermissionState()

        mainHandler.post {
            requestPermissionsIfNeeded()
        }
    }

    override fun onResume() {
        super.onResume()

        appInForeground = true

        if (serverBaseUrl == null) {
            startServerDiscovery(announce = false)
        }

        refreshPermissionState()

        if (!permissionNeededState.value && ttsReady) {
            startGreetingOrListening()
        }
    }

    override fun onPause() {

        appInForeground = false

        stopListening()

        tts?.stop()

        isSpeaking = false
        resumeListeningAfterSpeech = false
        setScreenAwake(false)

        super.onPause()
    }

    override fun onDestroy() {

        appInForeground = false
        setScreenAwake(false)

        stopListening()

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null

        activeCameraProvider?.unbindAll()
        activeCameraProvider = null
        activeImageCapture = null
        stopServerDiscovery()

        tts?.stop()
        tts?.shutdown()
        tts = null

        super.onDestroy()
    }

    // -------------------------------------------------
    // PERMISSIONS
    // -------------------------------------------------

    private fun hasPermission(permission: String): Boolean {

        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshPermissionState() {

        permissionNeededState.value = !(
                hasPermission(Manifest.permission.CAMERA) &&
                        hasPermission(Manifest.permission.RECORD_AUDIO)
                )
    }

    private fun requestPermissionsIfNeeded() {

        val missing = mutableListOf<String>()

        if (!hasPermission(Manifest.permission.CAMERA)) {
            missing.add(Manifest.permission.CAMERA)
        }

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }

        if (missing.isEmpty()) {

            permissionNeededState.value = false
            startGreetingOrListening()

        } else {

            permissionNeededState.value = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    // -------------------------------------------------
    // VOICE AND SPEECH
    // -------------------------------------------------

    private fun startGreetingOrListening() {

        if (
            !appInForeground ||
            !ttsReady ||
            isProcessing ||
            isSpeaking ||
            permissionNeededState.value
        ) return

        createSpeechRecognizerIfNeeded()

        if (!hasGreeted) {

            hasGreeted = true

            speak(
                "Hello! What would you like me to describe?",
                true
            )

        } else {
            scheduleListening(300)
        }
    }

    private fun createSpeechRecognizerIfNeeded() {

        if (speechRecognizer != null) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {

            statusState.value =
                "Speech recognition is unavailable on this phone."

            return
        }

        try {

            speechRecognizer =
                SpeechRecognizer.createSpeechRecognizer(this).also { recognizer ->

                    recognizer.setRecognitionListener(

                        object : RecognitionListener {

                            override fun onReadyForSpeech(params: Bundle?) {

                                isListening = true
                                statusState.value = "Listening for your command."
                            }

                            override fun onBeginningOfSpeech() {
                                statusState.value = "Hearing your command."
                            }

                            override fun onRmsChanged(rmsdB: Float) {}
                            override fun onBufferReceived(buffer: ByteArray?) {}

                            override fun onEndOfSpeech() {

                                isListening = false
                                statusState.value = "Processing voice command."
                            }

                            override fun onError(error: Int) {

                                isListening = false

                                if (
                                    !appInForeground ||
                                    isProcessing ||
                                    isSpeaking
                                ) return

                                if (
                                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                                ) {
                                    statusState.value =
                                        "Microphone permission is missing."
                                    return
                                }

                                val delay =
                                    if (
                                        error == SpeechRecognizer.ERROR_NO_MATCH ||
                                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                                    ) 500L else 1200L

                                scheduleListening(delay)
                            }

                            override fun onResults(results: Bundle?) {

                                isListening = false

                                val matches =
                                    results?.getStringArrayList(
                                        SpeechRecognizer.RESULTS_RECOGNITION
                                    )

                                val command =
                                    matches?.firstOrNull()
                                        ?.lowercase(Locale.getDefault())
                                        ?.replace(Regex("[^a-z0-9 ]"), " ")
                                        ?.replace(Regex("\\s+"), " ")
                                        ?.trim()
                                        .orEmpty()

                                handleVoiceCommand(command)
                            }

                            override fun onPartialResults(
                                partialResults: Bundle?
                            ) {}

                            override fun onEvent(
                                eventType: Int,
                                params: Bundle?
                            ) {}
                        }
                    )
                }

        } catch (_: Exception) {

            statusState.value =
                "Could not start speech recognition."
        }
    }

    private fun scheduleListening(delayMs: Long) {

        mainHandler.removeCallbacksAndMessages(LISTENING_TOKEN)

        mainHandler.postAtTime(
            { startListening() },
            LISTENING_TOKEN,
            SystemClock.uptimeMillis() + delayMs
        )
    }

    private fun startListening() {

        if (
            !appInForeground ||
            !ttsReady ||
            isProcessing ||
            isSpeaking ||
            isListening ||
            !hasPermission(Manifest.permission.RECORD_AUDIO)
        ) return

        createSpeechRecognizerIfNeeded()

        val recognizer = speechRecognizer ?: return

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                Locale.getDefault().toLanguageTag()
            )

            putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                true
            )

            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                3
            )
        }

        try {

            recognizer.startListening(intent)

            isListening = true
            statusState.value = "Listening for your command."

        } catch (_: Exception) {

            isListening = false
            scheduleListening(1200)
        }
    }

    private fun stopListening() {

        mainHandler.removeCallbacksAndMessages(LISTENING_TOKEN)

        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }

        isListening = false
    }

    private fun handleVoiceCommand(command: String) {

        heardCommandState.value =
            if (command.isBlank()) {
                "No speech was recognised."
            } else {
                "Heard: $command"
            }

        if (command.isBlank()) {
            scheduleListening(400)
            return
        }

        when {

            command.contains("close vision assist") ||
                    command == "close app" ||
                    command == "exit vision assist" ||
                    command == "stop vision assist" -> {

                stopListening()
                tts?.stop()
                finish()
            }

            command.contains("stop speaking") ||
                    command == "stop talking" -> {

                stopSpeakingAndListen()
            }

            command.contains("repeat") -> {

                repeatDescription()
            }

            command.contains("describe") ||
                    command.contains("take picture") ||
                    command.contains("take image") ||
                    command.contains("another photo") ||
                    command.contains("another picture") ||
                    command.contains("what do you see") ||
                    command.contains("what is around") ||
                    command.contains("what's around") -> {

                captureAndDescribe()
            }

            else -> {

                statusState.value = "Command not recognized."

                speak(
                    "I didn't catch that. You can say describe this, repeat that, or close Vision Assist.",
                    true
                )
            }
        }
    }

    private fun speak(
        text: String,
        resumeListening: Boolean
    ) {

        stopListening()

        if (!ttsReady || tts == null) {

            statusState.value = text

            if (resumeListening) {
                scheduleListening(1000)
            }

            return
        }

        resumeListeningAfterSpeech = resumeListening
        isSpeaking = true
        setScreenAwake(true)

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "vision-${System.currentTimeMillis()}"
        )
    }

    private fun repeatDescription() {

        if (lastDescription.isBlank()) {

            speak(
                "There is no previous description yet. Say describe this to take a picture.",
                true
            )

        } else {

            statusState.value = "Repeating the last description."
            speak(lastDescription, true)
        }
    }

    private fun stopSpeakingAndListen() {

        tts?.stop()

        isSpeaking = false
        resumeListeningAfterSpeech = false
        if (!isProcessing) setScreenAwake(false)

        statusState.value = "Speech stopped."

        scheduleListening(300)
    }

    // -------------------------------------------------
    // CAMERA CAPTURE
    // -------------------------------------------------

    private fun captureAndDescribe() {

        if (isProcessing) return

        if (
            !hasPermission(Manifest.permission.CAMERA) ||
            !hasPermission(Manifest.permission.RECORD_AUDIO)
        ) {

            speak(
                "Please allow camera and microphone permissions first.",
                false
            )

            requestPermissionsIfNeeded()
            return
        }

        stopListening()

        tts?.stop()

        isSpeaking = false
        resumeListeningAfterSpeech = false

        isProcessing = true
        processingState.value = true
        setScreenAwake(true)

        capturedBitmapState.value = null
        savedPhotoState.value = ""

        statusState.value = "Opening camera..."

        val photo = File(
            cacheDir,
            "vision_capture.jpg"
        )

        if (photo.exists()) {
            photo.delete()
        }

        // Demo Mode already has a live camera session.
        val existingCapture = activeImageCapture
        val existingProvider = activeCameraProvider

        if (existingCapture != null && existingProvider != null) {

            takePhoto(
                existingCapture,
                existingProvider,
                photo,
                false
            )

            return
        }

        // Normal Mode: open the camera only for capture.
        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            var provider: ProcessCameraProvider? = null
            var capture: ImageCapture? = null

            try {

                provider = providerFuture.get()

                provider.unbindAll()

                capture = ImageCapture.Builder()
                    .setCaptureMode(
                        ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                    )
                    .build()

                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    capture
                )

                takePhoto(
                    capture,
                    provider,
                    photo,
                    true
                )

            } catch (e: Exception) {

                finishProcessingWithMessage(
                    "I couldn't open the camera. Please try again.",
                    "Camera error: ${e.message}"
                )
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto(
        capture: ImageCapture,
        provider: ProcessCameraProvider,
        photo: File,
        temporaryCamera: Boolean
    ) {

        statusState.value = "Capturing image..."

        val options =
            ImageCapture.OutputFileOptions.Builder(photo).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),

            object : ImageCapture.OnImageSavedCallback {

                override fun onImageSaved(
                    output: ImageCapture.OutputFileResults
                ) {

                    if (temporaryCamera) {

                        try {
                            provider.unbindAll()
                        } catch (_: Exception) {
                        }
                    }

                    statusState.value =
                        "Image captured. Analysing the scene."

                    speak(
                        "Image captured. Analysing the scene. Please wait.",
                        false
                    )

                    // Show the captured photo in Demo Mode.
                    if (demoModeState.value) {

                        try {

                            val bitmap =
                                BitmapFactory.decodeFile(photo.absolutePath)

                            capturedBitmapState.value = bitmap

                        } catch (_: Exception) {

                            capturedBitmapState.value = null
                        }
                    }

                    uploadImage(photo)
                }

                override fun onError(
                    exception: ImageCaptureException
                ) {

                    if (temporaryCamera) {

                        try {
                            provider.unbindAll()
                        } catch (_: Exception) {
                        }
                    }

                    finishProcessingWithMessage(
                        "I couldn't capture the image. Please try again.",
                        "Camera capture failed: ${exception.message}"
                    )
                }
            }
        )
    }

    // -------------------------------------------------
    // SAVE PHOTO TO PHONE
    // -------------------------------------------------

    private fun savePhotoToGallery(photo: File): String {

        return try {

            val resolver = contentResolver

            val values = ContentValues().apply {

                put(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    "VisionAssist_${System.currentTimeMillis()}.jpg"
                )

                put(
                    MediaStore.Images.Media.MIME_TYPE,
                    "image/jpeg"
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        "${Environment.DIRECTORY_PICTURES}/Vision Assist"
                    )

                    put(
                        MediaStore.Images.Media.IS_PENDING,
                        1
                    )
                }
            }

            val imageUri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values
            ) ?: throw Exception("Could not create gallery image.")

            resolver.openOutputStream(imageUri).use { output ->

                if (output == null) {
                    throw Exception("Could not save image.")
                }

                photo.inputStream().use { input ->
                    input.copyTo(output)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {

                val completedValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }

                resolver.update(
                    imageUri,
                    completedValues,
                    null,
                    null
                )
            }

            "Saved to Pictures/Vision Assist"

        } catch (e: Exception) {

            "Photo could not be saved: ${e.message}"
        }
    }


    // -------------------------------------------------
    // AUTOMATIC LAPTOP DISCOVERY (mDNS / Android NSD)
    // -------------------------------------------------

    private fun startServerDiscovery(announce: Boolean = false) {
        val manager = nsdManager ?: return
        if (isDiscoveringServer) return

        if (announce && System.currentTimeMillis() - lastDiscoveryAnnouncement > 15_000) {
            lastDiscoveryAnnouncement = System.currentTimeMillis()
            if (appInForeground && ttsReady) {
                speak("Searching for the Vision Assist server.", false)
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                isDiscoveringServer = true
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.') != SERVICE_TYPE.trimEnd('.')) return
                try {
                    manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                            serverBaseUrl = null
                        }

                        override fun onServiceResolved(info: NsdServiceInfo) {
                            val host = info.host?.hostAddress ?: return
                            val port = info.port
                            serverBaseUrl = "http://$host:$port"
                            mainHandler.post {
                                statusState.value = "Vision Assist server connected."
                                if (appInForeground && ttsReady && !isProcessing && !isSpeaking) {
                                    speak("Vision Assist server connected.", false)
                                }
                            }
                        }
                    })
                } catch (_: Exception) {
                    // Another discovery callback may already be resolving this service.
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                serverBaseUrl = null
                mainHandler.post {
                    statusState.value = "Server connection lost. Searching again."
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                isDiscoveringServer = false
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                isDiscoveringServer = false
                try { manager.stopServiceDiscovery(this) } catch (_: Exception) {}
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                try { manager.stopServiceDiscovery(this) } catch (_: Exception) {}
            }
        }

        discoveryListener = listener
        try {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            isDiscoveringServer = false
        }
    }

    private fun stopServerDiscovery() {
        val manager = nsdManager
        val listener = discoveryListener
        if (manager != null && listener != null) {
            try { manager.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
        discoveryListener = null
        isDiscoveringServer = false
    }

    private fun rediscoverServer() {
        serverBaseUrl = null
        stopServerDiscovery()
        startServerDiscovery(announce = true)
    }

    // -------------------------------------------------
    // LAPTOP AI CONNECTION
    // -------------------------------------------------

    private fun uploadImage(photo: File) {

        if (serverBaseUrl == null) {
            mainHandler.post {
                statusState.value = "Searching for Vision Assist server..."
                startServerDiscovery(announce = false)
            }
        }

        Thread {

            try {

                val savedLocation =
                    savePhotoToGallery(photo)

                // Give Android NSD time to resolve the laptop service.
                var attempts = 0
                while (serverBaseUrl == null && attempts < 80) {
                    SystemClock.sleep(250)
                    attempts++
                }
                val discoveredBaseUrl = serverBaseUrl
                    ?: throw Exception(
                        "Vision Assist server was not found. Check that the laptop server is running on the same Wi-Fi."
                    )

                val client =
                    OkHttpClient.Builder()
                        .connectTimeout(20, TimeUnit.SECONDS)
                        .writeTimeout(60, TimeUnit.SECONDS)
                        .readTimeout(180, TimeUnit.SECONDS)
                        .build()

                val body =
                    MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                            "file",
                            photo.name,
                            photo.asRequestBody(
                                "image/jpeg".toMediaType()
                            )
                        )
                        .build()

                val request =
                    Request.Builder()
                        .url("${serverBaseUrl ?: throw Exception("Vision Assist server not found on this Wi-Fi. Please check the laptop server and try again.")}/upload")
                        .post(body)
                        .build()

                client.newCall(request)
                    .execute()
                    .use { response ->

                        val responseText =
                            response.body?.string().orEmpty()

                        if (!response.isSuccessful) {

                            val detail =
                                try {

                                    JSONObject(responseText)
                                        .optString(
                                            "detail",
                                            "Server error ${response.code}"
                                        )

                                } catch (_: Exception) {

                                    "Server error ${response.code}"
                                }

                            throw Exception(detail)
                        }

                        val result =
                            JSONObject(responseText)
                                .optString("description", "")
                                .trim()

                        if (result.isBlank()) {

                            throw Exception(
                                "The server returned an empty description."
                            )
                        }

                        mainHandler.post {

                            isProcessing = false
                            processingState.value = false

                            lastDescription = result
                            descriptionState.value = result

                            savedPhotoState.value = savedLocation

                            statusState.value =
                                "Description ready."

                            if (appInForeground) {

                                speak(
                                    "Here is what I see. $result",
                                    true
                                )
                            }
                        }
                    }

            } catch (e: Exception) {

                mainHandler.post {

                    finishProcessingWithMessage(
                        "I couldn't analyse the image. Please check the connection and try again.",
                        "Analysis failed: ${e.message}"
                    )
                    rediscoverServer()
                }
            }

        }.start()
    }

    private fun finishProcessingWithMessage(
        spokenMessage: String,
        statusMessage: String
    ) {

        isProcessing = false
        processingState.value = false

        statusState.value = statusMessage

        if (appInForeground) {
            speak(spokenMessage, true)
        } else {
            setScreenAwake(false)
        }
    }
}

// =====================================================
// CAMERA PREVIEW COMPONENT
// =====================================================

@Composable
private fun CameraPreview(
    onCameraReady: (ImageCapture?, ProcessCameraProvider?) -> Unit,
    modifier: Modifier = Modifier
) {

    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var providerReference by remember {
        mutableStateOf<ProcessCameraProvider?>(null)
    }

    AndroidView(
        modifier = modifier,

        factory = { ctx ->

            val previewView = PreviewView(ctx)

            previewView.scaleType =
                PreviewView.ScaleType.FILL_CENTER

            val providerFuture =
                ProcessCameraProvider.getInstance(ctx)

            providerFuture.addListener({

                try {

                    val provider = providerFuture.get()

                    val preview =
                        Preview.Builder().build()

                    val capture =
                        ImageCapture.Builder()
                            .setCaptureMode(
                                ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                            )
                            .build()

                    preview.setSurfaceProvider(
                        previewView.surfaceProvider
                    )

                    provider.unbindAll()

                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture
                    )

                    providerReference = provider

                    onCameraReady(capture, provider)

                } catch (_: Exception) {

                    onCameraReady(null, null)
                }

            }, ContextCompat.getMainExecutor(context))

            previewView
        }
    )

    DisposableEffect(lifecycleOwner) {

        onDispose {

            try {
                providerReference?.unbindAll()
            } catch (_: Exception) {
            }

            providerReference = null

            onCameraReady(null, null)
        }
    }
}

// =====================================================
// MAIN SCREEN
// =====================================================

@Composable
private fun VisionAssistScreen(
    status: String,
    description: String,
    processing: Boolean,
    demoMode: Boolean,
    capturedBitmap: Bitmap?,
    savedPhoto: String,
    showPermissionButton: Boolean,
    heardCommand: String,
    onDemoModeChange: (Boolean) -> Unit,
    onCameraReady: (ImageCapture?, ProcessCameraProvider?) -> Unit,
    onRequestPermissions: () -> Unit,
    onDescribe: () -> Unit,
    onRepeat: () -> Unit,
    onStopSpeaking: () -> Unit,
    onClose: () -> Unit
) {
    val backgroundTop = Color(0xFF101426)
    val backgroundBottom = Color(0xFF17152B)
    val panelColor = Color(0xFF20243A)
    val panelLight = Color(0xFF292D48)
    val purple = Color(0xFF9277F5)
    val cyan = Color(0xFF73D9E8)
    val white = Color(0xFFF6F5FF)
    val muted = Color(0xFFB8BED3)
    val green = Color(0xFF72D6A0)
    val amber = Color(0xFFFFC66D)

    val isConnected = status.contains("server connected", ignoreCase = true) &&
            !status.contains("lost", ignoreCase = true)
    val statusAccent = when {
        processing -> cyan
        isConnected -> green
        status.contains("error", ignoreCase = true) ||
                status.contains("couldn't", ignoreCase = true) ||
                status.contains("not found", ignoreCase = true) -> amber
        else -> purple
    }

    Scaffold(containerColor = backgroundTop) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(backgroundTop, backgroundBottom)
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF302B58)
                    ) {
                        Box(
                            modifier = Modifier.size(52.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "◉",
                                color = cyan,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "VISION ASSIST",
                            color = white,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.2.sp
                        )
                        Text(
                            text = "Your AI visual companion",
                            color = muted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isConnected) Color(0xFF173D39) else Color(0xFF34304E)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(statusAccent, CircleShape)
                            )
                            Text(
                                text = if (isConnected) "Connected" else "Server",
                                color = white,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = panelColor)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(17.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF302B58)
                        ) {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = when {
                                        processing -> "…"
                                        (status.contains("Listening", true) || status.contains("Hearing your command", true)) -> "◖"
                                        else -> "◉"
                                    },
                                    color = cyan,
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = when {
                                    processing -> "ANALYSING"
                                    (status.contains("Listening", true) || status.contains("Hearing your command", true)) -> "READY TO LISTEN"
                                    isConnected -> "VISION ASSIST IS READY"
                                    else -> "APP STATUS"
                                },
                                color = cyan,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = status,
                                color = white,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        if (processing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(25.dp),
                                color = cyan,
                                strokeWidth = 3.dp
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (processing) "PLEASE WAIT" else "READY WHEN YOU ARE",
                        color = muted,
                        style = MaterialTheme.typography.labelLarge,
                        letterSpacing = 1.5.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Button(
                        onClick = onDescribe,
                        enabled = !processing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 84.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = purple,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF51466F),
                            disabledContentColor = Color(0xFFD8D2E8)
                        ),
                        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = if (processing) "ANALYSING IMAGE" else "DESCRIBE THIS",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.7.sp
                            )
                            Text(
                                text = if (processing) {
                                    "Please wait for the description"
                                } else {
                                    "Capture and describe your surroundings"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = onRepeat,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 62.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = cyan
                            )
                        ) {
                            Text(
                                text = "↻  Repeat",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        OutlinedButton(
                            onClick = onStopSpeaking,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 62.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFFFB8C5)
                            )
                        ) {
                            Text(
                                text = "■  Stop speaking",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = panelColor)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 17.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Demo Mode",
                                color = white,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = if (demoMode) {
                                    "Live camera preview is visible"
                                } else {
                                    "Camera preview is hidden"
                                },
                                color = muted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = demoMode,
                            onCheckedChange = onDemoModeChange,
                            enabled = !processing
                        )
                    }
                }

                if (demoMode) {
                    SectionHeading(
                        title = "LIVE CAMERA PREVIEW",
                        subtitle = "Visible for project demonstrations"
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = panelColor)
                    ) {
                        CameraPreview(
                            onCameraReady = onCameraReady,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(240.dp)
                        )
                    }
                }

                if (showPermissionButton) {
                    Button(
                        onClick = onRequestPermissions,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 58.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cyan,
                            contentColor = Color(0xFF101426)
                        )
                    ) {
                        Text(
                            text = "Allow camera and microphone",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (demoMode && capturedBitmap != null) {
                    SectionHeading(
                        title = "CAPTURED IMAGE",
                        subtitle = "The photograph sent for analysis"
                    )
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = panelColor)
                    ) {
                        Image(
                            bitmap = capturedBitmap.asImageBitmap(),
                            contentDescription = "Latest captured photograph",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 340.dp)
                                .padding(10.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                }

                if (savedPhoto.isNotBlank()) {
                    Text(
                        text = savedPhoto,
                        color = muted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                SectionHeading(
                    title = "AI DESCRIPTION",
                    subtitle = "The latest visual description"
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = panelLight)
                ) {
                    Text(
                        text = description,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(19.dp),
                        color = white,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 27.sp
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = panelColor)
                ) {
                    Column(
                        modifier = Modifier.padding(15.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "LAST VOICE COMMAND",
                            color = cyan,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.7.sp
                        )
                        Text(
                            text = heardCommand,
                            color = muted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = muted
                    )
                ) {
                    Text(
                        text = "Close Vision Assist",
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Text(
                    text = "Vision Assist • Visual understanding made accessible",
                    color = Color(0xFF858CA5),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            color = Color(0xFF9F91FF),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Text(
            text = subtitle,
            color = Color(0xFFB8BED3),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

