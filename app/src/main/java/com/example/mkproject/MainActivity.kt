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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import be.tarsos.dsp.AudioDispatcher
import be.tarsos.dsp.AudioEvent
import be.tarsos.dsp.io.android.AndroidAudioInputStream
import be.tarsos.dsp.mfcc.MFCC
import com.example.mkproject.ui.theme.MainScreen
import com.example.mkproject.ui.theme.MkprojectTheme
import java.io.*
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {

    // App logic variables
    private var isListening by mutableStateOf(false)
    private var matchCount by mutableStateOf(0)
    private var matchLimit by mutableStateOf(0)
    private var targetMantra by mutableStateOf("")
    private var lastProcessedAudio: FloatArray? = null
    private var referenceMFCCs: Map<String, List<FloatArray>> = emptyMap()

    // Audio processing variables
    private var audioRecord: AudioRecord? = null
    private var audioDispatcher: AudioDispatcher? = null
    private var dispatcherThread: Thread? = null

    // Storage and permissions
    private val storageDir: File by lazy { File(filesDir, "mantras").also { if (!it.exists()) it.mkdirs() } }
    private val savedMantras: List<String> get() = storageDir.listFiles()?.filter { it.extension == "wav" }?.map { it.nameWithoutExtension } ?: emptyList()
    private val recordAudioLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
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
                    onRecordMantraClick = { recordMantra() },
                    onStartStopClick = { target, limit ->
                        if (isListening) {
                            stopListening()
                        } else {
                            if (target.isBlank()) {
                                Toast.makeText(this@MainActivity, "Please select a recorded mantra.", Toast.LENGTH_SHORT).show()
                                return@MainScreen
                            }
                            matchLimit = limit.toIntOrNull() ?: 0
                            if (matchLimit <= 0) {
                                Toast.makeText(this@MainActivity, "Please enter a valid match limit.", Toast.LENGTH_SHORT).show()
                                return@MainScreen
                            }
                            targetMantra = target
                            matchCount = 0
                            isListening = true
                            startListeningWithDelay()
                        }
                    },
                    matchCount = matchCount,
                    processingStatus = if (isListening) "Listening for mantra..." else "Stopped",
                    matchLimitText = matchLimitText,
                    onMatchLimitTextChange = { matchLimitText = it }
                )
            }
        }
        checkPermissionAndStart()
        loadReferenceMFCCs()
    }

    @SuppressLint("MissingPermission")
    private fun startListeningWithDelay() {
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isListening) return@postDelayed
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "Failed to initialize audio recording.", Toast.LENGTH_SHORT).show()
                stopListening()
                return@postDelayed
            }
            val audioStream = AndroidAudioInputStream(audioRecord!!, bufferSize)
            audioDispatcher = AudioDispatcher(audioStream, bufferSize, bufferSize / 2)
            val mfcc = MFCC(sampleRate.toFloat(), bufferSize, 13, 40, 20f, 4000f)
            audioDispatcher?.addAudioProcessor(mfcc)
            audioDispatcher?.addAudioProcessor(object : be.tarsos.dsp.AudioProcessor {
                override fun process(audioEvent: AudioEvent): Boolean {
                    val mfccs = mfcc.mfcc
                    lastProcessedAudio = mfccs.copyOf()
                    if (targetMantra.isNotEmpty() && referenceMFCCs.containsKey(targetMantra)) {
                        val similarity = calculateCosineSimilarity(mfccs, referenceMFCCs[targetMantra]!![0])
                        if (similarity > 0.9) {
                            matchCount++
                            Log.d("MainActivity", "Match detected, count: $matchCount")
                            if (matchCount >= matchLimit) {
                                runOnUiThread { triggerAlarm() }
                            }
                        }
                    }
                    return true
                }
                override fun processingFinished() {}
            })
            audioRecord?.startRecording()
            dispatcherThread = Thread { audioDispatcher?.run() }
            dispatcherThread?.start()
        }, 500)
    }

    private fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioDispatcher?.stop()
        dispatcherThread?.interrupt()
        dispatcherThread = null
        audioDispatcher = null
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } // else no action needed
    }

    private fun showPermissionAlert() {
        AlertDialog.Builder(this)
            .setTitle("Permission Required")
            .setMessage("Go to Settings > Permissions > Enable Microphone")
            .setPositiveButton("OK") { _, _ -> }
            .setNegativeButton("Settings") { _, _ ->
                val intent = android.content.Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .show()
    }

    private fun recordMantra() {
        val sampleRate = 16000
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
        if (audioRecord == null) {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "Failed to initialize audio recording.", Toast.LENGTH_SHORT).show()
                return
            }
        }
        isListening = true
        val file = File(storageDir, "mantra_${System.currentTimeMillis()}.wav")
        val outputStream = RandomAccessFile(file, "rw")

        // Write placeholder WAV header
        writeWavHeader(outputStream, sampleRate, 1, 16, 0)

        audioDispatcher = AudioDispatcher(AndroidAudioInputStream(audioRecord!!, bufferSize), bufferSize, bufferSize / 2)
        audioDispatcher?.addAudioProcessor(object : be.tarsos.dsp.AudioProcessor {
            override fun process(audioEvent: AudioEvent): Boolean {
                outputStream.write(audioEvent.byteBuffer)
                return true
            }
            override fun processingFinished() {
                val dataSize = (outputStream.length() - 44).toInt()
                try {
                    outputStream.seek(4)
                    outputStream.writeInt(Integer.reverseBytes(36 + dataSize)) // RIFF chunk size
                    outputStream.seek(40)
                    outputStream.writeInt(Integer.reverseBytes(dataSize)) // data chunk size
                } catch(_: Exception) { }
                outputStream.close()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Mantra recorded and saved as ${file.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
                    loadReferenceMFCCs()
                    stopListening()
                }
            }
        })
        audioRecord?.startRecording()
        dispatcherThread = Thread { audioDispatcher?.run() }
        dispatcherThread?.start()
    }

    private fun writeWavHeader(out: RandomAccessFile, sampleRate: Int, channels: Int, bitsPerSample: Int, dataSize: Int) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        out.setLength(0)
        out.writeBytes("RIFF")
        out.writeInt(36 + dataSize) // chunk size (to be updated)
        out.writeBytes("WAVE")
        out.writeBytes("fmt ")
        out.writeInt(16) // subchunk1 size
        out.writeShort(1) // audio format (PCM)
        out.writeShort(channels.toShort().toInt())
        out.writeInt(sampleRate)
        out.writeInt(byteRate)
        out.writeShort(blockAlign.toInt())
        out.writeShort(bitsPerSample)
        out.writeBytes("data")
        out.writeInt(dataSize) // subchunk2 size (to be updated)
    }
    private fun RandomAccessFile.writeBytes(s: String) = this.write(s.toByteArray())
    private fun RandomAccessFile.writeShort(v: Int) {
        this.write(v and 0xff)
        this.write((v shr 8) and 0xff)
    }

    private fun loadReferenceMFCCs() {
        referenceMFCCs = savedMantras.associateWith { mantraName ->
            val file = File(storageDir, "$mantraName.wav")
            if (file.exists()) {
                val inputStream = file.inputStream()
                // TODO: For real WAV, skip 44 bytes header manually, or use a WAV stream parser
                inputStream.skip(44)
                val bufferSize = 2048
                val sampleRate = 16000f
                val audioStream = AndroidAudioInputStream(inputStream, sampleRate.toInt(), bufferSize)
                val dispatcher = AudioDispatcher(audioStream, bufferSize, bufferSize / 2)
                val mfcc = MFCC(sampleRate, bufferSize, 13, 40, 20f, 4000f)
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
                inputStream.close()
                mfccList
            } else emptyList()
        }
    }

    private fun calculateCosineSimilarity(vec1: FloatArray, vec2: FloatArray): Float {
        var dotProduct = 0f; var norm1 = 0f; var norm2 = 0f
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i].pow(2)
            norm2 += vec2[i].pow(2)
        }
        return dotProduct / (sqrt(norm1) * sqrt(norm2))
    }

    @SuppressLint("MissingPermission")
    private fun triggerAlarm() {
        stopListening()
        val toneGenerator = ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
        toneGenerator.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
        Handler(Looper.getMainLooper()).postDelayed({ toneGenerator.release() }, 300)
        AlertDialog.Builder(this)
            .setTitle("Limit Reached")
            .setMessage("The match limit has been reached.")
            .setPositiveButton("OK") { _, _ ->
                matchCount = 0
                isListening = true
                startListeningWithDelay()
            }
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
    }
}