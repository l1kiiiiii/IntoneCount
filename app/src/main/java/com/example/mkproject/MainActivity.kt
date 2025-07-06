package com.example.mkproject

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
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
import be.tarsos.dsp.io.android.AndroidAudioInputStream
import be.tarsos.dsp.mfcc.MFCC
import be.tarsos.dsp.io.TarsosDSPAudioFormat
import com.example.mkproject.ui.theme.MainScreen
import com.example.mkproject.ui.theme.MkprojectTheme
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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
    private var recordingThread: Thread? = null // For recordMantra
    private val stopRecordingFlag = AtomicBoolean(false) // To stop recording

    // Constants for audio processing
    private val sampleRate = 16000
    private val recordingBufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
    }

    // Storage and permissions
    private val storageDir: File by lazy { File(filesDir, "mantras").also { if (!it.exists()) it.mkdirs() } }
    private val savedMantras: List<String>
        get() = storageDir.listFiles()?.filter { it.extension == "wav" }?.map { it.nameWithoutExtension } ?: emptyList()
    private val recordAudioLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted) showPermissionAlert()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MkprojectTheme {
                var matchLimitText by remember { mutableStateOf("") }
                MainScreen(
                    mantras = savedMantras,
                    matchLimitText = matchLimitText,
                    onMatchLimitTextChange = { matchLimitText = it },
                    onRecordMantraClick = { recordMantra() },
                    onStartStopClick = { targetMantraName, _ ->
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
                    onStopRecordingClick = { stopRecordingMantra() }, // New callback for stopping recording
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

    @SuppressLint("MissingPermission")
    private fun startListeningWithDelay() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_SHORT).show()
            isRecognizingMantra = false
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isRecognizingMantra) return@postDelayed

            if (recordingBufferSize <= 0) {
                Log.e("MainActivity", "Invalid buffer size for recording: $recordingBufferSize")
                Toast.makeText(this, "Invalid buffer size for recording.", Toast.LENGTH_SHORT).show()
                isRecognizingMantra = false
                return@postDelayed
            }
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordingBufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MainActivity", "Failed to initialize audio recording. State: ${audioRecord?.state}")
                Toast.makeText(this, "Failed to initialize audio recording.", Toast.LENGTH_SHORT).show()
                isRecognizingMantra = false
                audioRecord?.release()
                audioRecord = null
                return@postDelayed
            }
            val tarsosDspAudioFormat = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val audioStream = AndroidAudioInputStream(audioRecord!!, tarsosDspAudioFormat)
            audioDispatcher = AudioDispatcher(audioStream, recordingBufferSize / 2, recordingBufferSize / 4)
            val mfcc = MFCC(
                sampleRate,
                (recordingBufferSize / 2).toFloat(),
                13,
                40,
                20f,
                4000f
            )
            audioDispatcher?.addAudioProcessor(mfcc)
            audioDispatcher?.addAudioProcessor(object : be.tarsos.dsp.AudioProcessor {
                override fun process(audioEvent: AudioEvent): Boolean {
                    val mfccs = mfcc.mfcc
                    lastProcessedAudio = mfccs.copyOf()
                    if (targetMantra.isNotEmpty() && referenceMFCCs.containsKey(targetMantra)) {
                        val refMfccList = referenceMFCCs[targetMantra]
                        if (refMfccList != null && refMfccList.isNotEmpty()) {
                            val similarity = calculateCosineSimilarity(mfccs, refMfccList[0])
                            if (similarity > 0.9) {
                                val currentCount = matchCount.incrementAndGet()
                                Log.d(
                                    "MainActivity",
                                    "Match detected, count: $currentCount, Similarity: $similarity"
                                )
                                if (currentCount >= matchLimit) {
                                    runOnUiThread { triggerAlarm() }
                                }
                            }
                        }
                    }
                    return true
                }
                override fun processingFinished() {}
            })
            audioRecord?.startRecording()
            dispatcherThread = Thread {
                try {
                    audioDispatcher?.run()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error in AudioDispatcher thread", e)
                    runOnUiThread {
                        isRecognizingMantra = false
                    }
                }
            }
            dispatcherThread?.name = "AudioDispatcherThread"
            dispatcherThread?.start()
            Log.d("MainActivity", "Started listening.")
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
            dispatcherThread?.join(500)
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
                val intent = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun recordMantra() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission required for recording.", Toast.LENGTH_SHORT).show()
            return
        }

        if (isRecognizingMantra || isRecordingMantra) {
            Toast.makeText(this, "Already processing audio, please stop first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (recordingBufferSize <= 0) {
            Log.e("MainActivity", "Invalid buffer size for recording: $recordingBufferSize")
            Toast.makeText(this, "Cannot record: Invalid buffer size.", Toast.LENGTH_SHORT).show()
            return
        }

        var localAudioRecord: AudioRecord? = null
        try {
            localAudioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                recordingBufferSize
            )
            if (localAudioRecord.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("MainActivity", "Failed to initialize audio recording for mantra. State: ${localAudioRecord.state}")
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
        val file = File(storageDir, "mantra_${System.currentTimeMillis()}.wav")
        var outputStream: FileOutputStream? = null
        var localAudioDispatcher: AudioDispatcher? = null

        try {
            outputStream = FileOutputStream(file)
            writeWavHeader(outputStream, channels = 1, sampleRate = sampleRate, bitsPerSample = 16)

            val tarsosDspAudioFormat = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val audioStream = AndroidAudioInputStream(localAudioRecord, tarsosDspAudioFormat)
            localAudioDispatcher = AudioDispatcher(audioStream, recordingBufferSize / 2, 0)

            val currentAudioRecord = localAudioRecord
            localAudioDispatcher.addAudioProcessor(object : be.tarsos.dsp.AudioProcessor {
                override fun process(audioEvent: AudioEvent): Boolean {
                    if (stopRecordingFlag.get()) return false // Stop processing if flag is set
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
                        outputStream?.close()
                    }

                    runOnUiThread {
                        if (recordedDataSize > 0) {
                            Toast.makeText(this@MainActivity, "Mantra recorded: ${file.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
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
            localAudioRecord?.release()
            outputStream?.close()
            isRecordingMantra = false
            if (file.exists() && file.length() == 44L) {
                file.delete()
            }
        }
    }

    private fun stopRecordingMantra() {
        if (!isRecordingMantra) {
            Log.d("MainActivity", "StopRecordingMantra called but not recording.")
            return
        }
        stopRecordingFlag.set(true)
        recordingThread?.interrupt()
        try {
            recordingThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e("MainActivity", "Interrupted while joining recording thread", e)
        }
        recordingThread = null
        Log.d("MainActivity", "Stopped recording mantra.")
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

    private fun isValidWavFile(file: File): Boolean {
        FileInputStream(file).use { input ->
            val header = ByteArray(44)
            if (input.read(header) != 44) return false
            return header[0] == 'R'.code.toByte() &&
                    header[8] == 'W'.code.toByte() &&
                    header[20] == 1.toByte() && // PCM format
                    header[22] == 1.toByte() && // Mono
                    header[24] == (sampleRate and 0xff).toByte() // Sample rate
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
                Log.w("MainActivity", "Invalid WAV file: $mantraName.wav")
                return@forEach
            }

            var inputStream: FileInputStream? = null
            var dispatcher: AudioDispatcher? = null
            try {
                Log.d("MainActivity", "Processing reference: $mantraName")
                inputStream = FileInputStream(file)

                if (inputStream.skip(44L) != 44L) {
                    Log.e("MainActivity", "Could not skip WAV header for $mantraName")
                    return@forEach
                }

                val audioFormat = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                val mfccList = mutableListOf<FloatArray>()
                val mfccProcessor = MFCC(
                    sampleRate,
                    (recordingBufferSize / 2).toFloat(),
                    13,
                    40,
                    20f,
                    4000f
                )

                val customAudioStream = object : be.tarsos.dsp.io.TarsosDSPAudioInputStream {
                    override fun getFormat(): TarsosDSPAudioFormat = audioFormat
                    override fun read(b: ByteArray, off: Int, len: Int): Int = inputStream.read(b, off, len)
                    override fun skip(n: Long): Long = 0 // Header already skipped
                    override fun close() = inputStream.close()
                    override fun getFrameLength(): Long = (file.length() - 44) / 2 // 16-bit mono
                }

                dispatcher = AudioDispatcher(customAudioStream, recordingBufferSize / 2, recordingBufferSize / 4)
                dispatcher.addAudioProcessor(mfccProcessor)
                dispatcher.addAudioProcessor(object : be.tarsos.dsp.AudioProcessor {
                    override fun process(audioEvent: AudioEvent): Boolean {
                        mfccList.add(mfccProcessor.mfcc.copyOf())
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

        val toneGenerator = ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
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