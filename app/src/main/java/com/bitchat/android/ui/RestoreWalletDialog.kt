package com.bitchat.android.ui

import android.view.WindowManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreWalletDialog(
    isVisible: Boolean,
    defaultBirthdayHeight: Long = 0L,
    onDismiss: () -> Unit,
    onRestore: (seed: String, birthdayBlock: Long) -> Unit,
    isLoading: Boolean = false
) {
    if (!isVisible) return

    var seedText by remember { mutableStateOf("") }
    var birthdayHeightText by remember { mutableStateOf(if (defaultBirthdayHeight > 0) defaultBirthdayHeight.toString() else "0") }
    var confirmDelete by remember { mutableStateOf(false) }

    val validationResult = remember(seedText) {
        if (seedText.isBlank()) null
        else com.bitchat.android.monero.utils.MoneroMnemonic.validateAndPrepareSeed(seedText)
    }
    val isValidSeed = validationResult?.isValid ?: false
    val isValidHeight = birthdayHeightText.toLongOrNull() != null && birthdayHeightText.toLong() >= 0
    val canSubmit = isValidSeed && isValidHeight && confirmDelete && !isLoading
    val dialogScrollState = rememberScrollState()
    val dialogFieldTextColor = MaterialTheme.colorScheme.onSurface
    val dialogFieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = dialogFieldTextColor,
        unfocusedTextColor = dialogFieldTextColor,
        errorTextColor = dialogFieldTextColor,
        disabledTextColor = dialogFieldTextColor.copy(alpha = 0.38f)
    )

    Dialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isLoading,
            dismissOnClickOutside = !isLoading,
            usePlatformDefaultWidth = false,
            // Let Compose receive IME insets for the dialog window. Without
            // this, the keyboard overlays the bottom actions instead of
            // shrinking the available dialog viewport.
            decorFitsSystemWindows = false
        )
    ) {
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window

        SideEffect {
            // The activity is edge-to-edge, so explicitly opt this dialog
            // into resize-on-IME. Otherwise Android leaves the dialog at its
            // full height and the keyboard covers the action row.
            dialogWindow?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }

        // The dialog window is edge-to-edge. Apply the IME inset to the
        // container that positions the card, otherwise the card is centered
        // using the full screen height and its action row ends up behind the
        // keyboard on smaller phones.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .widthIn(max = 520.dp)
                    .heightIn(max = 640.dp),
                shape = RectangleShape,
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(dialogScrollState)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Restore wallet",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Replaces the current wallet and rescans from the selected block.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    OutlinedTextField(
                        value = seedText,
                        onValueChange = { seedText = it },
                        label = { Text("Seed phrase") },
                        placeholder = { Text("Enter 25 words") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = dialogFieldTextColor),
                        colors = dialogFieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 128.dp, max = 224.dp),
                        minLines = 4,
                        maxLines = 8,
                        enabled = !isLoading,
                        isError = seedText.isNotBlank() && !isValidSeed,
                        supportingText = {
                            if (seedText.isNotBlank()) {
                                val wordCount = seedText.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.size
                                Text(
                                    if (validationResult != null && !validationResult.isValid) {
                                        validationResult.errorMessage
                                    } else {
                                        "$wordCount words"
                                    }
                                )
                            }
                        }
                    )

                    OutlinedTextField(
                        value = birthdayHeightText,
                        onValueChange = { birthdayHeightText = it.filter { char -> char.isDigit() } },
                        label = { Text("Restore height") },
                        placeholder = { Text("0 = scan from beginning") },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = dialogFieldTextColor),
                        colors = dialogFieldColors,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading,
                        isError = birthdayHeightText.isBlank()
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = confirmDelete,
                            onCheckedChange = { confirmDelete = it },
                            enabled = !isLoading
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column {
                            Text(
                                text = "Replace current wallet",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Existing wallet data will be removed",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !isLoading
                        ) {
                            Text("Cancel")
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                val height = birthdayHeightText.toLongOrNull() ?: 0L
                                onRestore(seedText.trim(), height)
                            },
                            enabled = canSubmit,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Loading...")
                            } else {
                                Text("Restore")
                            }
                        }
                    }
                }
            }
        }
    }
}
