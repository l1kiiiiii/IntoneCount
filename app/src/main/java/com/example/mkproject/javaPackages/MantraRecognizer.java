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
    private static final String TAG = "MantraRecognizer";
    private static final int SAMPLE_RATE = 48000;
    private static final int BUFFER_SIZE = 2048;
    private static final int OVERLAP = BUFFER_SIZE / 2;
    public static final int MFCC_SIZE = 13;
    private static final int MAX_UTTERANCE_FRAMES = 150; // Max frames for a live utterance before comparing
    private static final int SILENCE_FRAMES_THRESHOLD = 15; // Consecutive silent frames to trigger DTW

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

    private Map<String, List<float[]>> referenceMFCCs = new HashMap<>(); // Holds full MFCC sequences for stored mantras
    private List<String> savedMantras = new ArrayList<>();
    private List<float[]> currentUtterance = new ArrayList<>(); // Accumulates MFCC frames from live audio
    private int consecutiveSilence = 0;

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public MantraRecognizer(Context context) {
        this.storageDir = new File(context.getFilesDir(), "mantras");
        if (!this.storageDir.exists()) {
            if(!this.storageDir.mkdirs()){
                Log.e(TAG, "Failed to create storage directory: " + this.storageDir.getAbsolutePath());
            }
        }
        this.context = context;
        // mainHandler is already initialized with Looper.getMainLooper()
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
            referenceMFCCs.clear(); // Ensure consistency
            if (listener != null) mainHandler.post(listener::onMantrasUpdated);
            return;
        }
        List<String> newSavedMantras = new ArrayList<>();
        Map<String, List<float[]>> newReferenceMFCCs = new HashMap<>();

        File[] files = storageDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".wav"));
        if (files != null) {
            for (File file : files) {
                String name = file.getName().substring(0, file.getName().length() - 4); // Remove .wav
                float[] audio = loadWavToFloatArray(file);
                if (audio != null && audio.length > 0) {
                    List<float[]> mfccs = AudioMatcher.extractMFCC(audio);
                    if (mfccs != null && !mfccs.isEmpty()) {
                        newReferenceMFCCs.put(name, mfccs);
                        newSavedMantras.add(name);
                        Log.d(TAG, "Loaded reference MFCCs for: " + name + " with " + mfccs.size() + " frames.");
                    } else {
                        Log.w(TAG, "No MFCCs extracted for reference: " + name);
                    }
                } else {
                    Log.w(TAG, "Failed to load audio or audio is empty for reference: " + name);
                }
            }
        }
        this.savedMantras = newSavedMantras;
        this.referenceMFCCs = newReferenceMFCCs;
        if (listener != null) {
            mainHandler.post(listener::onMantrasUpdated);
        }
    }

    // loadReferenceMFCC was inlined into loadSavedMantras essentially

    private float[] loadWavToFloatArray(File file) {
        if (file == null || !file.exists()) {
             Log.e(TAG, "loadWavToFloatArray: File is null or does not exist: " + (file != null ? file.getPath() : "null"));
             return null;
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] header = new byte[44];
            if (fis.read(header) != 44) {
                Log.e(TAG, "WAV file header too short: " + file.getPath());
                return null;
            }

            int channels = ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
            int bitsPerSample = ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
            int fileSampleRate = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

            if (channels != 1 || bitsPerSample != 16 || fileSampleRate != SAMPLE_RATE) {
                Log.e(TAG, String.format("Unsupported WAV format for %s: channels=%d (exp 1), bitsPerSample=%d (exp 16), sampleRate=%d (exp %d)",
                 file.getName(), channels, bitsPerSample, fileSampleRate, SAMPLE_RATE));
                return null;
            }

            int dataSize = ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if (dataSize <= 0 || dataSize > file.length() - 44) { // Basic sanity check for dataSize
                Log.e(TAG, "WAV file data size invalid or exceeds file bounds: " + dataSize + " for file " + file.getPath());
                return null;
            }

            byte[] data = new byte[dataSize];
            int bytesRead = fis.read(data);
            if (bytesRead != dataSize) {
                Log.e(TAG, String.format("Could not read full WAV data for %s. Expected: %d, Got: %d", file.getName(), dataSize, bytesRead));
                return null;
            }

            short[] shortsArray = new short[dataSize / 2];
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortsArray);
            float[] floatArray = new float[shortsArray.length];
            for (int i = 0; i < shortsArray.length; i++) {
                floatArray[i] = shortsArray[i] / 32768.0f; // Normalize to [-1, 1]
            }
            return floatArray;
        } catch (IOException e) {
            Log.e(TAG, "Error loading WAV file: " + file.getPath(), e);
            return null;
        }
    }

    public void startRecognition(String mantra, int limit, float threshold) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) mainHandler.post(() -> listener.onError("Microphone permission required."));
            return;
        }

        targetMantra = mantra;
        matchLimit = limit;
        similarityThreshold = threshold;
        matchCount = 0;
        isRecognizing.set(true);
        currentUtterance.clear();
        consecutiveSilence = 0;

        if (!referenceMFCCs.containsKey(mantra)) {
            Log.e(TAG, "Target mantra '" + mantra + "' not found in referenceMFCCs map.");
            if (listener != null) mainHandler.post(() -> listener.onError("Mantra not found: " + mantra));
            isRecognizing.set(false);
            if (listener != null) mainHandler.post(() -> listener.onRecognizingStateChanged(false));
            return;
        }

        final List<float[]> referenceMfccSequence = referenceMFCCs.get(targetMantra);
        if (referenceMfccSequence == null || referenceMfccSequence.isEmpty()){
            Log.e(TAG, "Reference MFCC sequence for '" + mantra + "' is null or empty.");
            if (listener != null) mainHandler.post(() -> listener.onError("Reference mantra data is invalid for: " + mantra));
            isRecognizing.set(false);
            if (listener != null) mainHandler.post(() -> listener.onRecognizingStateChanged(false));
            return;
        }
        // Pre-load the reference audio WAV file for computeDTW
        final float[] referenceAudio = loadWavToFloatArray(new File(storageDir, targetMantra + ".wav"));
        if (referenceAudio == null) {
            Log.e(TAG, "Failed to load reference audio WAV for DTW: " + targetMantra);
            if (listener != null) mainHandler.post(() -> listener.onError("Failed to load reference audio for: " + targetMantra));
            isRecognizing.set(false);
            if (listener != null) mainHandler.post(() -> listener.onRecognizingStateChanged(false));
            return;
        }


        if (listener != null) {
            mainHandler.post(() -> {
                listener.onStatusUpdate("Recognizing: " + mantra);
                listener.onMatchCountUpdate(0);
                listener.onRecognizingStateChanged(true);
            });
        }

        try {
            dispatcher = AudioDispatcherFactory.fromDefaultMicrophone(SAMPLE_RATE, BUFFER_SIZE, OVERLAP);
        } catch (Exception e) { // Catch potential exceptions from factory method
            Log.e(TAG, "Failed to create AudioDispatcher from microphone", e);
            if (listener != null) mainHandler.post(() -> listener.onError("Failed to initialize microphone: " + e.getMessage()));
            isRecognizing.set(false);
            if (listener != null) mainHandler.post(() -> listener.onRecognizingStateChanged(false));
            return;
        }
        
        dispatcher.addAudioProcessor(new be.tarsos.dsp.AudioProcessor() {
            @Override
            public boolean process(AudioEvent audioEvent) {
                if (!isRecognizing.get()) return false; // Stop processing if recognition was cancelled

                float[] audioBuffer = audioEvent.getFloatBuffer();
                if (audioBuffer == null || audioBuffer.length == 0) return true;

                List<float[]> liveMfccsPortion = AudioMatcher.extractMFCC(audioBuffer);
                if (liveMfccsPortion == null || liveMfccsPortion.isEmpty()) return true;

                currentUtterance.addAll(liveMfccsPortion);
                // Keep currentUtterance from growing indefinitely
                while (currentUtterance.size() > MAX_UTTERANCE_FRAMES) {
                    currentUtterance.remove(0);
                }

                // Use the last MFCC frame from the current live portion for silence detection
                float[] lastLiveMfccFrame = liveMfccsPortion.get(liveMfccsPortion.size() - 1);

                if (AudioMatcher.isSilentFrame(audioBuffer, lastLiveMfccFrame)) {
                    consecutiveSilence++;
                    if (consecutiveSilence >= SILENCE_FRAMES_THRESHOLD && !currentUtterance.isEmpty()) {
                        // Silence threshold met, currentUtterance is considered complete. Perform DTW.
                        Log.d(TAG, "Silence detected. Utterance size: " + currentUtterance.size() + ". Comparing with '" + targetMantra + "'.");
                        
                        // Ensure reference is still valid (it should be, but good for safety)
                        if (referenceMfccSequence != null && !referenceMfccSequence.isEmpty() && referenceAudio != null) {
                             float similarity = AudioMatcher.computeDTW(new ArrayList<>(currentUtterance), referenceMfccSequence, referenceAudio);
                             Log.d(TAG, "DTW Similarity for '"+targetMantra+"': " + similarity);

                            if (similarity >= similarityThreshold) {
                                matchCount++;
                                Log.i(TAG, "Match detected for '" + targetMantra + "'! Count: " + matchCount + " (Limit: "+matchLimit+", Threshold: "+similarityThreshold+", Similarity: "+similarity+")");
                                mainHandler.post(() -> {
                                    if (listener != null) listener.onMatchCountUpdate(matchCount);
                                    if (matchCount >= matchLimit && listener != null) listener.onAlarmTriggered();
                                });
                            } else {
                                Log.d(TAG, "No match. Similarity " + similarity + " < threshold " + similarityThreshold);
                            }
                        } else {
                            Log.w(TAG, "Skipping DTW: Reference data for '"+targetMantra+"' became invalid during processing.");
                        }
                        currentUtterance.clear();
                        consecutiveSilence = 0;
                    }
                } else {
                    // Not silent, reset silence counter
                    consecutiveSilence = 0;
                }
                return true; // Keep processing
            }

            @Override
            public void processingFinished() {
                Log.d(TAG, "AudioProcessor: processingFinished called.");
                currentUtterance.clear();
                // isRecognizing should be set by stopRecognition logic
                // mainHandler.post(() -> {
                //     if (listener != null) {
                //         listener.onStatusUpdate("Stopped");
                //         listener.onRecognizingStateChanged(false);
                //     }
                // });
            }
        });

        new Thread(dispatcher::run, "AudioRecognitionThread").start();
    }

    public void stopRecognition() {
        if (!isRecognizing.compareAndSet(true, false)) {
            Log.d(TAG, "stopRecognition called but was not recognizing.");
            return; // Already stopped or wasn't running
        }
        Log.d(TAG, "Stopping recognition...");
        if (dispatcher != null) {
            try {
                 dispatcher.stop();
            } catch (Exception e){
                Log.e(TAG, "Exception while stopping dispatcher", e);
            }
            dispatcher = null;
        }
        currentUtterance.clear();
        consecutiveSilence = 0;
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onStatusUpdate("Stopped");
                listener.onRecognizingStateChanged(false);
            }
        });
        Log.d(TAG, "Recognition stopped.");
    }

    public void recordMantra(String name) {
        if (name == null || name.trim().isEmpty()){
            if(listener != null) mainHandler.post(() -> listener.onError("Mantra name cannot be empty."));
            return;
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (listener != null) mainHandler.post(() -> listener.onError("Microphone permission required."));
            return;
        }
        if (isRecording.get()) {
            if (listener != null) mainHandler.post(() -> listener.onError("Already recording."));
            return;
        }

        File file = getUniqueFile(name.trim());
        isRecording.set(true);
        if (listener != null) {
            mainHandler.post(() -> {
                listener.onStatusUpdate("Recording: " + file.getName().replace(".wav",""));
                listener.onRecordingStateChanged(true);
            });
        }

        AudioRecord record = null;
        try {
            int bufferSizeInBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (bufferSizeInBytes <= 0) { // Check for error or invalid value
                Log.w(TAG, "AudioRecord.getMinBufferSize returned error or invalid value: " + bufferSizeInBytes + ". Using default.");
                bufferSizeInBytes = SAMPLE_RATE * 2 * 2; // Default to 2s buffer, 16-bit mono
            }

            record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeInBytes);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Failed to initialize AudioRecord. State: " + record.getState());
                if (listener != null) {
                    AudioRecord finalRecord1 = record;
                    mainHandler.post(() -> listener.onError("Failed to initialize recorder. State: " + finalRecord1.getState()));
                }
                isRecording.set(false);
                if (listener != null) mainHandler.post(() -> listener.onRecordingStateChanged(false));
                if (record != null) record.release(); // Release if initialized but failed later
                return;
            }
            record.startRecording();
            Log.d(TAG, "AudioRecord started recording to file: " + file.getAbsolutePath());

            final AudioRecord finalRecord = record; // For use in thread
            new Thread(() -> {
                FileOutputStream fos = null;
                try {
                    fos = new FileOutputStream(file);
                    writeWavHeader(fos, 0); // Write initial header with 0 data length
                    short[] buffer = new short[BUFFER_SIZE]; // Use class constant for buffer size consistency
                    int totalBytesWritten = 0;
                    while (isRecording.get()) {
                        int shortsRead = finalRecord.read(buffer, 0, buffer.length);
                        if (shortsRead > 0) {
                            byte[] bytesToWrite = new byte[shortsRead * 2]; // Each short is 2 bytes
                            ByteBuffer.wrap(bytesToWrite).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(buffer, 0, shortsRead);
                            fos.write(bytesToWrite);
                            totalBytesWritten += bytesToWrite.length;
                        } else if (shortsRead < 0) { // Error reading
                            Log.e(TAG, "AudioRecord read error: " + shortsRead);
                            break; 
                        }
                    }
                    fos.flush(); // Ensure all buffered data is written
                    fos.close(); // Close before updating header
                    fos = null; // Mark as closed
                    updateWavHeader(file, totalBytesWritten);
                    Log.d(TAG, "Recording finished. Total bytes written: " + totalBytesWritten + " to " + file.getName());
                    if (totalBytesWritten == 0 && file.exists()) {
                        Log.w(TAG, "Recording was empty, deleting file: " + file.getName());
                        file.delete();
                         mainHandler.post(() -> {
                             if (listener != null) listener.onError("Recording was empty.");
                         });
                    } else {
                        mainHandler.post(this::loadSavedMantras); // Reload mantras including the new one
                    }

                } catch (IOException e) {
                    Log.e(TAG, "Recording I/O error for " + file.getName(), e);
                    if (file.exists()) file.delete(); // Attempt to clean up partial file
                     mainHandler.post(() -> {
                        if (listener != null) listener.onError("Recording failed: "+e.getMessage());
                    });
                } finally {
                    if (fos != null) {
                        try { fos.close(); } catch (IOException e) { Log.e(TAG, "Failed to close FileOutputStream", e); }
                    }
                    if (finalRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        try { finalRecord.stop(); } catch(IllegalStateException e) { Log.e(TAG, "Failed to stop AudioRecord", e);}
                    }
                    finalRecord.release();
                    isRecording.set(false);
                    mainHandler.post(() -> {
                        if (listener != null) {
                            // Status update handled by error or loadSavedMantras completion
                            listener.onRecordingStateChanged(false);
                        }
                    });
                }
            }, "AudioRecordingThread").start();
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
        if(isRecording.compareAndSet(true, false)){
            Log.d(TAG, "Stopping recording via stopRecording() call.");
            // The recording thread will see isRecording flag and stop itself.
        } else {
            Log.d(TAG, "stopRecording called but was not recording.");
        }
    }

    private File getUniqueFile(String name) {
        String sanitizedName = name.replaceAll("[^a-zA-Z0-9_.-]+", "_");
        File file = new File(storageDir, sanitizedName + ".wav");
        if (!file.exists()) return file;
        
        int counter = 1;
        String baseName = sanitizedName;
        while (file.exists()) {
            sanitizedName = baseName + "_" + counter;
            file = new File(storageDir, sanitizedName + ".wav");
            counter++;
        }
        return file;
    }

    private void writeWavHeader(FileOutputStream fos, int totalAudioLen) throws IOException {
        int totalDataLen = totalAudioLen + 36; // 36 is for the rest of the header from 'WAVE' onwards
        int channels = 1;
        int bitsPerSample = 16;
        long byteRate = (long)SAMPLE_RATE * channels * bitsPerSample / 8;
        byte[] header = new byte[44];

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F'; // RIFF chunk
        ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen); // ChunkSize
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E'; // WAVE format
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' '; // fmt subchunk
        ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(16); // Subchunk1Size (16 for PCM)
        ByteBuffer.wrap(header, 20, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) 1); // AudioFormat (1 for PCM)
        ByteBuffer.wrap(header, 22, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) channels); // NumChannels
        ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(SAMPLE_RATE); // SampleRate
        ByteBuffer.wrap(header, 28, 4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) byteRate); // ByteRate
        ByteBuffer.wrap(header, 32, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) (channels * bitsPerSample / 8)); // BlockAlign
        ByteBuffer.wrap(header, 34, 2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) bitsPerSample); // BitsPerSample
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a'; // data subchunk
        ByteBuffer.wrap(header, 40, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalAudioLen); // Subchunk2Size (data size)

        fos.write(header);
    }

    private void updateWavHeader(File file, int totalAudioLen) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            int totalDataLen = totalAudioLen + 36;
            raf.seek(4); // Position for ChunkSize
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen).array());
            raf.seek(40); // Position for Subchunk2Size (data size)
            raf.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalAudioLen).array());
            Log.d(TAG, "WAV header updated for " + file.getName() + ". TotalAudioLen: "+totalAudioLen+", TotalDataLen: "+totalDataLen);
        } catch (IOException e) {
            Log.e(TAG, "Error updating WAV header for " + file.getPath(), e);
        }
    }

    public void deleteMantra(String name) {
        if (name == null || name.trim().isEmpty()) {
             if (listener != null) mainHandler.post(() -> listener.onError("Mantra name for deletion is empty."));
             return;
        }
        File file = new File(storageDir, name.trim() + ".wav");
        if (file.exists()) {
            if (file.delete()) {
                Log.d(TAG, "Deleted mantra file: " + name);
                loadSavedMantras(); // Reload to update lists and UI
                 mainHandler.post(() -> {
                     if (listener != null) listener.onStatusUpdate("Deleted: " + name);
                 });
            } else {
                Log.e(TAG, "Failed to delete mantra file: " + name);
                if (listener != null) mainHandler.post(() -> listener.onError("Failed to delete " + name));
            }
        } else {
            Log.w(TAG, "Mantra file not found for deletion: " + name);
            if (listener != null) mainHandler.post(() -> listener.onError("File not found for deletion: " + name));
            loadSavedMantras(); // Still reload, in case of inconsistency
        }
    }

    public void resetMatchCount() {
        matchCount = 0;
        if (listener != null) mainHandler.post(() -> listener.onMatchCountUpdate(0));
    }

    public interface MantraListener {
        void onStatusUpdate(String newStatus);
        void onMatchCountUpdate(int count);
        void onAlarmTriggered();
        void onError(String error);
        void onMantrasUpdated(); // Called when the list of saved mantras changes
        void onRecognizingStateChanged(boolean recognizing);
        void onRecordingStateChanged(boolean recording);
    }
}
