package com.example.mkproject

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.AudioProcessor
import be.tarsos.dsp.io.android.AndroidAudioInputStream
import be.tarsos.dsp.mfcc.MFCC
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import be.tarsos.dsp.io.TarsosDSPAudioInputStream
import com.example.mkproject.ui.theme.MainScreen
import com.example.mkproject.ui.theme.MkprojectTheme
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    // App logic variables
    private var isRecognizingMantra by mutableStateOf(false)
    private var isRecordingMantra by mutableStateOf(false)
    private val matchCount = AtomicInteger(0)
    private var matchLimit by mutableIntStateOf(0)
    private var targetMantra by mutableStateOf("")
    private var lastProcessedAudio: FloatArray? = null
    private var referenceMFCCs: Map<String, List<FloatArray>> = emptyMap()

    // Audio processing variables
    private var audioRecord: AudioRecord? = null
    private var audioDispatcher: AudioDispatcher? = null
    private var dispatcherThread: Thread? = null
    private var recordingThread: Thread? = null
    private val stopRecordingFlag = AtomicBoolean(false)

    // Constants for audio processing
    private val sampleRate = 48000
    private val audioFormatEncoding = AudioFormat.ENCODING_PCM_16BIT
    private val audioChannelConfig = AudioFormat.CHANNEL_IN_MONO

    // Buffer size for AudioRecord
    private val audioRecordMinBufferSize: Int by lazy {
        val size = AudioRecord.getMinBufferSize(sampleRate, audioChannelConfig, audioFormatEncoding)
        if (size <= 0) {
            Log.e("MainActivity", "Invalid min buffer size: $size, defaulting to 4096")
            4096
        } else {
            size
        }
    }
    private val actualRecordingBufferSize: Int by lazy {
        val minSize = audioRecordMinBufferSize * 2
        val powerOfTwo = listOf(1024, 2048, 4096, 8192).firstOrNull { it >= minSize } ?: 4096
        Log.d("MainActivity", "AudioRecord buffer size: min=$audioRecordMinBufferSize, chosen=$powerOfTwo")
        powerOfTwo
    }

    // TarsosDSP buffer sizes (power of 2 for FFT)
    private val tarsosProcessingBufferSizeSamples = 2048
    private val tarsosProcessingOverlapSamples = tarsosProcessingBufferSizeSamples / 2

    // Storage and permissions
    private val storageDir: File by lazy {
        File(filesDir, "mantras").also {
            if (!it.exists() && !it.mkdirs()) {
                Log.e("MainActivity", "Failed to create storage directory: ${it.absolutePath}")
                runOnUiThread {
                    Toast.makeText(this, "Failed to create storage directory.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    private val inbuiltMantraName = "testhello"
    private val savedMantras: List<String>
        get() {
            val files = storageDir.listFiles()
                ?.filter { it.extension == "wav" && isValidWavFile(it) }
                ?.map { it.nameWithoutExtension }
                ?.sorted()
                ?: emptyList()
            return files.ifEmpty {
                Log.d(
                    "MainActivity",
                    "No valid WAV files found in storage, including inbuilt mantra"
                )
                emptyList()
            }
        }

    private val recordAudioLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) showPermissionAlert()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Copy inbuilt mantra from assets to storageDir
        copyInbuiltMantraToStorage()

        setContent {
            MkprojectTheme {
                var matchLimitText by remember { mutableStateOf("") }
                MainScreen(
                    mantras = savedMantras,
                    matchLimitText = matchLimitText,
                    onMatchLimitTextChange = { matchLimitText = it },
                    onRecordMantraClick = { mantraName -> recordMantra(mantraName) },
                    onStartStopClick = { targetMantraName, matchLimitText ->
                        if (isRecognizingMantra) {
                            stopListening()
                        } else {
                            if (targetMantraName.isBlank()) {
                                Toast.makeText(this@MainActivity, "Please select a recorded mantra.", Toast.LENGTH_SHORT).show()
                                return@MainScreen
                            }
                            matchLimit = matchLimitText.toIntOrNull() ?: 0
                            if (matchLimit <= 0) {
                                Toast.makeText(this@MainActivity, "Please enter a valid match limit.", Toast.LENGTH_SHORT).show()
                                return@MainScreen
                            }
                            targetMantra = targetMantraName
                            matchCount.set(0)
                            isRecognizingMantra = true
                            startListeningWithDelay()
                        }
                    },
                    onStopRecordingClick = { stopRecordingMantra() },
                    onDeleteMantraClick = { mantraName -> deleteMantra(mantraName) },
                    matchCount = matchCount.get(),
                    processingStatus = when {
                        isRecognizingMantra -> "Listening for mantra..."
                        isRecordingMantra -> "Recording mantra..."
                        else -> "Stopped"
                    }
                )
            }
        }
        checkPermissionAndStart()
        loadReferenceMFCCs()
    }

    private fun copyInbuiltMantraToStorage() {
        val inbuiltFile = File(storageDir, "$inbuiltMantraName.wav") // storageDir is filesDir/mantras
        // inbuiltMantraName is "testhello"
        Log.d("MainActivity_Copy", "Target inbuilt file path: ${inbuiltFile.absolutePath}")

        // Check 1: Does the 'mantras' directory exist?
        if (!storageDir.exists()) {
            Log.e("MainActivity_Copy", "Storage directory ${storageDir.absolutePath} does NOT exist. Attempting to create.")
            if (!storageDir.mkdirs()) {
                Log.e("MainActivity_Copy", "FAILED to create storage directory ${storageDir.absolutePath}.")
                // Post toast or handle error appropriately, as file copy will fail
                runOnUiThread {
                    Toast.makeText(this, "Critical: Failed to create app data directory.", Toast.LENGTH_LONG).show()
                }
                return // Cannot proceed
            } else {
                Log.d("MainActivity_Copy", "Storage directory ${storageDir.absolutePath} created successfully.")
            }
        } else {
            Log.d("MainActivity_Copy", "Storage directory ${storageDir.absolutePath} already exists.")
        }

        // Check 2: If the file already exists, is it valid? (Your existing logic is good)
        if (inbuiltFile.exists()) {
            Log.d("MainActivity_Copy", "Inbuilt file ${inbuiltFile.name} already exists. Validating...")
            if (isValidWavFile(inbuiltFile)) {
                Log.d("MainActivity_Copy", "Inbuilt mantra $inbuiltMantraName already exists and is valid. No copy needed.")
                return
            } else {
                Log.w("MainActivity_Copy", "Inbuilt file ${inbuiltFile.name} exists but is INVALID. Will attempt to overwrite.")
                // Optionally delete it first to ensure a clean copy, though FileOutputStream should overwrite
                // inbuiltFile.delete()
            }
        } else {
            Log.d("MainActivity_Copy", "Inbuilt file ${inbuiltFile.name} does not exist. Proceeding with copy.")
        }

        var assetInputStream: InputStream? = null
        var fileOutputStream: FileOutputStream? = null
        try {
            Log.d("MainActivity_Copy", "Attempting to open asset: 'testhello.wav'")
            // Verify asset exists by listing (more robust check)
            val assetFiles = assets.list("") // Lists files/dirs in root of assets
            val assetPathForTestHello = "testhello.wav" // Assuming it's directly in assets root

            if (assetFiles == null || !assetFiles.contains(assetPathForTestHello)) {
                Log.e("MainActivity_Copy", "Asset '$assetPathForTestHello' NOT FOUND in assets root. Listing: ${assetFiles?.joinToString()}")
                // Also check subdirectories if it might be there, e.g., assets.list("sounds")
                // For now, assuming it's in the root.
                runOnUiThread {
                    Toast.makeText(this, "Error: Inbuilt mantra source file missing from app package.", Toast.LENGTH_LONG).show()
                }
                return // Critical error, cannot copy
            }
            Log.d("MainActivity_Copy", "Asset '$assetPathForTestHello' confirmed in assets listing.")

            assetInputStream = assets.open(assetPathForTestHello) // Or just "testhello.wav"
            Log.d("MainActivity_Copy", "Asset 'testhello.wav' opened successfully.")

            fileOutputStream = FileOutputStream(inbuiltFile) // This will create the file if it doesn't exist, or overwrite
            Log.d("MainActivity_Copy", "FileOutputStream for ${inbuiltFile.name} opened.")

            assetInputStream.copyTo(fileOutputStream)
            Log.d("MainActivity_Copy", "Finished copying asset to ${inbuiltFile.absolutePath}. File size: ${inbuiltFile.length()}")

            if (!isValidWavFile(inbuiltFile)) {
                Log.e("MainActivity_Copy", "Copied inbuilt mantra is INVALID: ${inbuiltFile.name}. Deleting.")
                inbuiltFile.delete() // Clean up invalid file
                throw IOException("Invalid WAV format for inbuilt mantra after copy.")
            }
            Log.d("MainActivity_Copy", "Copied inbuilt mantra ${inbuiltFile.name} is VALID.")

        } catch (e: FileNotFoundException) { // Specifically for assets.open()
            Log.e("MainActivity_Copy", "Failed to copy inbuilt mantra: ASSET 'testhello.wav' NOT FOUND.", e)
            runOnUiThread {
                Toast.makeText(this, "Failed to load inbuilt mantra: Source file missing.", Toast.LENGTH_LONG).show()
            }
        } catch (e: IOException) {
            Log.e("MainActivity_Copy", "Failed to copy inbuilt mantra due to IOException: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Failed to load inbuilt mantra: I/O error.", Toast.LENGTH_LONG).show()
            }
            // Clean up potentially partially written file
            if (inbuiltFile.exists()) {
                inbuiltFile.delete()
            }
        } catch (e: Exception) {
            Log.e("MainActivity_Copy", "Unexpected error copying inbuilt mantra: ${e.message}", e)
            runOnUiThread {
                Toast.makeText(this, "Failed to load inbuilt mantra: ${e.message}", Toast.LENGTH_LONG).show()
            }
            if (inbuiltFile.exists()) {
                inbuiltFile.delete()
            }
        } finally {
            try {
                assetInputStream?.close()
            } catch (ioe: IOException) {
                Log.w("MainActivity_Copy", "Error closing asset input stream", ioe)
            }
            try {
                fileOutputStream?.close()
            } catch (ioe: IOException) {
                Log.w("MainActivity_Copy", "Error closing file output stream", ioe)
            }
            Log.d("MainActivity_Copy", "copyInbuiltMantraToStorage finished execution.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListeningWithDelay() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("MainActivity", "Microphone permission missing")
            Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_SHORT).show()
            isRecognizingMantra = false
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isRecognizingMantra) {
                Log.d("MainActivity", "Recognition cancelled before start")
                return@postDelayed
            }

            if (actualRecordingBufferSize <= 0 || actualRecordingBufferSize % 2 != 0) {
                Log.e("MainActivity", "Invalid AudioRecord buffer size: $actualRecordingBufferSize")
                Toast.makeText(this, "Invalid audio buffer configuration.", Toast.LENGTH_SHORT).show()
                isRecognizingMantra = false
                return@postDelayed
            }

            try {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    audioChannelConfig,
                    audioFormatEncoding,
                    actualRecordingBufferSize
                )
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e("MainActivity", "AudioRecord initialization failed. State: ${audioRecord?.state}")
                    Toast.makeText(this, "Failed to initialize audio recording.", Toast.LENGTH_SHORT).show()
                    isRecognizingMantra = false
                    audioRecord?.release()
                    audioRecord = null
                    return@postDelayed
                }

                Log.d("MainActivity", "AudioRecord initialized: sampleRate=$sampleRate, bufferSize=$actualRecordingBufferSize, state=${audioRecord?.state}")

                val tarsosDspAudioFormat = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                val audioStream = AndroidAudioInputStream(audioRecord!!, tarsosDspAudioFormat)
                Log.d("MainActivity", "AndroidAudioInputStream initialized")

                Log.d("MainActivity", "Audio stream frame length: ${audioStream.frameLength}")

                audioDispatcher = AudioDispatcher(
                    audioStream,
                    tarsosProcessingBufferSizeSamples,
                    tarsosProcessingOverlapSamples
                )

                val mfcc = MFCC(
                    tarsosProcessingBufferSizeSamples,
                    sampleRate.toFloat(),
                    13,
                    40,
                    20f,
                    4000f
                )
                audioDispatcher?.addAudioProcessor(mfcc)
                audioDispatcher?.addAudioProcessor(object : AudioProcessor {
                    override fun process(audioEvent: AudioEvent): Boolean {
                        Log.d("MainActivity", "AudioEvent: bufferSize=${audioEvent.bufferSize}, byteBuffer=${audioEvent.byteBuffer.size}")
                        val mfccs = mfcc.mfcc
                        if (mfccs.isEmpty() || mfccs.size != 13) {
                            Log.w("MainActivity", "Invalid MFCCs: size=${mfccs.size}")
                            return true
                        }
                        lastProcessedAudio = mfccs.copyOf()
                        if (targetMantra.isNotEmpty() && referenceMFCCs.containsKey(targetMantra)) {
                            val refMfccList = referenceMFCCs[targetMantra]
                            if (refMfccList != null && refMfccList.isNotEmpty()) {
                                val similarity = calculateCosineSimilarity(mfccs, refMfccList[0])
                                if (similarity > 0.9) {
                                    val currentCount = matchCount.incrementAndGet()
                                    Log.d("MainActivity", "Match detected, count: $currentCount, Similarity: $similarity")
                                    if (currentCount >= matchLimit) {
                                        runOnUiThread { triggerAlarm() }
                                    }
                                }
                            } else {
                                Log.w("MainActivity", "No reference MFCCs for $targetMantra")
                            }
                        }
                        return true
                    }
                    override fun processingFinished() {
                        Log.d("MainActivity", "AudioProcessor finished")
                    }
                })

                audioRecord?.startRecording()
                if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.e("MainActivity", "AudioRecord failed to start recording. State: ${audioRecord?.recordingState}")
                    throw IllegalStateException("AudioRecord not recording")
                }

                dispatcherThread = Thread {
                    try {
                        Log.d("MainActivity", "AudioDispatcher starting with buffer: $tarsosProcessingBufferSizeSamples, overlap: $tarsosProcessingOverlapSamples")
                        audioDispatcher?.run()
                        Log.d("MainActivity", "AudioDispatcher completed normally")
                    } catch (e: ArrayIndexOutOfBoundsException) {
                        Log.e("MainActivity", "Buffer error in AudioDispatcher", e)
                        runOnUiThread {
                            isRecognizingMantra = false
                            Toast.makeText(this@MainActivity, "Audio processing failed: buffer mismatch.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: IllegalStateException) {
                        Log.e("MainActivity", "AudioDispatcher failed due to illegal state", e)
                        runOnUiThread {
                            isRecognizingMantra = false
                            Toast.makeText(this@MainActivity, "Audio processing failed: invalid state.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Unexpected error in AudioDispatcher thread", e)
                        runOnUiThread {
                            isRecognizingMantra = false
                            Toast.makeText(this@MainActivity, "Error processing audio: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } finally {
                        stopListening()
                    }
                }
                dispatcherThread?.name = "AudioDispatcherThread"
                dispatcherThread?.start()
                Log.d("MainActivity", "Started listening.")
            } catch (e: Exception) {
                Log.e("MainActivity", "Error initializing AudioDispatcher", e)
                audioRecord?.release()
                audioRecord = null
                isRecognizingMantra = false
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to start listening: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }, 500)
    }

    private fun stopListening() {
        if (!isRecognizingMantra && audioRecord == null && audioDispatcher == null && dispatcherThread == null) {
            Log.d("MainActivity", "StopListening called but nothing to stop.")
            return
        }
        isRecognizingMantra = false
        Log.d("MainActivity", "Stopping listening...")

        dispatcherThread?.interrupt()
        try {
            dispatcherThread?.join(1000)
            Log.d("MainActivity", "Dispatcher thread joined")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e("MainActivity", "Interrupted while joining dispatcher thread", e)
        }
        dispatcherThread = null

        audioDispatcher?.stop()
        audioDispatcher = null

        if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            try {
                audioRecord?.stop()
                Log.d("MainActivity", "AudioRecord stopped")
            } catch (e: IllegalStateException) {
                Log.e("MainActivity", "Failed to stop AudioRecord", e)
            }
        }
        audioRecord?.release()
        audioRecord = null
        Log.d("MainActivity", "Stopped listening completely.")
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showPermissionAlert() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Microphone permission is required to record and analyze mantras. Please grant the permission in app settings.")
            .setPositiveButton("OK", null)
            .setNegativeButton("Settings") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun recordMantra(mantraName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required for recording.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isRecognizingMantra || isRecordingMantra) {
            Toast.makeText(this, "Already processing audio, please stop first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (mantraName.isBlank() || mantraName == inbuiltMantraName) {
            Toast.makeText(this, if (mantraName.isBlank()) "Mantra name cannot be empty." else "Cannot use inbuilt mantra name '$inbuiltMantraName'.", Toast.LENGTH_SHORT).show()
            return
        }

        val sanitizedMantraName = mantraName.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        var file = File(storageDir, "$sanitizedMantraName.wav")
        var uniqueFileName = sanitizedMantraName
        var counter = 1
        while (file.exists()) {
            uniqueFileName = "${sanitizedMantraName}_$counter"
            file = File(storageDir, "$uniqueFileName.wav")
            counter++
        }

        if (actualRecordingBufferSize <= 0) {
            Log.e("MainActivity", "Invalid AudioRecord buffer size: $actualRecordingBufferSize")
            Toast.makeText(this, "Cannot record: Invalid buffer size.", Toast.LENGTH_SHORT).show()
            return
        }

        var localAudioRecord: AudioRecord? = null
        try {
            localAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                audioChannelConfig,
                audioFormatEncoding,
                actualRecordingBufferSize
            )
            if (localAudioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MainActivity", "Failed to initialize AudioRecord for mantra. State: ${localAudioRecord.state}")
                Toast.makeText(this, "Failed to initialize audio recording.", Toast.LENGTH_SHORT).show()
                localAudioRecord.release()
                return
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Exception initializing AudioRecord for mantra recording", e)
            Toast.makeText(this, "Error initializing recording.", Toast.LENGTH_SHORT).show()
            localAudioRecord?.release()
            return
        }

        isRecordingMantra = true
        stopRecordingFlag.set(false)
        var outputStream: FileOutputStream? = null
        var localAudioDispatcher: AudioDispatcher?

        try {
            outputStream = FileOutputStream(file)
            writeWavHeader(outputStream, channels = 2, sampleRate = sampleRate, bitsPerSample = 16)

            val tarsosDspAudioFormat = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val audioStream = AndroidAudioInputStream(localAudioRecord, tarsosDspAudioFormat)
            Log.d("MainActivity", "AndroidAudioInputStream initialized for recording")

            localAudioDispatcher = AudioDispatcher(
                audioStream,
                tarsosProcessingBufferSizeSamples,
                0
            )

            val currentAudioRecord = localAudioRecord
            localAudioDispatcher.addAudioProcessor(object : AudioProcessor {
                override fun process(audioEvent: AudioEvent): Boolean {
                    if (stopRecordingFlag.get()) return false
                    try {
                        outputStream.write(audioEvent.byteBuffer, 0, audioEvent.byteBuffer.size)
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error writing to WAV file", e)
                        currentAudioRecord.stop()
                        localAudioDispatcher.stop()
                        return false
                    }
                    return true
                }
                override fun processingFinished() {}
            })

            currentAudioRecord.startRecording()
            recordingThread = Thread({
                try {
                    Log.d("MainActivity", "AudioDispatcher run (recording) started with buffer: $tarsosProcessingBufferSizeSamples, overlap: 0")
                    localAudioDispatcher.run()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error in recording dispatcher thread", e)
                } finally {
                    try {
                        currentAudioRecord.stop()
                    } catch (e: IllegalStateException) {
                        Log.e("MainActivity", "Error stopping AudioRecord in recording thread", e)
                    }
                    currentAudioRecord.release()

                    val recordedDataSize = file.length() - 44
                    try {
                        outputStream.channel?.let { channel ->
                            if (recordedDataSize > 0) {
                                val chunkSize = recordedDataSize + 36
                                channel.position(4)
                                channel.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkSize.toInt()))
                                channel.position(40)
                                channel.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(recordedDataSize.toInt()))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("MainActivity", "Error updating WAV header size", e)
                    } finally {
                        outputStream.close()
                    }

                    runOnUiThread {
                        if (recordedDataSize > 0) {
                            Toast.makeText(this@MainActivity, "Mantra recorded: $uniqueFileName", Toast.LENGTH_SHORT).show()
                            loadReferenceMFCCs()
                        } else {
                            if (file.exists()) file.delete()
                            Toast.makeText(this@MainActivity, "Recording failed or was empty.", Toast.LENGTH_SHORT).show()
                        }
                        isRecordingMantra = false
                    }
                }
            }, "MantraRecordingThread")
            recordingThread?.start()
            Log.d("MainActivity", "Started mantra recording to file: ${file.name}")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error during mantra recording setup or execution", e)
            Toast.makeText(this, "Error starting recording.", Toast.LENGTH_SHORT).show()
            localAudioRecord.release()
            outputStream?.close()
            isRecordingMantra = false
            if (file.exists() && file.length() == 44L) {
                file.delete()
            }
        }
    }


    private fun stopRecordingMantra() {
        if (isRecordingMantra) {
            isRecordingMantra = false
            stopRecordingFlag.set(true)
            recordingThread?.interrupt()
            recordingThread = null
            Log.d("MainActivity", "Stopped recording mantra.")
        }
    }

    private fun deleteMantra(mantraName: String) {
        if (mantraName == inbuiltMantraName) {
            Toast.makeText(this, "Cannot delete inbuilt mantra '$inbuiltMantraName'.", Toast.LENGTH_SHORT).show()
            return
        }
        if (mantraName.isBlank()) {
            Toast.makeText(this, "No mantra selected to delete.", Toast.LENGTH_SHORT).show()
            return
        }

        val file = File(storageDir, "$mantraName.wav")
        if (!file.exists()) {
            Toast.makeText(this, "Mantra file not found.", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            if (file.delete()) {
                Toast.makeText(this, "Mantra '$mantraName' deleted.", Toast.LENGTH_SHORT).show()
                loadReferenceMFCCs()
            } else {
                Toast.makeText(this, "Failed to delete mantra '$mantraName'.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error deleting mantra $mantraName", e)
            Toast.makeText(this, "Error deleting mantra '$mantraName'.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun writeWavHeader(outputStream: FileOutputStream, channels: Int, sampleRate: Int, bitsPerSample: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = 0; header[5] = 0; header[6] = 0; header[7] = 0
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = blockAlign.toByte(); header[33] = 0
        header[34] = bitsPerSample.toByte(); header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = 0; header[41] = 0; header[42] = 0; header[43] = 0
        outputStream.write(header)
    }

    // This is an EXAMPLE of what your isValidWavFile might look like.
// You need to show me YOUR ACTUAL isValidWavFile function.
    private fun isValidWavFile(file: File): Boolean {
        if (!file.exists() || file.length() < 44) { // Basic check for existence and minimum header size
            Log.w("MainActivity", "File ${file.name} does not exist or too small to be WAV.")
            return false
        }
        var fis: FileInputStream? = null
        try {
            fis = FileInputStream(file)
            val header = ByteArray(44)
            val bytesRead = fis.read(header, 0, 44)

            if (bytesRead < 44) {
                Log.w("MainActivity", "Could not read full 44-byte WAV header from ${file.name}.")
                return false
            }

            // --- COMMON VALIDATION POINTS ---

            // 1. "RIFF" chunk descriptor (Bytes 0-3)
            if (!(header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                        header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte())) {
                Log.w("MainActivity", "Invalid WAV file: ${file.name}, missing 'RIFF' marker. Found: ${header.sliceArray(0..3).map { it.toInt().toChar() }.joinToString("")}")
                return false
            }

            // 2. "WAVE" format (Bytes 8-11)
            if (!(header[8] == 'W'.code.toByte() && header[9] == 'A'.code.toByte() &&
                        header[10] == 'V'.code.toByte() && header[11] == 'E'.code.toByte())) {
                Log.w("MainActivity", "Invalid WAV file: ${file.name}, missing 'WAVE' marker. Found: ${header.sliceArray(8..11).map { it.toInt().toChar() }.joinToString("")}")
                return false
            }

            // 3. "fmt " sub-chunk (Bytes 12-15)
            if (!(header[12] == 'f'.code.toByte() && header[13] == 'm'.code.toByte() &&
                        header[14] == 't'.code.toByte() && header[15] == ' '.code.toByte())) {
                Log.w("MainActivity", "Invalid WAV file: ${file.name}, missing 'fmt ' marker. Found: ${header.sliceArray(12..15).map { it.toInt().toChar() }.joinToString("")}")
                return false
            }

            // 4. AudioFormat (PCM = 1) (Bytes 20-21)
            val audioFormat = ByteBuffer.wrap(header, 20, 2).order(ByteOrder.LITTLE_ENDIAN).short
            if (audioFormat.toInt() != 1) { // 1 means PCM
                Log.w("MainActivity", "Invalid WAV file: ${file.name}, unsupported audio format: $audioFormat (expected 1 for PCM).")
                return false
            }

            // 5. NumChannels (e.g., Mono = 1) (Bytes 22-23)
            val numChannels = ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short
            // if (numChannels.toInt() != 1) { // Your app expects MONO
            //     Log.w("MainActivity", "Invalid WAV file: ${file.name}, unexpected number of channels: $numChannels (expected 1).")
            //     return false
            // }
            Log.d("MainActivity_WAV_Validate", "File: ${file.name}, Channels: $numChannels")


            // 6. SampleRate (e.g., 16000 Hz) (Bytes 24-27)
            val fileSampleRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            // if (fileSampleRate != this.sampleRate) { // this.sampleRate is your target 16000
            //     Log.w("MainActivity", "Invalid WAV file: ${file.name}, unexpected sample rate: $fileSampleRate (expected ${this.sampleRate}).")
            //     return false
            // }
            Log.d("MainActivity_WAV_Validate", "File: ${file.name}, Sample Rate: $fileSampleRate")

            // 7. BitsPerSample (e.g., 16) (Bytes 34-35)
            val bitsPerSample = ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN).short
            // if (bitsPerSample.toInt() != 16) { // Your app expects 16-bit
            //     Log.w("MainActivity", "Invalid WAV file: ${file.name}, unexpected bits per sample: $bitsPerSample (expected 16).")
            //     return false
            // }
            Log.d("MainActivity_WAV_Validate", "File: ${file.name}, BitsPerSample: $bitsPerSample")


            // 8. "data" sub-chunk (Bytes 36-39)
            // This can sometimes be other chunks like "LIST" before "data"
            // A more robust check would scan for "data"
            var dataChunkOffset = 36
            while (dataChunkOffset < header.size - 4) {
                if (header[dataChunkOffset] == 'd'.code.toByte() && header[dataChunkOffset+1] == 'a'.code.toByte() &&
                    header[dataChunkOffset+2] == 't'.code.toByte() && header[dataChunkOffset+3] == 'a'.code.toByte()) {
                    Log.d("MainActivity_WAV_Validate", "Found 'data' chunk at offset $dataChunkOffset for ${file.name}")
                    break // Found it
                }
                // If not 'data', skip this chunk: read its size (4 bytes) and advance
                if (dataChunkOffset + 8 > header.size) break // Not enough space to read size
                val chunkSize = ByteBuffer.wrap(header, dataChunkOffset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                dataChunkOffset += (8 + chunkSize)
                // Add padding if chunk size is odd
                if (chunkSize % 2 != 0) {
                    dataChunkOffset++
                }
            }
            if (dataChunkOffset >= header.size -4 ) { // or a better check if not found
                Log.w("MainActivity", "Invalid WAV file: ${file.name}, 'data' chunk not found where expected.")
                // return false // Be careful with this check, data chunk is not always at byte 36
            }


            Log.i("MainActivity", "WAV file ${file.name} passed header validation.")
            return true

        } catch (e: Exception) {
            Log.e("MainActivity", "Error validating WAV file ${file.name}: ${e.message}", e)
            return false
        } finally {
            try {
                fis?.close()
            } catch (_: IOException) {
                // ignore
            }
        }
    }


    private fun List<FloatArray>.averageMfcc(): FloatArray {
        if (isEmpty()) return FloatArray(13)
        val result = FloatArray(first().size)
        for (mfcc in this) {
            for (i in mfcc.indices) {
                result[i] += mfcc[i] / size
            }
        }
        return result
    }

    private fun loadReferenceMFCCs() {
        Log.d("MainActivity", "Loading reference MFCCs...")
        val tempReferenceMFCCs = mutableMapOf<String, List<FloatArray>>()

        savedMantras.forEach { mantraName ->
            val file = File(storageDir, "$mantraName.wav")
            if (!file.exists() || file.length() <= 44 || !isValidWavFile(file)) {
                Log.w("MainActivity", "Skipping $mantraName: file missing, too small, or invalid")
                return@forEach
            }

            var inputStream: FileInputStream? = null
            var dispatcher: AudioDispatcher? = null
            try {
                Log.d("MainActivity", "Processing reference: $mantraName, fileSize=${file.length()}")
                inputStream = FileInputStream(file)

                if (inputStream.skip(44L) != 44L) {
                    Log.e("MainActivity", "Could not skip WAV header for $mantraName")
                    return@forEach
                }

                val audioFormat = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                val mfccList = mutableListOf<FloatArray>()
                val mfccProcessor = MFCC(
                    tarsosProcessingBufferSizeSamples,
                    sampleRate.toFloat(),
                    13,
                    40,
                    20f,
                    4000f
                )

                val customAudioStream = object : TarsosDSPAudioInputStream {
                    override fun getFormat(): TarsosDSPAudioFormat = audioFormat
                    override fun read(b: ByteArray, off: Int, len: Int): Int = inputStream.read(b, off, len)
                    override fun skip(n: Long): Long = 0 // Header already skipped
                    override fun close() = inputStream.close()
                    override fun getFrameLength(): Long = (file.length() - 44) / 2 // 16-bit mono
                }

                dispatcher = AudioDispatcher(customAudioStream, tarsosProcessingBufferSizeSamples, tarsosProcessingOverlapSamples)
                dispatcher.addAudioProcessor(mfccProcessor)
                dispatcher.addAudioProcessor(object : AudioProcessor {
                    override fun process(audioEvent: AudioEvent): Boolean {
                        val mfccs = mfccProcessor.mfcc
                        if (mfccs.isEmpty() || mfccs.size != 13) {
                            Log.w("MainActivity", "Invalid MFCCs for $mantraName: size=${mfccs.size}")
                            return true
                        }
                        mfccList.add(mfccs.copyOf())
                        return true
                    }
                    override fun processingFinished() {}
                })

                dispatcher.run()

                if (mfccList.isNotEmpty()) {
                    tempReferenceMFCCs[mantraName] = listOf(mfccList.averageMfcc())
                    Log.d("MainActivity", "Loaded ${mfccList.size} MFCC frames for $mantraName, stored averaged")
                } else {
                    Log.w("MainActivity", "No MFCC frames extracted for $mantraName")
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error loading MFCC for $mantraName", e)
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to process mantra: $mantraName", Toast.LENGTH_SHORT).show()
                }
            } finally {
                inputStream?.close()
                dispatcher?.stop()
            }
        }

        referenceMFCCs = tempReferenceMFCCs.filterValues { it.isNotEmpty() }
        Log.d("MainActivity", "Finished loading ${referenceMFCCs.size} reference MFCCs.")
    }

    private fun calculateCosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        if (vec1.isEmpty() || vec2.isEmpty() || vec1.size != vec2.size) {
            Log.w("CosineSimilarity", "Vectors are empty or have different sizes. vec1: ${vec1.size}, vec2: ${vec2.size}")
            return 0f
        }
        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i].pow(2)
            norm2 += vec2[i].pow(2)
        }
        val denominator = sqrt(norm1) * sqrt(norm2)
        return if (denominator == 0f) 0f else dotProduct / denominator
    }

    @SuppressLint("MissingPermission")
    private fun triggerAlarm() {
        Log.d("MainActivity", "Triggering alarm. Match limit reached.")
        val wasRecognizing = isRecognizingMantra
        if (wasRecognizing) {
            stopListening()
        }

        val toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
        Handler(Looper.getMainLooper()).postDelayed({ toneGenerator.release() }, 500)

        AlertDialog.Builder(this)
            .setTitle("Mantra Limit Reached")
            .setMessage("The target number of mantra recitations ($matchLimit) has been reached.")
            .setPositiveButton("OK & Continue") { _, _ ->
                matchCount.set(0)
                if (targetMantra.isNotBlank() && matchLimit > 0) {
                    isRecognizingMantra = true
                    startListeningWithDelay()
                } else {
                    isRecognizingMantra = false
                }
            }
            .setNegativeButton("Stop") { _, _ ->
                matchCount.set(0)
            }
            .setCancelable(false)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (isRecognizingMantra && audioRecord != null && audioDispatcher != null) {
            Log.d("MainActivity", "onPause: Stopping listening (recognition) due to activity pause.")
            stopListening()
        }
        if (isRecordingMantra) {
            Log.d("MainActivity", "onPause: Stopping recording due to activity pause.")
            stopRecordingMantra()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy: Ensuring everything is stopped and released.")
        stopListening()
        stopRecordingMantra()
    }
}