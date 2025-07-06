package com.example.mkproject.ui.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    mantras: List<String>,
    matchLimitText: String,
    onMatchLimitTextChange: (String) -> Unit,
    onRecordMantraClick: () -> Unit,
    onStartStopClick: (String, String) -> Unit,
    onStopRecordingClick: () -> Unit,
    matchCount: Int,
    processingStatus: String
) {
    var selectedMantra by remember { mutableStateOf(mantras.firstOrNull() ?: "") }
    var isMenuExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Mantra Recognition App",
                        fontSize = 20.sp
                    )
                }
            )
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Record New Mantra Button
                Button(
                    onClick = {
                        errorMessage = ""
                        onRecordMantraClick()
                    },
                    enabled = processingStatus != "Listening for mantra..." && processingStatus != "Recording mantra...",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Record New Mantra", fontSize = 16.sp)
                }

                // Stop Recording Button (visible when recording)
                if (processingStatus == "Recording mantra...") {
                    Button(
                        onClick = {
                            errorMessage = ""
                            onStopRecordingClick()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Stop Recording", fontSize = 16.sp)
                    }
                }

                // Mantra Selection Dropdown
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Select Mantra",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Box {
                        Button(
                            onClick = { isMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(selectedMantra.ifEmpty { "Choose..." }, fontSize = 16.sp)
                        }

                        DropdownMenu(
                            expanded = isMenuExpanded,
                            onDismissRequest = { isMenuExpanded = false }
                        ) {
                            mantras.forEach { mantra ->
                                DropdownMenuItem(
                                    text = { Text(mantra, fontSize = 16.sp) },
                                    onClick = {
                                        selectedMantra = mantra
                                        isMenuExpanded = false
                                        errorMessage = ""
                                    }
                                )
                            }
                        }
                    }
                }

                // Match Limit Input
                OutlinedTextField(
                    value = matchLimitText,
                    onValueChange = {
                        onMatchLimitTextChange(it)
                        errorMessage = ""
                    },
                    label = { Text("Match Limit", fontSize = 14.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = matchLimitText.isNotEmpty() && matchLimitText.toIntOrNull()?.let { it <= 0 } == true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Error Message for Invalid Input
                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                }

                // Start/Stop Button
                Button(
                    onClick = {
                        if (selectedMantra.isEmpty()) {
                            errorMessage = "Please select a mantra."
                            return@Button
                        }
                        if (matchLimitText.isEmpty() || matchLimitText.toIntOrNull()?.let { it <= 0 } == true) {
                            errorMessage = "Please enter a valid match limit (positive number)."
                            return@Button
                        }
                        errorMessage = ""
                        onStartStopClick(selectedMantra, matchLimitText)
                    },
                    enabled = processingStatus != "Recording mantra...",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (processingStatus == "Listening for mantra...") "STOP" else "START",
                        fontSize = 16.sp
                    )
                }

                // Match Count Display
                Text("Match Count: $matchCount", fontSize = 18.sp)

                // Processing Status
                Text("Status: $processingStatus", fontSize = 16.sp)
            }
        }
    )
}

@Preview(showSystemUi = true)
@Composable
fun MainScreenPreview() {
    MainScreen(
        mantras = listOf("Om Namah Shivaya", "Gayatri Mantra", "Mahamrityunjaya"),
        matchLimitText = "5",
        onMatchLimitTextChange = {},
        onRecordMantraClick = {},
        onStartStopClick = { _, _ -> },
        onStopRecordingClick = {},
        matchCount = 3,
        processingStatus = "Listening for mantra..."
    )
}