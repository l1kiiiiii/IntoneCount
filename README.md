# 🎙️ Mantra Match – Real-Time Mantra Recognition Android App

This Android app uses **real-time audio processing** with [TarsosDSP](https://github.com/JorenSix/TarsosDSP) to recognize when a spoken mantra matches a previously recorded version. It applies **MFCC (Mel-Frequency Cepstral Coefficients)** and **cosine similarity** to count accurate repetitions of the mantra and triggers an alarm when a match limit is reached.

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

| Layer         | Technology                      |
|---------------|----------------------------------|
| UI            | Jetpack Compose                 |
| Audio Input   | AudioRecord + AndroidAudioInputStream |
| DSP/Features  | TarsosDSP MFCC extraction       |
| Storage       | Internal app storage (`/files/mantras`) |
| Comparison    | Cosine similarity (custom logic)|
| Permissions   | Android `ActivityResultContracts` |

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
    - Select a mantra from dropdown
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

  
