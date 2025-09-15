package com.example.mkproject.javaPackages;

import android.util.Log;
import be.tarsos.dsp.mfcc.MFCC;
import java.util.ArrayList;
import java.util.List;

public class AudioMatcher {
    private static final String TAG = "AudioMatcher";
    private static final int SAMPLE_RATE = 48000;
    private static final int BUFFER_SIZE = 2048;
    private static final float C0_SILENCE_THRESHOLD = -40.0f; // Log energy threshold for silence
    private static final float ENERGY_THRESHOLD = 0.01f; // VAD energy threshold
    private static final float ZCR_THRESHOLD = 0.1f; // VAD zero-crossing rate threshold
    private static final int MFCC_SIZE = 13; // Number of MFCC coefficients

    // Pre-emphasis filter
    public static float[] preEmphasis(float[] signal) {
        if (signal == null || signal.length < 2) return signal;
        float[] result = new float[signal.length];
        result[0] = signal[0];
        for (int i = 1; i < signal.length; i++) {
            result[i] = signal[i] - 0.95f * signal[i - 1];
        }
        return result;
    }

    // Hamming window (used by TarsosDSP, but included for completeness)
    public static float[] hammingWindow(float[] frame) {
        if (frame == null || frame.length < 2) return frame;
        float[] result = new float[frame.length];
        for (int i = 0; i < frame.length; i++) {
            double angle = 2.0 * Math.PI * i / (frame.length - 1);
            result[i] = (float) (frame[i] * (0.54 - 0.46 * Math.cos(angle)));
        }
        return result;
    }

    // Voice Activity Detection (VAD)
    public static boolean isSilentFrame(float[] frame, float[] mfcc) {
        if (frame == null || mfcc == null || mfcc.length == 0) return true; // Treat as silent if data is invalid
        // Compute energy
        float energy = 0.0f;
        for (float sample : frame) {
            energy += sample * sample;
        }
        energy /= (frame.length > 0 ? frame.length : 1);

        // Compute zero-crossing rate (ZCR)
        int zcrCount = 0;
        for (int i = 1; i < frame.length; i++) {
            if ((frame[i - 1] >= 0 && frame[i] < 0) || (frame[i - 1] < 0 && frame[i] >= 0)) {
                zcrCount++;
            }
        }
        float zcr = (frame.length > 0) ? (float) zcrCount / frame.length : 0.0f;

        // Check C0 (log energy) and VAD thresholds
        boolean isSilent = mfcc[0] < C0_SILENCE_THRESHOLD || energy < ENERGY_THRESHOLD || zcr > ZCR_THRESHOLD;
        Log.d(TAG, String.format("VAD: C0=%f, Energy=%f, ZCR=%f, Silent=%b", mfcc[0], energy, zcr, isSilent));
        return isSilent;
    }

    // Trim silence from MFCC sequence
    public static List<float[]> trimSilence(List<float[]> mfccSeq, float[] audio) {
        if (mfccSeq == null || mfccSeq.isEmpty()) {
            Log.d(TAG, "trimSilence: mfccSeq is null or empty, returning empty list.");
            return new ArrayList<>();
        }
        if (audio == null || audio.length < BUFFER_SIZE) {
            Log.d(TAG, "trimSilence: audio data is null or too short for a frame. Audio length: " + (audio != null ? audio.length : "null") + ", returning original mfccSeq as fallback or empty if it was meant to be processed.");
            // Depending on use case, either return mfccSeq as is, or an empty list if processing is critical.
            // For now, returning empty as the expectation is to process if audio is valid.
            return new ArrayList<>();
        }

        List<float[]> trimmed = new ArrayList<>();
        int frameSize = BUFFER_SIZE;
        int hopSize = frameSize / 2;

        for (int i = 0; i < mfccSeq.size(); i++) {
            int start = i * hopSize;

            if (start >= audio.length) {
                Log.w(TAG, String.format("trimSilence: Calculated start (%d) is beyond audio length (%d) for mfccSeq index %d. Stopping further processing for this audio.", start, audio.length, i));
                break;
            }
            if (start + 1 > audio.length) { // Need at least one sample
                 Log.w(TAG, String.format("trimSilence: Not enough audio data left (start %d, audio length %d) for mfccSeq index %d. Stopping.", start, audio.length, i));
                 break;
            }

            float[] frameForVAD = new float[frameSize];
            int lenToCopy = Math.min(frameSize, audio.length - start);

            if (lenToCopy <= 0) {
                Log.w(TAG, String.format("trimSilence: Calculated lenToCopy is %d for mfccSeq index %d. Skipping frame and stopping.", lenToCopy, i));
                break;
            }

            System.arraycopy(audio, start, frameForVAD, 0, lenToCopy);

            if (!isSilentFrame(frameForVAD, mfccSeq.get(i))) {
                trimmed.add(mfccSeq.get(i));
            }
        }
        Log.d(TAG, "trimSilence: Input mfccSeq size: " + mfccSeq.size() + ", audio length: " + audio.length + ", trimmed size: " + trimmed.size());
        return trimmed;
    }

    // Extract MFCC using TarsosDSP
    public static List<float[]> extractMFCC(float[] audioData) {
        List<float[]> mfccSeq = new ArrayList<>();
        if (audioData == null || audioData.length == 0) return mfccSeq;

        be.tarsos.dsp.io.TarsosDSPAudioFormat format = new be.tarsos.dsp.io.TarsosDSPAudioFormat(
            SAMPLE_RATE, 16, 1, true, false
        );
        MFCC mfccProcessor = new MFCC(BUFFER_SIZE, SAMPLE_RATE, MFCC_SIZE, 40, 50.0f, 8000.0f);

        for (int i = 0; i + BUFFER_SIZE <= audioData.length; i += BUFFER_SIZE / 2) {
            float[] frame = new float[BUFFER_SIZE];
            System.arraycopy(audioData, i, frame, 0, BUFFER_SIZE);
            frame = preEmphasis(frame);
            frame = hammingWindow(frame);

            be.tarsos.dsp.AudioEvent audioEvent = new be.tarsos.dsp.AudioEvent(format);
            audioEvent.setFloatBuffer(frame);
            mfccProcessor.process(audioEvent);
            float[] mfcc = mfccProcessor.getMFCC();

            if (mfcc != null && mfcc.length == MFCC_SIZE) {
                mfccSeq.add(mfcc);
            } else {
                Log.w(TAG, String.format("Invalid MFCC frame at index %d", i));
            }
        }
        return mfccSeq;
    }

    // Cosine similarity for DTW
    public static float cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length != vec2.length || vec1.length == 0) return 0.0f;
        double dot = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        for (int i = 0; i < vec1.length; i++) {
            dot += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }
        double denom = Math.sqrt(norm1) * Math.sqrt(norm2);
        if (denom < 1e-9) {
            return (norm1 < 1e-9 && norm2 < 1e-9) ? 1.0f : 0.0f;
        }
        return (float) (dot / denom);
    }

    // DTW with silence trimming for reference sequence (mfccSeq2)
    public static float computeDTW(List<float[]> mfccSeq1, List<float[]> mfccSeq2, float[] audio2) {
        if (mfccSeq1 == null || mfccSeq1.isEmpty() || mfccSeq2 == null || mfccSeq2.isEmpty()) {
            Log.e(TAG, String.format("computeDTW: Empty or null MFCC sequence. Seq1 null? %b, empty? %b. Seq2 null? %b, empty? %b.",
                    mfccSeq1 == null, mfccSeq1 != null && mfccSeq1.isEmpty(),
                    mfccSeq2 == null, mfccSeq2 != null && mfccSeq2.isEmpty()));
            return 0.0f;
        }

        // mfccSeq1 (live utterance) is used as-is.
        List<float[]> trimmedSeq1 = mfccSeq1; // No trimming for live sequence here
        // Trim reference sequence (mfccSeq2) using its full audio (audio2)
        List<float[]> trimmedSeq2 = trimSilence(mfccSeq2, audio2);

        if (trimmedSeq1.isEmpty() || trimmedSeq2.isEmpty()) {
            Log.d(TAG, String.format("DTW: One or both sequences became empty after processing/trimming. Trimmed sizes: seq1=%d, seq2=%d",
                    trimmedSeq1.size(), trimmedSeq2.size()));
            return 0.0f;
        }

        int R = trimmedSeq1.size() + 1;
        int C = trimmedSeq2.size() + 1;
        float[][] dp = new float[R][C];
        for (int i = 0; i < R; i++) {
            for (int j = 0; j < C; j++) {
                dp[i][j] = Float.POSITIVE_INFINITY;
            }
        }
        dp[0][0] = 0.0f;

        for (int i = 1; i < R; i++) {
            for (int j = 1; j < C; j++) {
                float cost = 1.0f - cosineSimilarity(trimmedSeq1.get(i - 1), trimmedSeq2.get(j - 1));
                dp[i][j] = cost + Math.min(Math.min(dp[i - 1][j], dp[i][j - 1]), dp[i - 1][j - 1]);
            }
        }

        float denom = trimmedSeq1.size() + trimmedSeq2.size();
        float similarity = 0.0f;
        if (denom > 1e-6f) {
            similarity = 1.0f - (dp[R - 1][C - 1] / denom);
        } else if (dp[R - 1][C - 1] < 1e-6f && (R - 1 > 0 || C - 1 > 0)) {
            similarity = 1.0f; // Sequences were identical and very short, or empty leading to zero cost
        }
        similarity = Math.max(0.0f, Math.min(1.0f, similarity));

        Log.d(TAG, String.format("DTW cost: %f, denom: %f, similarity: %f. Trimmed sizes: seq1=%d, seq2=%d",
                dp[R - 1][C - 1], denom, similarity, trimmedSeq1.size(), trimmedSeq2.size()));
        return similarity;
    }
}
