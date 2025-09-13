#include <jni.h>
#include <android/log.h>
#include <vector>
#include <complex>
#include <cmath>
#include <algorithm>
#include <limits>

#define LOG_TAG "MantraMatcher"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using cd = std::complex<double>;
const double PI = std::acos(-1.0);
const int SAMPLE_RATE = 48000;
const float C0_SILENCE_THRESHOLD = -40.0f; // Tuned for silence (negative for low log energy)
const float ENERGY_THRESHOLD = 0.01f; // VAD energy threshold
const float ZCR_THRESHOLD = 0.1f; // VAD zero-crossing rate threshold

// Forward declaration for cosineSimilarity
float cosineSimilarity(const std::vector<float>& vec1, const std::vector<float>& vec2);

// Fast Fourier Transform (Cooley-Tukey radix-2)
void fft(std::vector<cd>& a, bool invert) {
    const auto n = static_cast<int>(a.size());
    int lg_n = 0;
    while ((1 << lg_n) < n) ++lg_n;

    for (int i = 0; i < n; ++i) {
        int rev = 0;
        for (int j = 0; j < lg_n; ++j) {
            if (i & (1 << j)) rev |= (1 << (lg_n - 1 - j));
        }
        if (i < rev) std::swap(a[i], a[rev]);
    }

    for (int len = 2; len <= n; len <<= 1) {
        double ang = 2.0 * PI / len * (invert ? -1.0 : 1.0);
        cd wlen(std::cos(ang), std::sin(ang));
        for (int i = 0; i < n; i += len) {
            cd w(1.0);
            for (int j = 0; j < len / 2; ++j) {
                cd u = a[i + j];
                cd v = a[i + j + len / 2] * w;
                a[i + j] = u + v;
                a[i + j + len / 2] = u - v;
                w *= wlen;
            }
        }
    }

    if (invert) {
        for (cd& x : a) x /= static_cast<double>(n);
    }
}

// Compute power spectrum from FFT
std::vector<double> power_spectrum(const std::vector<float>& frame) {
    const size_t n = frame.size();
    size_t fft_size = 1;
    while (fft_size < n) fft_size <<= 1;

    std::vector<cd> fft_input(fft_size, cd(0.0, 0.0));
    for (size_t i = 0; i < n; ++i) fft_input[i] = cd(frame[i], 0.0);

    fft(fft_input, false);

    const size_t half = fft_size / 2;
    std::vector<double> power(half + 1, 0.0);
    for (size_t i = 0; i <= half; ++i) {
        power[i] = std::norm(fft_input[i]) / static_cast<double>(fft_size);
    }
    return power;
}

// Pre-emphasis
void pre_emphasis(std::vector<float>& signal) {
    if (signal.size() < 2) return;
    for (size_t i = signal.size() - 1; i > 0; --i) {
        signal[i] -= 0.95f * signal[i - 1];
    }
}

// Hamming window
void hamming_window(std::vector<float>& frame) {
    const size_t n = frame.size();
    if (n < 2) return;
    for (size_t i = 0; i < n; ++i) {
        const auto idx = static_cast<double>(i);
        const auto denom = static_cast<double>(n - 1);
        frame[i] *= static_cast<float>(0.54 - 0.46 * std::cos(2.0 * PI * idx / denom));
    }
}

// Mel frequency conversion
double hz_to_mel(double hz) {
    return 2595.0 * std::log10(1.0 + hz / 700.0);
}

double mel_to_hz(double mel) {
    return 700.0 * (std::pow(10.0, mel / 2595.0) - 1.0);
}

// Create mel filterbanks (40 filters for 48kHz, 13 MFCCs)
std::vector<std::vector<double>> create_mel_filterbanks(int num_filters, int fft_size, int sample_rate) {
    const double low_freq_mel = 0.0;
    const double high_freq_mel = hz_to_mel(static_cast<double>(sample_rate) / 2.0);

    std::vector<double> mel_points(static_cast<size_t>(num_filters + 2));
    for (int i = 0; i < num_filters + 2; ++i) {
        mel_points[static_cast<size_t>(i)] = low_freq_mel + (high_freq_mel - low_freq_mel) * i / static_cast<double>(num_filters + 1);
    }
    std::vector<double> hz_points(static_cast<size_t>(num_filters + 2));
    for (int i = 0; i < num_filters + 2; ++i) {
        hz_points[static_cast<size_t>(i)] = mel_to_hz(mel_points[static_cast<size_t>(i)]);
    }

    std::vector<int> bin(static_cast<size_t>(num_filters + 2));
    for (int i = 0; i < num_filters + 2; ++i) {
        bin[static_cast<size_t>(i)] = static_cast<int>(std::floor((static_cast<double>(fft_size) + 1.0) * hz_points[static_cast<size_t>(i)] / static_cast<double>(sample_rate)));
    }

    const auto half = static_cast<size_t>(fft_size / 2 + 1);
    std::vector<std::vector<double>> filters(static_cast<size_t>(num_filters), std::vector<double>(half, 0.0));

    for (int m = 1; m <= num_filters; ++m) {
        int start = bin[static_cast<size_t>(m - 1)];
        int center = bin[static_cast<size_t>(m)];
        int end = bin[static_cast<size_t>(m + 1)];

        start = std::max(0, start);
        center = std::max(start, center);
        end = std::max(center, end);

        for (int k = start; k < center && k < static_cast<int>(half); ++k) {
            filters[static_cast<size_t>(m - 1)][static_cast<size_t>(k)] = (static_cast<double>(k) - start) / static_cast<double>(center - start + 1e-12);
        }
        for (int k = center; k < end && k < static_cast<int>(half); ++k) {
            filters[static_cast<size_t>(m - 1)][static_cast<size_t>(k)] = (end - static_cast<double>(k)) / static_cast<double>(end - center + 1e-12);
        }
    }
    return filters;
}

// Apply mel filters
std::vector<double> apply_mel_filters(const std::vector<double>& power, const std::vector<std::vector<double>>& filterbanks) {
    const size_t num_filters = filterbanks.size();
    std::vector<double> mel_energies(num_filters, 0.0);
    const size_t pw_size = power.size();

    for (size_t m = 0; m < num_filters; ++m) {
        const auto& filt = filterbanks[m];
        const size_t filt_size = filt.size();
        const size_t lim = std::min(pw_size, filt_size);
        double acc = 0.0;
        for (size_t k = 0; k < lim; ++k) {
            acc += power[k] * filt[k];
        }
        if (acc > 0.0) mel_energies[m] = std::log(acc);
        else mel_energies[m] = std::log(1e-10);
    }
    return mel_energies;
}

// DCT for MFCC (simple cos-based, for 13 coefficients)
std::vector<float> dct(const std::vector<double>& mel_energies) {
    const int num_mfcc = 13;
    const size_t num_filters = mel_energies.size();
    std::vector<float> mfcc(static_cast<size_t>(num_mfcc), 0.0f);
    if (num_filters == 0) return mfcc;

    for (int k = 0; k < num_mfcc; ++k) {
        double sum = 0.0;
        for (size_t m = 0; m < num_filters; ++m) {
            sum += mel_energies[m] * std::cos(PI * static_cast<double>(k) * (static_cast<double>(m) + 0.5) / static_cast<double>(num_filters));
        }
        mfcc[static_cast<size_t>(k)] = static_cast<float>(sum);
    }
    return mfcc;
}

// Compute short-term energy for VAD
float compute_energy(const std::vector<float>& frame) {
    if (frame.empty()) return 0.0f;
    double energy = 0.0;
    for (float sample : frame) {
        energy += static_cast<double>(sample) * static_cast<double>(sample);
    }
    return static_cast<float>(energy / static_cast<double>(frame.size()));
}

// Compute zero-crossing rate for VAD
float compute_zcr(const std::vector<float>& frame) {
    if (frame.size() < 2) return 0.0f;
    size_t zcr = 0;
    for (size_t i = 1; i < frame.size(); ++i) {
        if ((frame[i] >= 0.0f && frame[i - 1] < 0.0f) || (frame[i] < 0.0f && frame[i - 1] >= 0.0f)) {
            ++zcr;
        }
    }
    return static_cast<float>(zcr) / static_cast<float>(frame.size());
}

// Trim silence from MFCC sequence (start and end) using C0
std::vector<std::vector<float>> trim_silence(const std::vector<std::vector<float>>& mfcc_seq) {
    if (mfcc_seq.empty()) return {};

    using diff_type = std::vector<std::vector<float>>::difference_type;
    size_t start = 0;
    size_t end = mfcc_seq.size();

    for (size_t i = 0; i < mfcc_seq.size(); ++i) {
        if (!mfcc_seq[i].empty() && mfcc_seq[i][0] > C0_SILENCE_THRESHOLD) {
            start = i;
            break;
        }
    }
    for (size_t i = mfcc_seq.size(); i > start; --i) {
        if (!mfcc_seq[i - 1].empty() && mfcc_seq[i - 1][0] > C0_SILENCE_THRESHOLD) {
            end = i;
            break;
        }
    }
    if (start >= end) return {};

    if (start > static_cast<size_t>(std::numeric_limits<diff_type>::max()) ||
        end > static_cast<size_t>(std::numeric_limits<diff_type>::max())) {
        LOGE("Trim indices exceed iterator difference_type limit: start=%zu, end=%zu", start, end);
        return {};
    }

    LOGD("Trimmed MFCC sequence: start=%zu, end=%zu, original size=%zu", start, end, mfcc_seq.size());
    return {
            mfcc_seq.begin() + static_cast<diff_type>(start),
            mfcc_seq.begin() + static_cast<diff_type>(end)
    };
}

// MFCC extraction for a frame (with VAD and logging)
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mkproject_javaPackages_MantraRecognizer_extractMFCC(JNIEnv* env, jobject /* this */, jfloatArray audioData) {
    jsize len = env->GetArrayLength(audioData);
    if (len <= 0) {
        LOGE("Invalid or empty audio data array: length=%d", len);
        return env->NewFloatArray(0);
    }

    const auto ulen = static_cast<size_t>(len);
    std::vector<float> frame(ulen);
    env->GetFloatArrayRegion(audioData, 0, len, frame.data());

    // VAD checks (Energy/ZCR)
    float energy = compute_energy(frame);
    float zcr = compute_zcr(frame);
    if (energy < ENERGY_THRESHOLD || zcr < ZCR_THRESHOLD) {
        return env->NewFloatArray(0);
    }

    // Pre-emphasis
    pre_emphasis(frame);

    // Hamming window
    hamming_window(frame);

    // Power spectrum via FFT
    std::vector<double> power = power_spectrum(frame);
    if (power.empty()) {
        LOGE("Power spectrum computation failed or resulted in empty output.");
        return env->NewFloatArray(0);
    }

    // Mel filterbanks (40 filters)
    auto fft_size_for_filters = static_cast<int>(power.size()) * 2 - 2;
    if (fft_size_for_filters < 2) fft_size_for_filters = 2;
    std::vector<std::vector<double>> filterbanks = create_mel_filterbanks(40, fft_size_for_filters, SAMPLE_RATE);

    // Apply filters
    std::vector<double> mel_energies = apply_mel_filters(power, filterbanks);

    // DCT to get 13 MFCCs
    std::vector<float> mfcc = dct(mel_energies);

    // Final VAD check on C0
    if (!mfcc.empty() && mfcc[0] <= C0_SILENCE_THRESHOLD) {
        return env->NewFloatArray(0);
    }

    if (!mfcc.empty()) {
        LOGD("MFCC C0: %f, Energy: %f, ZCR: %f", mfcc[0], energy, zcr);
    }

    auto result = env->NewFloatArray(static_cast<jsize>(mfcc.size()));
    if (result == nullptr) {
        LOGE("Failed to allocate MFCC array");
        return nullptr;
    }
    env->SetFloatArrayRegion(result, 0, static_cast<jsize>(mfcc.size()), mfcc.data());
    return result;
}

// DTW with silence trimming
extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_mkproject_javaPackages_MantraRecognizer_computeDTW(JNIEnv* env, jobject /* this */, jobjectArray mfccSeq1, jobjectArray mfccSeq2) {
    jsize len1_jsize = env->GetArrayLength(mfccSeq1);
    jsize len2_jsize = env->GetArrayLength(mfccSeq2);

    if (len1_jsize <= 0 || len2_jsize <= 0) {
        LOGE("Empty MFCC sequence: len1=%d, len2=%d", len1_jsize, len2_jsize);
        return 0.0f;
    }

    const auto ulen1 = static_cast<size_t>(len1_jsize);
    const auto ulen2 = static_cast<size_t>(len2_jsize);

    std::vector<std::vector<float>> seq1(ulen1), seq2(ulen2);

    for (jsize i = 0; i < len1_jsize; ++i) {
        auto frame_obj = static_cast<jfloatArray>(env->GetObjectArrayElement(mfccSeq1, i));
        if (frame_obj == nullptr) {
            LOGE("Null MFCC frame in seq1 at index %d", i);
            return 0.0f;
        }
        jsize frameLen = env->GetArrayLength(frame_obj);
        if (frameLen != 13) {
            LOGE("Invalid MFCC frame length in seq1 at index %d: %d (expected 13)", i, frameLen);
            env->DeleteLocalRef(frame_obj);
            return 0.0f;
        }
        seq1[static_cast<size_t>(i)].resize(static_cast<size_t>(frameLen));
        env->GetFloatArrayRegion(frame_obj, 0, frameLen, seq1[static_cast<size_t>(i)].data());
        env->DeleteLocalRef(frame_obj);
    }

    for (jsize i = 0; i < len2_jsize; ++i) {
        auto frame_obj = static_cast<jfloatArray>(env->GetObjectArrayElement(mfccSeq2, i));
        if (frame_obj == nullptr) {
            LOGE("Null MFCC frame in seq2 at index %d", i);
            return 0.0f;
        }
        jsize frameLen = env->GetArrayLength(frame_obj);
        if (frameLen != 13) {
            LOGE("Invalid MFCC frame length in seq2 at index %d: %d (expected 13)", i, frameLen);
            env->DeleteLocalRef(frame_obj);
            return 0.0f;
        }
        seq2[static_cast<size_t>(i)].resize(static_cast<size_t>(frameLen));
        env->GetFloatArrayRegion(frame_obj, 0, frameLen, seq2[static_cast<size_t>(i)].data());
        env->DeleteLocalRef(frame_obj);
    }

    // Trim silence
    std::vector<std::vector<float>> trimmed_seq1 = trim_silence(seq1);
    std::vector<std::vector<float>> trimmed_seq2 = trim_silence(seq2);

    if (trimmed_seq1.empty() || trimmed_seq2.empty()) {
        LOGD("DTW: One or both sequences trimmed to empty. Trimmed sizes: seq1=%zu, seq2=%zu", trimmed_seq1.size(), trimmed_seq2.size());
        return 0.0f;
    }

    // Initialize DP table with infinities
    const size_t R = trimmed_seq1.size() + 1;
    const size_t C = trimmed_seq2.size() + 1;
    auto dp = std::vector<std::vector<float>>(R, std::vector<float>(C, std::numeric_limits<float>::infinity()));
    dp[0][0] = 0.0f;

    for (size_t i = 1; i < R; ++i) {
        for (size_t j = 1; j < C; ++j) {
            float cost = 1.0f - cosineSimilarity(trimmed_seq1[i - 1], trimmed_seq2[j - 1]);
            float val_a = dp[i - 1][j];
            float val_b = dp[i][j - 1];
            float val_c = dp[i - 1][j - 1];
            dp[i][j] = cost + std::min({val_a, val_b, val_c});
        }
    }

    const auto denom_val = static_cast<float>((trimmed_seq1.size() + trimmed_seq2.size()));
    float similarity = 0.0f;
    if (denom_val > 1e-6f) {
        similarity = 1.0f - (dp[R - 1][C - 1] / denom_val);
    } else if (dp[R-1][C-1] < 1e-6f && (R-1 > 0 || C-1 > 0)) {
        similarity = 1.0f;
    } else {
        similarity = 0.0f;
    }
    similarity = std::max(0.0f, std::min(1.0f, similarity));

    LOGD("DTW cost: %f, denom: %f, similarity: %f. Trimmed sizes: seq1=%zu, seq2=%zu", dp[R-1][C-1], denom_val, similarity, trimmed_seq1.size(), trimmed_seq2.size());
    return similarity;
}

// Cosine similarity for DTW
float cosineSimilarity(const std::vector<float>& vec1, const std::vector<float>& vec2) {
    if (vec1.size() != vec2.size() || vec1.empty()) {
        return 0.0f;
    }
    double dot = 0.0;
    double norm1 = 0.0;
    double norm2 = 0.0;
    for (size_t i = 0; i < vec1.size(); ++i) {
        dot += static_cast<double>(vec1[i]) * static_cast<double>(vec2[i]);
        norm1 += static_cast<double>(vec1[i]) * static_cast<double>(vec1[i]);
        norm2 += static_cast<double>(vec2[i]) * static_cast<double>(vec2[i]);
    }
    const double denom_cs = std::sqrt(norm1) * std::sqrt(norm2);
    if (denom_cs < 1e-9) {
        return (norm1 < 1e-9 && norm2 < 1e-9) ? 1.0f : 0.0f;
    }
    return static_cast<float>(dot / denom_cs);
}
