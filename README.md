# 🎙️ Mantra Match – Real-Time Mantra Recognition Android App

This Android app uses **real-time audio processing** with [TarsosDSP](https://github.com/JorenSix/TarsosDSP) to recognize when a spoken mantra matches a previously recorded version. It applies **MFCC (Mel-Frequency Cepstral Coefficients)** and **cosine similarity** to count accurate repetitions of the mantra and triggers an alarm when a match limit is reached.

---

## ✨ Features

- 🎤 Record custom mantras (saved as `.wav` files)
- 🧠 Real-time mantra recognition using MFCCs
- 🧮 Match counter with threshold trigger
- 🔔 Audio alert when match count is reached
- 🧘 Built using **Jetpack Compose UI** with modern Android APIs
- 📁 Offline storage of mantras for future use
- 📱 Clean UI with dropdown selector and status updates

---

## 📸 Screenshots

> _You can add screenshots here if available_

---

## 🏗️ Architecture Overview

| Layer         | Technology                            |
|---------------|--------------------------------------|
| UI            | Jetpack Compose                      |
| Audio Input   | AudioRecord + AndroidAudioInputStream |
| DSP/Features  | TarsosDSP MFCC extraction            |
| Storage       | Internal app storage (`/files/mantras`) |
| Comparison    | Cosine similarity (custom logic)     |
| Permissions   | Android `ActivityResultContracts`    |

---

## 🧰 Tech Stack

- **Kotlin** (Android)
- **Jetpack Compose**
- **TarsosDSP** (via `.jar`)
- **MFCC Feature Extraction**
- **AudioRecord / WAV Encoding**
- **Cosine Similarity Matching**

---

## 🚀 How It Works

1. **Recording Mantras**
    - Tap `Record New Mantra`
    - A `.wav` file is created and saved in internal storage

2. **Matching in Real-Time**
    - Select a mantra from the dropdown
    - Enter a match count threshold (e.g., 5)
    - Press `START`
    - App listens and extracts MFCCs live
    - Compares with stored mantra features
    - When the count of high-similarity matches reaches the limit, an alarm is triggered

---

## 🧪 Audio Processing Logic

- **MFCC Extraction**
    - 13 Coefficients
    - 40 Mel bands
    - Window size: 2048
    - Sample rate: 16,000 Hz

- **Matching Criteria**
    - Cosine similarity > `0.9` is considered a match
    - Real-time matching is done on each incoming audio frame

---

## 📁 File Structure

```
app/
 ┣ MainActivity.kt         # Core logic
 ┣ MainScreen.kt           # Jetpack Compose UI
 ┣ ui/theme/               # Material Theme
 ┣ libs/
 ┃ ┗ TarsosDSP-Android-latest.jar
 ┣ files/
 ┃ ┗ 🎵 Saved mantra .wav files
```

---

## ✅ Permissions

Ensure the app has the following permissions:

```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```
App requests runtime microphone access via ActivityResultContracts.RequestPermission().

---

## 🛠️ Setup & Build

### 🔧 Requirements
- Android Studio Giraffe or later
- Android SDK 28+
- Kotlin 1.9+
- TarsosDSP JAR (placed inside `libs/` folder)

### ▶️ Build Instructions
1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Run on a real device (recommended)

---

## 🚨 Known Limitations

- Only one mantra is matched at a time
- MFCC comparison uses only the first frame for simplicity
- No automatic silence detection or speech segmentation
- Cosine similarity threshold is fixed (> 0.9)

---

## 🧩 Possible Improvements

- Use averaged MFCC vectors for more robust matching
- Support multiple simultaneous mantra matching
- Visual waveform or pitch feedback in real-time
- ViewModel integration for clean state management
- Export/import mantra files externally

---

## 👨‍💻 Author

Developed by LIKHITH

Feel free to replace this with a link to your portfolio or GitHub profile.
