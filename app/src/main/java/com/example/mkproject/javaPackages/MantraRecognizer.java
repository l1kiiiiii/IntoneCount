
// MantraRecognizer.java - Java for workflow logic, TarsosDSP integration
package com.example.mkproject.javaPackages;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.core.content.ContextCompat;

import be.tarsos.dsp.AudioDispatcher;
import be.tarsos.dsp.AudioEvent;
import be.tarsos.dsp.AudioProcessor;
import be.tarsos.dsp.io.android.AudioDispatcherFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class MantraRecognizer {
    static {
        System.loadLibrary("mantra_matcher");
    }

    // Native methods
    public native float[] extractMFCC(float[] audioData);
    public native float computeDTW(float[][] mfccSeq1, float[][] mfccSeq2);

    private static final String TAG = "MantraRecognizer";
    private static final int SAMPLE_RATE = 48000;
    private static final int BUFFER_SIZE = 2048; // ~42ms
    private static final int OVERLAP = BUFFER_SIZE / 2;
    private static final int MFCC_SIZE = 13;
    private static final int MAX_UTTERANCE_FRAMES = 150;
    private static final int SILENCE_FRAMES_THRESHOLD = 15;

    private final Context context;
    private final File storageDir;
    private AudioDispatcher dispatcher;
    private final AtomicBoolean isRecognizing = new AtomicBoolean(false);
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private int matchCount = 0;
    private int matchLimit = 0;
    private float similarityThreshold = 0.7f;
    private String targetMantra = "";
    private MantraListener listener;

    private Map<String, List<float[]>> referenceMFCCs = new HashMap<>();
    private List<String> savedMantras = new ArrayList<>();

    private List<float[]> currentUtterance = new ArrayList<>();
    private int consecutiveSilence = 0;

    private Handler mainHandler = new Handler(Looper.getMainLooper());



    public MantraRecognizer(Context context) {
        this.storageDir = new File(context.getFilesDir(), "mantras");
        if (!this.storageDir.exists()) {
            this.storageDir.mkdirs(); // Create the directory if it doesn't exist
        }
        this.context = context;
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setListener(MantraListener listener) {
        this.listener = listener;
    }

    public List<String> getSavedMantras() {
        return new ArrayList<>(savedMantras);
    }

    public void loadSavedMantras() {
        if (!storageDir.exists() || !storageDir.isDirectory()) {
            if (listener != null) listener.onError("Mantra directory not found.");
            savedMantras = new ArrayList<>();
            if (listener != null) listener.onMantrasUpdated();
            return;
        }
        savedMantras.clear();
        referenceMFCCs.clear();
        File[] files = storageDir.listFiles((dir, name) -> name.endsWith(".wav"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName().replace(".wav", "");
                savedMantras.add(name);
                loadReferenceMFCC(name, file);
            }
        }
        if (listener != null) {
            mainHandler.post(() -> listener.onMantrasUpdated());
        }
    }

    private void loadReferenceMFCC(String name, File file) {
        float[] audio = loadWavToFloatArray(file);
        if (audio != null && audio.length > 0) {
            List<float[]> mfccs = new ArrayList<>();
            for (int i = 0; (i + BUFFER_SIZE) <= audio.length; i += OVERLAP) { // Ensure full buffer reads
                float[] chunk = new float[BUFFER_SIZE];
                System.arraycopy(audio, i, chunk, 0, BUFFER_SIZE);
                // Note: Assumes JNI extractMFCC handles potential errors/empty results gracefully.
                float[] mfcc = extractMFCC(chunk);
                if (mfcc != null && mfcc.length == MFCC_SIZE) {
                    mfccs.add(mfcc);
                }
            }
            if (!mfccs.isEmpty()) {
                referenceMFCCs.put(name, mfccs);
                Log.d(TAG, "Loaded reference MFCCs for: " + name + " with " + mfccs.size() + " frames.");
            }else {
                Log.w(TAG, "No MFCCs extracted for reference: " + name);
            }
        }else {
            Log.w(TAG, "Failed to load audio or audio is empty for reference: " + name + " from file: " + file.getAbsolutePath());
        }
    }

    private float[] loadWavToFloatArray(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[44];
            if (fis.read(header) != 44) {
                Log.e(TAG, "WAV file header too short: " + file.getPath());
                return null;
            }

            // Basic WAV validation (mono, 16-bit, specific sample rate)
            int channels = ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
            int bitsPerSample = ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
            int fileSampleRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

            if (channels != 1) {
                 Log.e(TAG, "Unsupported WAV format: not mono. Channels: " + channels);
                 return null;
            }
            if (bitsPerSample != 16) {
                Log.e(TAG, "Unsupported WAV format: not 16-bit. BitsPerSample: " + bitsPerSample);
                return null;
            }
            // Allow some flexibility or ensure WAVs are always at SAMPLE_RATE
             if (fileSampleRate != SAMPLE_RATE) {
                Log.e(TAG, "Unsupported WAV format: sample rate mismatch. File: " + fileSampleRate + ", Expected: " + SAMPLE_RATE);
               return null;
            }

            int dataSize = ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (dataSize <= 0) {
                Log.e(TAG, "WAV file data size invalid: " + dataSize);
                return null;
            }

            byte[] data = new byte[dataSize];
            int bytesRead = fis.read(data);
            if (bytesRead != dataSize) {
                Log.e(TAG, "Could not read full WAV data. Expected: " + dataSize + ", Got: " + bytesRead);
                return null;
            }

            short[] shortsArray = new short[dataSize / 2];
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortsArray);
            
            float[] floats = new float[shortsArray.length];
            for (int i = 0; i < shortsArray.length; i++) {
                floats[i] = shortsArray[i] / 32768.0f; // Normalize to [-1.0, 1.0]
            }
            return floats;
        } catch (IOException e) {
            Log.e(TAG, "Error loading WAV file: " + file.getPath(), e);
            return null;
        }
    }

    // Callers must ensure RECORD_AUDIO permission is granted.
    public void startRecognition(String mantra, int limit, float threshold) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) listener.onError("RECORD_AUDIO permission not granted.");
            return;
        }

        targetMantra = mantra;
        matchLimit = limit;
        similarityThreshold = threshold;
        matchCount = 0;
        if (listener != null) listener.onMatchCountUpdate(0);

        if (!referenceMFCCs.containsKey(mantra)) {
            if (listener != null) listener.onError("No reference for " + mantra);
            return;
        }

        isRecognizing.set(true);
        if (listener != null) {
            mainHandler.post(() -> {
                listener.onStatusUpdate("Listening");
                listener.onRecognizingStateChanged(true);
            });
        }
        try {
            dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(SAMPLE_RATE, BUFFER_SIZE, OVERLAP);
            dispatcher.addAudioProcessor(new AudioProcessor() {
                @Override
                public boolean process(AudioEvent audioEvent) {
                    float[] buffer = audioEvent.getFloatBuffer();
                    // Note: Assumes JNI extractMFCC handles potential errors/empty results gracefully.
                    float[] mfcc = extractMFCC(buffer);
                    if (mfcc != null && mfcc.length == MFCC_SIZE) {
                        currentUtterance.add(mfcc);
                        consecutiveSilence = 0;
                        if (currentUtterance.size() > MAX_UTTERANCE_FRAMES) {
                            // Utterance too long, could try to process or just clear
                            Log.w(TAG, "Utterance too long, clearing.");
                            processUtterance(); // Process what we have before clearing
                            currentUtterance.clear();
                        }
                    } else {
                        consecutiveSilence++;
                        if (consecutiveSilence >= SILENCE_FRAMES_THRESHOLD && !currentUtterance.isEmpty()) {
                            processUtterance();
                            currentUtterance.clear();
                            consecutiveSilence = 0;
                        }
                    }
                    return isRecognizing.get();
                }

                @Override
                public void processingFinished() {}
            });
            new Thread(dispatcher, "Audio Dispatcher").start();
        } catch (Exception e) { // Catch potential exceptions from AudioDispatcherFactory
            Log.e(TAG, "Error starting AudioDispatcher", e);
            if (listener != null) listener.onError("Error initializing audio: " + e.getMessage());
            isRecognizing.set(false); 
            if (listener != null) {
                 mainHandler.post(() -> {
                    listener.onStatusUpdate("Error");
                    listener.onRecognizingStateChanged(false);
                });
            }
        }
    }

    private void processUtterance() {
        List<float[]> ref = referenceMFCCs.get(targetMantra);
        if (ref == null || currentUtterance.isEmpty()) return;

        float[][] utteranceArray = currentUtterance.toArray(new float[0][]);
        float[][] refArray = ref.toArray(new float[0][]);
        
        // Log sizes for debugging
        Log.d(TAG, "Processing utterance: " + utteranceArray.length + " frames, Reference: " + refArray.length + " frames");

        // Note: Assumes JNI computeDTW handles potential errors/empty results gracefully.
        float similarity = computeDTW(utteranceArray, refArray);
        // Log.d(TAG, "DTW Similarity: " + similarity);

        if (similarity >= similarityThreshold) {
            matchCount++;
            if (listener != null) listener.onMatchCountUpdate(matchCount);
            if (matchLimit > 0 && matchCount >= matchLimit) {
                stopRecognition(); // Stop after reaching limit
                if (listener != null) listener.onAlarmTriggered();
            }
        }
    }

    public void stopRecognition() {
        isRecognizing.set(false);
        if (dispatcher != null) {
            try {
                 if(!dispatcher.isStopped()) dispatcher.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping dispatcher", e);
            }
            dispatcher = null;
        }
        if (listener != null) {
            mainHandler.post(() -> {
                listener.onStatusUpdate("Stopped");
                listener.onRecognizingStateChanged(false);
            });
        }
    }

    // Callers must ensure RECORD_AUDIO permission is granted.
    public void recordMantra(String name) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) listener.onError("RECORD_AUDIO permission not granted.");
            return;
        }

        isRecording.set(true);
        if (listener != null) {
            mainHandler.post(() -> {
                listener.onStatusUpdate("Recording: " + name);
                listener.onRecordingStateChanged(true);
            });
        }

        File file = getUniqueFile(name);
        AudioRecord record = null;
        try {
            // Use a common buffer size, ensure it's a multiple of frame size if that matters for underlying libs
            int bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSizeInBytes == AudioRecord.ERROR_BAD_VALUE || bufferSizeInBytes == AudioRecord.ERROR) {
                bufferSizeInBytes = SAMPLE_RATE * 2; // Default to 1 sec buffer if detection failed
            }

            record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeInBytes);
            
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                if (listener != null) listener.onError("Failed to initialize recorder. State: " + record.getState());
                isRecording.set(false);
                if (listener != null) mainHandler.post(() -> listener.onRecordingStateChanged(false));
                return;
            }
            record.startRecording();

            final AudioRecord finalRecord = record; // For use in thread
            new Thread(() -> {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    writeWavHeader(fos, 0); // Write header first with 0 data length
                    short[] buffer = new short[BUFFER_SIZE]; // Use class member BUFFER_SIZE for consistency with MFCC processing
                    int totalBytesWritten = 0;
                    while (isRecording.get()) {
                        int read = finalRecord.read(buffer, 0, buffer.length);
                        if (read > 0) {
                            byte[] bytes = new byte[read * 2]; // 1 short = 2 bytes
                            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, read);
                            fos.write(bytes);
                            totalBytesWritten += read * 2;
                        }
                    }
                    updateWavHeader(file, totalBytesWritten); // Update header with actual data length
                } catch (IOException e) {
                    Log.e(TAG, "Recording I/O error", e);
                    file.delete(); // Clean up partial file
                } finally {
                    if (finalRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) finalRecord.stop();
                    finalRecord.release();
                    isRecording.set(false);
                    mainHandler.post(() -> {
                        loadSavedMantras(); // Reload mantras after recording finishes
                        if (listener != null) {
                            listener.onStatusUpdate("Stopped");
                            listener.onRecordingStateChanged(false);
                        }
                    });
                }
            }).start();
        } catch (Exception e) {
            Log.e(TAG, "Error during recordMantra setup", e);
            if (record != null) record.release();
            isRecording.set(false);
            if (listener != null) {
                 mainHandler.post(() -> {
                    listener.onError("Recording setup failed: " + e.getMessage());
                    listener.onRecordingStateChanged(false);
                });
            }
        }
    }

    public void stopRecording() {
        isRecording.set(false); // Signal the recording thread to stop
    }

    private File getUniqueFile(String name) {
        File file = new File(storageDir, name + ".wav");
        int counter = 1;
        String baseName = name;
        while (file.exists()) {
            name = baseName + "_" + counter;
            file = new File(storageDir, name + ".wav");
            counter++;
        }
        return file;
    }

    private void writeWavHeader(FileOutputStream fos, int totalAudioLen) throws IOException {
        int totalDataLen = totalAudioLen + 36;
        int channels = 1;
        int bitsPerSample = 16;
        long sampleRateLong = SAMPLE_RATE;
        long byteRate = sampleRateLong * channels * bitsPerSample / 8;
        byte[] header = new byte[44];

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(16); // Subchunk1Size for PCM
        ByteBuffer.wrap(header, 20, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1); // AudioFormat (1 for PCM)
        ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) channels);
        ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(SAMPLE_RATE);
        ByteBuffer.wrap(header, 28, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((int)byteRate);
        ByteBuffer.wrap(header, 32, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) (channels * bitsPerSample / 8)); // BlockAlign
        ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) bitsPerSample);
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalAudioLen);

        fos.write(header);
    }

    private void updateWavHeader(File file, int totalAudioLen) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            int totalDataLen = totalAudioLen + 36;
            raf.seek(4); // RIFF chunk size
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen).array());
            raf.seek(40); // Data subchunk size
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalAudioLen).array());
        } catch (IOException e) {
            Log.e(TAG, "Error updating WAV header for " + file.getPath(), e);
        }
    }

    public void deleteMantra(String name) {
        File file = new File(storageDir, name + ".wav");
        if (file.exists()) {
            if (file.delete()) {
                loadSavedMantras(); // Reloads and triggers listener
            } else {
                if (listener != null) listener.onError("Failed to delete " + name);
            }
        } else {
            if (listener != null) listener.onError("File not found for deletion: " + name);
            loadSavedMantras(); // Still refresh, in case list was out of sync
        }
    }

    public void resetMatchCount() {
        matchCount = 0;
        if (listener != null) listener.onMatchCountUpdate(0);
    }

    public interface MantraListener {
        void onStatusUpdate(String newStatus);
        void onMatchCountUpdate(int count);
        void onAlarmTriggered();
        void onError(String error);
        void onMantrasUpdated();
        void onRecognizingStateChanged(boolean recognizing);
        void onRecordingStateChanged(boolean recording);
    }
}
