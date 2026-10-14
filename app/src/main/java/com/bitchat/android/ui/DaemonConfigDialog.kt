package com.bitchat.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bitchat.android.monero.wallet.WalletSuite
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

data class DaemonConfig(
    val host: String = "xmr-node.cakewallet.com",
    val port: String = "18081",
    val username: String = "",
    val password: String = "",
    val ssl: Boolean = false,
    val autoSync: Boolean = true,
    val networkType: String = "0"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaemonConfigDialog(
    isVisible: Boolean,
    initialConfig: DaemonConfig = DaemonConfig(),
    onDismiss: () -> Unit,
    onSave: (DaemonConfig) -> Unit,
    isLoading: Boolean = false
) {
    if (!isVisible) return

    var host by remember { mutableStateOf(initialConfig.host) }
    var port by remember { mutableStateOf(initialConfig.port) }
    var username by remember { mutableStateOf(initialConfig.username) }
    var password by remember { mutableStateOf(initialConfig.password) }
    var ssl by remember { mutableStateOf(initialConfig.ssl) }
    var autoSync by remember { mutableStateOf(initialConfig.autoSync) }
    var networkType by remember { mutableStateOf(initialConfig.networkType) }
    var showPassword by remember { mutableStateOf(false) }
    var isTestingConnection by remember { mutableStateOf(false) }

    val isValidConfig = host.isNotBlank() && 
                       port.isNotBlank() && 
                       port.toIntOrNull() != null &&
                       port.toInt() in 1..65535

    Dialog(
        onDismissRequest = { if (!isLoading) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isLoading,
            dismissOnClickOutside = !isLoading,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Daemon Configuration",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "Configure your Monero daemon connection settings",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                HorizontalDivider()

                // Host field
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim() },
                    label = { Text("Daemon Host") },
                    placeholder = { Text("xmr-node.cakewallet.com") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading
                )

                // Port field
                OutlinedTextField(
                    value = port,
                    onValueChange = { 
                        if (it.all { char -> char.isDigit() }) {
                            port = it
                        }
                    },
                    label = { Text("Port") },
                    placeholder = { Text("18089") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isLoading,
                    isError = port.isNotBlank() && (port.toIntOrNull() == null || port.toInt() !in 1..65535),
                    supportingText = {
                        if (port.isNotBlank() && (port.toIntOrNull() == null || port.toInt() !in 1..65535)) {
                            Text("Port must be between 1 and 65535")
                        }
                    }
                )

                // Username field (optional)
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isLoading
                )

                // Password field (optional)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password"
                            )
                        }
                    },
                    enabled = !isLoading
                )

                // SSL checkbox
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = ssl,
                        onCheckedChange = { ssl = it },
                        enabled = !isLoading
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Use SSL/TLS encryption",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // AutoSync checkbox
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = autoSync,
                        onCheckedChange = { autoSync = it },
                        enabled = !isLoading
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Enable Automatic Sync",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                // Network type dropdown
                var expanded by remember { mutableStateOf(false) }
                val networkOptions = listOf("mainnet", "testnet", "stagenet")
                
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it && !isLoading }
                ) {
                    OutlinedTextField(
                        value = networkType,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text("Network Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(),
                        enabled = !isLoading
                    )
                    
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        networkOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    networkType = option
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                HorizontalDivider()

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        enabled = !isLoading
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            val config = DaemonConfig(
                                host = host,
                                port = port,
                                username = username,
                                password = password,
                                ssl = ssl,
                                autoSync = autoSync,
                                networkType = networkType
                            )
                            onSave(config)
                        },
                        modifier = Modifier.weight(1f),
                        enabled = isValidConfig && !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(if (isLoading) "Saving..." else "Save & Connect")
                    }
                }
            }
        }
    }
}

/**
 * Utility function to save daemon configuration to properties file
 * Call this from your ViewModel or WalletSuite
 */
fun saveDaemonConfig(context: Context, config: DaemonConfig): Boolean {
    return try {
        val properties = Properties().apply {
            setProperty("daemon.address", config.host)
            setProperty("daemon.port", config.port)
            setProperty("daemon.ssl", config.ssl.toString())
            setProperty("daemon.autosync", config.autoSync.toString())
            val netInt = when (config.networkType.lowercase()) {
                "testnet" -> "1"
                "stagenet" -> "2"
                else -> "0"
            }
            setProperty("network.type", netInt)
        }

        // Save to external storage first (user accessible)
        val externalFile = File(context.getExternalFilesDir(null), "wallet.properties")
        FileOutputStream(externalFile).use { output ->
            properties.store(output, "Daemon configuration - Updated ${java.util.Date()}")
        }

        // Also save to internal storage as backup
        val internalFile = File(context.filesDir, "wallet.properties")
        FileOutputStream(internalFile).use { output ->
            properties.store(output, "Daemon configuration - Updated ${java.util.Date()}")
        }

        // Save credentials in EncryptedSharedPreferences
        if (config.username.isNotEmpty() || config.password.isNotEmpty()) {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                "daemon_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            encryptedPrefs.edit()
                .putString("daemon.username", config.username)
                .putString("daemon.password", config.password)
                .apply()
        }

        Log.i("DaemonConfig", "Saved daemon config to: ${externalFile.absolutePath}")
        true
    } catch (e: Exception) {
        Log.e("DaemonConfig", "Failed to save daemon config", e)
        false
    }
}

/**
 * Utility function to load existing daemon configuration
 */
fun loadDaemonConfig(context: Context): DaemonConfig {
    return try {
        val properties = Properties()
        
        // Try external file first
        val externalFile = File(context.getExternalFilesDir(null), "wallet.properties")
        if (externalFile.exists()) {
            externalFile.inputStream().use { properties.load(it) }
        } else {
            // Try internal file
            val internalFile = File(context.filesDir, "wallet.properties")
            if (internalFile.exists()) {
                internalFile.inputStream().use { properties.load(it) }
            }
        }

        // Load credentials from EncryptedSharedPreferences
        var username = ""
        var password = ""
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val encryptedPrefs = EncryptedSharedPreferences.create(
                context,
                "daemon_credentials",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            username = encryptedPrefs.getString("daemon.username", "") ?: ""
            password = encryptedPrefs.getString("daemon.password", "") ?: ""
        } catch (e: Exception) {
            Log.w("DaemonConfig", "Could not load encrypted credentials, using empty", e)
        }

        val netProp = properties.getProperty("network.type", "0")
        val netStr = when (netProp) {
            "1" -> "testnet"
            "2" -> "stagenet"
            else -> "mainnet"
        }

        DaemonConfig(
            host = properties.getProperty("daemon.address", "xmr-node.cakewallet.com"),
            port = properties.getProperty("daemon.port", "18081"),
            username = username,
            password = password,
            ssl = properties.getProperty("daemon.ssl", "false").toBoolean(),
            autoSync = properties.getProperty("daemon.autosync", "true").toBoolean(),
            networkType = netStr
        )
    } catch (e: Exception) {
        Log.w("DaemonConfig", "Failed to load daemon config, using defaults", e)
        DaemonConfig() // Return defaults
    }
}
