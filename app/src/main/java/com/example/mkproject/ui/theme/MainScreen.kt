package com.example.mkproject.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    onRecordMantraClick: (String) -> Unit,
    onStartStopClick: (String, String) -> Unit,
    onStopRecordingClick: () -> Unit,
    onDeleteMantraClick: (String) -> Unit,
    matchCount: Int,
    processingStatus: String
) {
    var selectedMantra by remember { mutableStateOf(mantras.firstOrNull() ?: "") }
    var isMenuExpanded by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showRecordDialog by remember { mutableStateOf(false) }
    var newMantraName by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(mantras) {
        if (selectedMantra !in mantras && mantras.isNotEmpty()) {
            selectedMantra = mantras.first()
        } else if (mantras.isEmpty()) {
            selectedMantra = ""
        }
    }

    if (showRecordDialog) {
        RecordDialog(
            newMantraName = newMantraName,
            onNameChange = { newMantraName = it.trim().take(50) },
            mantras = mantras,
            onConfirm = {
                onRecordMantraClick(newMantraName)
                showRecordDialog = false
                newMantraName = ""
                errorMessage = ""
            },
            onDismiss = {
                showRecordDialog = false
                newMantraName = ""
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Mantra") },
            text = { Text("Are you sure you want to delete '$selectedMantra'? This cannot be undone.") },
            confirmButton = {
                Button(onClick = {
                    onDeleteMantraClick(selectedMantra)
                    showDeleteDialog = false
                    errorMessage = ""
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                Button(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Mantra Recognition App", fontSize = 20.sp)
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
                Button(
                    onClick = {
                        errorMessage = ""
                        showRecordDialog = true
                    },
                    enabled = processingStatus != "Listening for mantra..." && processingStatus != "Recording mantra...",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Record New Mantra", fontSize = 16.sp)
                }

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
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }

                Button(
                    onClick = {
                        if (selectedMantra.isEmpty()) {
                            errorMessage = "Please select a mantra to delete."
                            return@Button
                        }
                        errorMessage = ""
                        showDeleteDialog = true
                    },
                    enabled = selectedMantra.isNotEmpty() && processingStatus != "Listening for mantra..." && processingStatus != "Recording mantra...",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Delete Selected Mantra", fontSize = 16.sp)
                }

                // Mantra Dropdown
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Select Mantra", fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))

                    ExposedDropdownMenuBox(
                        expanded = isMenuExpanded,
                        onExpandedChange = { isMenuExpanded = !isMenuExpanded }
                    ) {
                        OutlinedTextField(
                            value = selectedMantra,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Mantra") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isMenuExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
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
                        if (it.matches(Regex("^\\d{0,3}$"))) {
                            onMatchLimitTextChange(it)
                            errorMessage = ""
                        }
                    },
                    label = { Text("Match Limit", fontSize = 14.sp) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = matchLimitText.isNotEmpty() && matchLimitText.toIntOrNull()?.let { it <= 0 } == true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                }

                // Start/Stop Recognition
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

                // Match Count
                Text("Match Count: $matchCount", fontSize = 18.sp)

                // Progress Bar
                if (matchLimitText.toIntOrNull() != null && matchLimitText.toInt() > 0) {
                    val progress = (matchCount.toFloat() / matchLimitText.toInt().toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                }

                // Status
                Text("Status: $processingStatus", fontSize = 16.sp)
            }
        }
    )
}

@Composable
fun RecordDialog(
    newMantraName: String,
    onNameChange: (String) -> Unit,
    mantras: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val nameExists = mantras.contains(newMantraName)
    val isNameEmpty = newMantraName.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name Your Mantra") },
        text = {
            Column {
                Text("Enter a name for the new mantra recording:")
                OutlinedTextField(
                    value = newMantraName,
                    onValueChange = onNameChange,
                    label = { Text("Mantra Name") },
                    modifier = Modifier.fillMaxWidth(),
                    isError = isNameEmpty || nameExists,
                    singleLine = true
                )
                if (isNameEmpty) {
                    Text(
                        "Name cannot be empty.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                } else if (nameExists) {
                    Text(
                        "Name already exists. Try: ${newMantraName}_${mantras.size + 1}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isNameEmpty && !nameExists
            ) {
                Text("Start Recording")
            }
        },
        dismissButton = {
            Button(onClick = onDismiss) {
                Text("Cancel")
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
        onRecordMantraClick = { _ -> },
        onStartStopClick = { _, _ -> },
        onStopRecordingClick = {},
        onDeleteMantraClick = { _ -> },
        matchCount = 3,
        processingStatus = "Listening for mantra..."
    )
}
