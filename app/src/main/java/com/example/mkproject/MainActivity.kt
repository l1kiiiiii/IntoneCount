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
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    // App logic variables
    private var isListening by mutableStateOf(false)
    private var matchCount by mutableIntStateOf(0)
    private var matchLimit by mutableIntStateOf(0)
    private var targetMantra by mutableStateOf("")
    private var lastProcessedAudio: FloatArray? = null
    private var referenceMFCCs: Map<String, List<FloatArray>> = emptyMap()

    // Audio processing variables
    private var audioRecord: AudioRecord? = null
    private var audioDispatcher: AudioDispatcher? = null
    private var dispatcherThread: Thread? = null

    // Constants for audio processing
    private val sampleRate = 16000
    private val recordingBufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
    }
    private val processingBufferSize = 2048 // Common buffer size for TarsosDSP processing

    // Storage and permissions
    private val storageDir: File by lazy { File(filesDir, "mantras").also { if (!it.exists()) it.mkdirs() } }
    private val savedMantras: List<String>
        get() = storageDir.listFiles()?.filter { it.extension == "wav" }?.map { it.nameWithoutExtension }
            ?: emptyList()
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
                    onMatchLimitTextChange = { matchLimitText = it }, // Updates matchLimitText state
                    onRecordMantraClick = { recordMantra() },
                    onStartStopClick = { targetMantraName, _ ->
                        if (isListening) {
                            stopListening()
                        } else {
                            if (targetMantraName.isBlank()) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Please select a recorded mantra.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@MainScreen
                            }
                            matchLimit = matchLimitText.toIntOrNull() ?: 0
                            if (matchLimit <= 0) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Please enter a valid match limit.",
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@MainScreen
                            }
                            targetMantra = targetMantraName
                            matchCount = 0
                            isListening = true
                            startListeningWithDelay()
                        }
                    },
                    matchCount = matchCount,
                    processingStatus = if (isListening) "Listening for mantra..." else "Stopped"
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
            isListening = false
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!isListening) return@postDelayed

            if (recordingBufferSize <= 0) {
                Log.e("MainActivity", "Invalid buffer size for recording: $recordingBufferSize")
                Toast.makeText(this, "Invalid buffer size for recording.", Toast.LENGTH_SHORT).show()
                isListening = false
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
                isListening = false
                return@postDelayed
            }
            val audioFormat = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false) // 16-bit, mono
            val audioStream = AndroidAudioInputStream(audioRecord!!, audioFormat, recordingBufferSize)
            audioDispatcher = AudioDispatcher(audioStream, recordingBufferSize, recordingBufferSize / 2)
            val mfcc = MFCC(
                sampleRate.toFloat().toInt(),
                recordingBufferSize, // Use Int for bufferSize as per TarsosDSP API
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
                                matchCount++
                                Log.d("MainActivity", "Match detected, count: $matchCount, Similarity: $similarity")
                                if (matchCount >= matchLimit) {
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
                    runOnUiThread { isListening = false }
                }
            }
            dispatcherThread?.start()
            Log.d("MainActivity", "Started listening.")
        }, 500)
    }

    private fun stopListening() {
        if (!isListening && audioRecord == null && audioDispatcher == null) return
        isListening = false
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
            audioRecord?.stop()
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

        if (isListening) {
            Toast.makeText(this, "Already processing, please stop first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (recordingBufferSize <= 0) {
            Log.e("MainActivity", "Invalid buffer size for recording: $recordingBufferSize")
            Toast.makeText(this, "Cannot record: Invalid buffer size.", Toast.LENGTH_SHORT).show()
            return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            recordingBufferSize
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("MainActivity", "Failed to initialize audio recording for mantra. State: ${audioRecord?.state}")
            Toast.makeText(this, "Failed to initialize audio recording.", Toast.LENGTH_SHORT).show()
            audioRecord = null
            return
        }

        isListening = true
        val file = File(storageDir, "mantra_${System.currentTimeMillis()}.wav")
        val outputStream = FileOutputStream(file)

        // Basic WAV header (simplified, assumes 16-bit PCM mono)
        val header = ByteArray(44)
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        // RIFF chunk
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        // ChunkSize (file size - 8) - updated later
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        // fmt sub-chunk
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
        // data sub-chunk
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        // Subchunk2Size - updated later
        header[40] = 0; header[41] = 0; header[42] = 0; header[43] = 0
        outputStream.write(header)

        audioDispatcher = AudioDispatcher(AndroidAudioInputStream(audioRecord!!, recordingBufferSize), recordingBufferSize, recordingBufferSize / 2)
        audioDispatcher?.addAudioProcessor(object : be.tarsos.dsp.AudioProcessor {
            override fun process(audioEvent: AudioEvent): Boolean {
                try {
                    outputStream.write(audioEvent.byteBuffer)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error writing to WAV file", e)
                    return false
                }
                return true
            }

            override fun processingFinished() {
                // Not used here, handled in finally block
            }
        })

        audioRecord?.startRecording()
        Thread {
            try {
                audioDispatcher?.run()
            } catch (e: Exception) {
                Log.e("MainActivity", "Error in recording thread", e)
            } finally {
                val recordedDataSize = file.length() - 44
                if (recordedDataSize > 0) {
                    outputStream.channel.position(4) // ChunkSize
                    val chunkSize = recordedDataSize + 36
                    outputStream.write(byteArrayOf(
                        (chunkSize and 0xff).toByte(),
                        ((chunkSize shr 8) and 0xff).toByte(),
                        ((chunkSize shr 16) and 0xff).toByte(),
                        ((chunkSize shr 24) and 0xff).toByte()
                    ))
                    outputStream.channel.position(40) // Subchunk2Size
                    outputStream.write(byteArrayOf(
                        (recordedDataSize and 0xff).toByte(),
                        ((recordedDataSize shr 8) and 0xff).toByte(),
                        ((recordedDataSize shr 16) and 0xff).toByte(),
                        ((recordedDataSize shr 24) and 0xff).toByte()
                    ))
                }
                outputStream.close()
                runOnUiThread {
                    if (recordedDataSize > 0) {
                        Toast.makeText(this@MainActivity, "Mantra recorded: ${file.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
                        loadReferenceMFCCs()
                    } else if (file.exists()) {
                        file.delete()
                        Toast.makeText(this@MainActivity, "Recording failed, file deleted.", Toast.LENGTH_SHORT).show()
                    }
                    stopListening()
                }
            }
        }.start()
        Log.d("MainActivity", "Started mantra recording to file: ${file.name}")
    }

    private fun loadReferenceMFCCs() {
        Log.d("MainActivity", "Loading reference MFCCs...")
        referenceMFCCs = savedMantras.associateWith { mantraName ->
            val file = File(storageDir, "$mantraName.wav")
            if (file.exists() && file.length() > 44) {
                try {
                    Log.d("MainActivity", "Processing reference: $mantraName")
                    val inputStream = FileInputStream(file)
                    val audioFormat = TarsosDSPAudioFormat(sampleRate.toFloat(), 16, 1, true, false) // 16-bit, mono
                    val audioStream = AndroidAudioInputStream(inputStream, audioFormat, recordingBufferSize)
                    val dispatcher = AudioDispatcher(audioStream, recordingBufferSize, recordingBufferSize / 2)
                    val mfcc = MFCC(
                        sampleRate.toFloat().toInt(),
                        recordingBufferSize, // Use Int for bufferSize
                        13,
                        40,
                        20f,
                        4000f
                    )
                    val mfccList = mutableListOf<FloatArray>()
                    dispatcher.addAudioProcessor(mfcc)
                    dispatcher.addAudioProcessor(object : be.tarsos.dsp.AudioProcessor {
                        override fun process(audioEvent: AudioEvent): Boolean {
                            mfccList.add(mfcc.mfcc.copyOf())
                            return true
                        }
                        override fun processingFinished() {}
                    })
                    dispatcher.run()
                    Log.d("MainActivity", "Loaded ${mfccList.size} MFCC frames for $mantraName")
                    mfccList
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error loading MFCC for $mantraName", e)
                    emptyList()
                }
            } else {
                Log.w("MainActivity", "Reference file not found or empty: $mantraName.wav")
                emptyList()
            }
        }.filterValues { it.isNotEmpty() }
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
        stopListening() // Ensure listening stops before showing dialog
        val toneGenerator = ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300)
        Handler(Looper.getMainLooper()).postDelayed({ toneGenerator.release() }, 500)

        AlertDialog.Builder(this)
            .setTitle("Mantra Limit Reached")
            .setMessage("The target number of mantra recitations ($matchLimit) has been reached.")
            .setPositiveButton("OK & Continue") { _, _ ->
                matchCount = 0
                isListening = true
                startListeningWithDelay()
            }
            .setNegativeButton("Stop") { _, _ ->
                matchCount = 0
            }
            .setCancelable(false)
            .show()
    }

    override fun onPause() {
        super.onPause()
        if (isListening) {
            Log.d("MainActivity", "onPause: Stopping listening due to activity pause.")
            stopListening()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy: Ensuring everything is stopped and released.")
        stopListening()
    }
}