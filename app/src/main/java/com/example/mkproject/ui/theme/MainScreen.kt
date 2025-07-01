package com.example.mkproject.ui.theme

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

@Composable
fun MainScreen(
    mantras: List<String>,
    onRecordMantraClick: () -> Unit,
    onStartStopClick: (String, String) -> Unit,
    matchCount: Int,
    processingStatus: String,
    matchLimitText: String, // Use the hoisted state
    onMatchLimitTextChange: (String) -> Unit // Update to accept new text value
) {
    var selectedMantra by remember { mutableStateOf(mantras.firstOrNull() ?: "") }
    var isMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Record New Mantra Button
        Button(
            onClick = onRecordMantraClick,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("Record New Mantra", fontSize = 16.sp)
        }

        // Mantra Selection Dropdown
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Selected Mantra: ", fontSize = 16.sp)
            Button(
                onClick = { isMenuExpanded = true },
                modifier = Modifier.weight(1f)
            ) {
                Text(selectedMantra.ifEmpty { "Select Mantra" }, fontSize = 16.sp)
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
                        }
                    )
                }
            }
        }

        // Match Limit Input
        OutlinedTextField(
            value = matchLimitText, // Use hoisted matchLimitText
            onValueChange = onMatchLimitTextChange, // Pass new value to parent
            label = { Text("Match Limit", fontSize = 14.sp) },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        // Start/Stop Button
        Button(
            onClick = { onStartStopClick(selectedMantra, matchLimitText) }, // Pass matchLimitText
            modifier = Modifier.align(Alignment.CenterHorizontally),
            enabled = selectedMantra.isNotEmpty() && matchLimitText.isNotEmpty()
        ) {
            Text(if (processingStatus == "Listening for mantra...") "STOP" else "START", fontSize = 16.sp)
        }

        // Match Count Display
        Text(
            text = "Match Count: $matchCount",
            fontSize = 18.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        // Processing Status
        Text(
            text = "Status: $processingStatus",
            fontSize = 16.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}
@Preview
@Composable
fun MainScreenPreview() {
    MainScreen(
        mantras = listOf("Om Namah Shivaya", "Gayatri Mantra", "Mahamrityunjaya"),
        onRecordMantraClick = {},
        onStartStopClick = { _, _ -> },
        matchCount = 3,
        processingStatus = "Listening for mantra...",
        matchLimitText = "5",
        onMatchLimitTextChange = {}
    )
}