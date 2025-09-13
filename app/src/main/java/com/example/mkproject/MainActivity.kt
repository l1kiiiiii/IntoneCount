package com.example.mkproject

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.mkproject.javaPackages.MantraRecognizer

private const val TAG = "MantraMatchApp"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")
        setContent {
            MantraMatchApp()
        }
    }
}

@Composable
fun MantraMatchApp() {
    Log.d(TAG, "MantraMatchApp Composable executing")
    val context = LocalContext.current
    val recognizer = remember {
        Log.d(TAG, "Initializing MantraRecognizer")
        MantraRecognizer(context)
    }

    var status by rememberSaveable { mutableStateOf("Stopped") }
    var matchCount by rememberSaveable { mutableIntStateOf(0) }
    var savedMantras by rememberSaveable { mutableStateOf(listOf<String>()) }
    var selectedMantra by rememberSaveable { mutableStateOf("") }
    var mantraNameText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var matchLimitTFV by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue("10"))
    }
    var similarityThresholdTFV by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue("0.7"))
    }
    var showAlarm by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var isRecognizing by rememberSaveable { mutableStateOf(false) }
    var isRecording by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Permission result received. Granted: $isGranted")
        if (!isGranted) {
            errorMessage = "Microphone permission denied. Please enable it in settings."
            showError = true
            Log.w(TAG, "Microphone permission denied by user.")
        }
    }

    LaunchedEffect(Unit, key2 = "permissionCheck") {
        Log.d(TAG, "LaunchedEffect: Checking/Requesting RECORD_AUDIO permission")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            Log.d(TAG, "LaunchedEffect: RECORD_AUDIO permission already granted.")
        }
    }

    LaunchedEffect(Unit, key2 = "loadMantras") {
        Log.d(TAG, "LaunchedEffect: Loading saved mantras")
        recognizer.loadSavedMantras()
        savedMantras = recognizer.savedMantras
        Log.d(TAG, "LaunchedEffect: Saved mantras loaded: $savedMantras")
    }

    LaunchedEffect(recognizer) {
        Log.d(TAG, "LaunchedEffect: Setting MantraRecognizer listener")
        recognizer.setListener(object : MantraRecognizer.MantraListener {
            override fun onStatusUpdate(newStatus: String) {
                Log.d(TAG, "MantraListener: onStatusUpdate - New status: $newStatus")
                status = newStatus
            }
            override fun onMatchCountUpdate(count: Int) {
                Log.d(TAG, "MantraListener: onMatchCountUpdate - Count: $count")
                matchCount = count
            }
            override fun onAlarmTriggered() {
                Log.d(TAG, "MantraListener: onAlarmTriggered")
                showAlarm = true
            }
            override fun onError(error: String) {
                Log.e(TAG, "MantraListener: onError - Error: $error")
                errorMessage = error
                showError = true
            }
            override fun onMantrasUpdated() {
                Log.d(TAG, "MantraListener: onMantrasUpdated - Reloading mantras")
                savedMantras = recognizer.savedMantras
                Log.d(TAG, "MantraListener: Mantras reloaded: $savedMantras")
            }
            override fun onRecognizingStateChanged(recognizing: Boolean) {
                Log.d(TAG, "MantraListener: onRecognizingStateChanged - Recognizing: $recognizing")
                isRecognizing = recognizing
            }
            override fun onRecordingStateChanged(recording: Boolean) {
                Log.d(TAG, "MantraListener: onRecordingStateChanged - Recording: $recording")
                isRecording = recording
            }
        })
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(text = "Mantra Match", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = "Status: $status")
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Matches: $matchCount")
            Spacer(modifier = Modifier.height(16.dp))

            var expanded by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = {
                    Log.d(TAG, "Select Mantra button clicked, expanding dropdown.")
                    expanded = true
                }) {
                    Text(selectedMantra.ifEmpty { "Select Mantra" })
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    savedMantras.forEach { mantra ->
                        DropdownMenuItem(
                            text = { Text(mantra) },
                            onClick = {
                                Log.d(TAG, "Mantra selected from dropdown: $mantra")
                                selectedMantra = mantra
                                expanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = matchLimitTFV,
                onValueChange = {
                    matchLimitTFV = it
                    Log.d(TAG, "Match Limit OutlinedTextField onValueChange: ${it.text}")
                },
                label = { Text("Match Limit") },
                isError = matchLimitTFV.text.toIntOrNull()?.let { it <= 0 } ?: true
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = similarityThresholdTFV,
                onValueChange = {
                    similarityThresholdTFV = it
                    Log.d(TAG, "Similarity Threshold OutlinedTextField onValueChange: ${it.text}")
                },
                label = { Text("Similarity Threshold (0.0-1.0)") },
                isError = similarityThresholdTFV.text.toFloatOrNull()?.let { it < 0.0f || it > 1.0f } ?: true
            )
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    Log.d(TAG, "Start/Stop Listening button clicked. Current isRecognizing: $isRecognizing")
                    if (isRecognizing) {
                        Log.d(TAG, "Attempting to stop recognition.")
                        recognizer.stopRecognition()
                    } else {
                        val limit = matchLimitTFV.text.toIntOrNull() ?: 0
                        val thresh = similarityThresholdTFV.text.toFloatOrNull() ?: 0.7f
                        Log.d(TAG, "Attempting to start recognition. Mantra: $selectedMantra, Limit: $limit, Threshold: $thresh")
                        if (selectedMantra.isEmpty()) {
                            errorMessage = "Please select a mantra."
                            showError = true
                            Log.w(TAG, "Start recognition failed: No mantra selected.")
                        } else if (limit <= 0) {
                            errorMessage = "Please enter a valid match limit."
                            showError = true
                            Log.w(TAG, "Start recognition failed: Invalid match limit - $limit")
                        } else if (thresh < 0.0f || thresh > 1.0f) {
                            errorMessage = "Similarity threshold must be between 0.0 and 1.0."
                            showError = true
                            Log.w(TAG, "Start recognition failed: Invalid threshold - $thresh")
                        } else {
                            recognizer.startRecognition(selectedMantra, limit, thresh)
                        }
                    }
                },
                enabled = !isRecording && (isRecognizing || selectedMantra.isNotEmpty())
            ) {
                Text(if (isRecognizing) "Stop Listening" else "Start Listening")
            }
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = mantraNameText,
                onValueChange = {
                    mantraNameText = it
                    Log.d(TAG, "Mantra Name OutlinedTextField onValueChange: ${it.text}")
                },
                label = { Text("Mantra Name") },
                isError = mantraNameText.text.trim().isEmpty()
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    Log.d(TAG, "Record button clicked.")
                    val name = mantraNameText.text.trim()
                    if (name.isEmpty()) {
                        errorMessage = "Please enter a valid mantra name."
                        showError = true
                        Log.w(TAG, "Record mantra failed: Mantra name is empty.")
                    } else {
                        Log.d(TAG, "Attempting to record mantra: $name")
                        recognizer.recordMantra(name)
                    }
                },
                enabled = !isRecognizing && !isRecording
            ) {
                Text(if (isRecording) "Recording..." else "Record")
            }
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    Log.d(TAG, "Stop Recording button clicked.")
                    recognizer.stopRecording()
                },
                enabled = isRecording
            ) {
                Text("Stop Recording")
            }
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    Log.d(TAG, "Delete Selected Mantra button clicked. Selected: $selectedMantra")
                    if (selectedMantra.isEmpty()) {
                        errorMessage = "Please select a mantra to delete."
                        showError = true
                        Log.w(TAG, "Delete mantra failed: No mantra selected.")
                    } else {
                        recognizer.deleteMantra(selectedMantra)
                    }
                },
                enabled = !isRecognizing && !isRecording && selectedMantra.isNotEmpty()
            ) {
                Text("Delete Selected")
            }
        }
    }

    if (showAlarm) {
        Log.d(TAG, "Showing Alarm dialog for mantra: $selectedMantra")
        AlertDialog(
            onDismissRequest = {
                Log.d(TAG, "Alarm AlertDialog: Dismissed (outside click or back press)")
                showAlarm = false
            },
            title = { Text("Mantra Limit Reached") },
            text = { Text("Reached the limit for '$selectedMantra'.") },
            confirmButton = {
                Button(onClick = {
                    Log.d(TAG, "Alarm AlertDialog: OK & Continue clicked.")
                    showAlarm = false
                    recognizer.resetMatchCount()
                    val limit = matchLimitTFV.text.toIntOrNull() ?: 0
                    val thresh = similarityThresholdTFV.text.toFloatOrNull() ?: 0.7f
                    if (selectedMantra.isNotEmpty() && limit > 0) {
                        Log.d(TAG, "Alarm AlertDialog: Restarting recognition for $selectedMantra, Limit: $limit, Threshold: $thresh")
                        recognizer.startRecognition(selectedMantra, limit, thresh)
                    } else {
                        Log.w(TAG, "Alarm AlertDialog: Not restarting recognition. SelectedMantra: $selectedMantra, Limit: $limit")
                    }
                }) {
                    Text("OK & Continue")
                }
            },
            dismissButton = {
                Button(onClick = {
                    Log.d(TAG, "Alarm AlertDialog: Stop clicked.")
                    showAlarm = false
                    recognizer.resetMatchCount()
                }) {
                    Text("Stop")
                }
            }
        )
    }

    if (showError) {
        Log.d(TAG, "Showing Error dialog: $errorMessage")
        AlertDialog(
            onDismissRequest = {
                Log.d(TAG, "Error AlertDialog: Dismissed (outside click or back press)")
                showError = false
            },
            title = { Text("Error") },
            text = { Text(errorMessage) },
            confirmButton = {
                Button(onClick = {
                    Log.d(TAG, "Error AlertDialog: OK clicked.")
                    showError = false
                }) {
                    Text("OK")
                }
            }
        )
    }
}