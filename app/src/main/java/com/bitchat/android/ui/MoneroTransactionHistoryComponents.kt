package com.bitchat.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.monero.wallet.WalletSuite
import com.m2049r.xmrwallet.model.TransactionInfo
import com.m2049r.xmrwallet.model.Transfer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoneroTransactionHistorySheet(
    isPresented: Boolean,
    transactions: List<TransactionInfo>,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTransaction by remember { mutableStateOf<TransactionInfo?>(null) }
    LaunchedEffect(isPresented) {
        if (!isPresented) selectedTransaction = null
    }

    if (!isPresented) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(0.94f)
    ) {
        if (selectedTransaction == null) {
            TransactionHistoryList(
                transactions = transactions,
                isLoading = isLoading,
                error = error,
                onRefresh = onRefresh,
                onDismiss = onDismiss,
                onTransactionClick = { selectedTransaction = it }
            )
        } else {
            TransactionDetails(
                transaction = selectedTransaction!!,
                onBack = { selectedTransaction = null },
                onDismiss = onDismiss
            )
        }
    }
}

@Composable
private fun TransactionHistoryList(
    transactions: List<TransactionInfo>,
    isLoading: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    onTransactionClick: (TransactionInfo) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HistoryTopBar(
            title = "Transaction History",
            leadingIcon = Icons.Default.History,
            onLeadingClick = null,
            onRefresh = onRefresh,
            isRefreshing = isLoading,
            onDismiss = onDismiss
        )
        HorizontalDivider()

        when {
            isLoading && transactions.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Loading transaction history…")
                }
            }
            error != null && transactions.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = onRefresh) { Text("Retry") }
                }
            }
            transactions.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No transactions yet")
                    Text(
                        text = "Your received and sent Monero transactions will appear here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            else -> {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    if (isLoading || error != null) {
                        item(key = "history_status") {
                            Text(
                                text = if (isLoading) "Refreshing…" else error.orEmpty(),
                                color = if (error != null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                            )
                        }
                    }
                    items(
                        items = transactions,
                        key = { "${it.hash}:${it.timestamp}" }
                    ) { transaction ->
                        TransactionHistoryRow(
                            transaction = transaction,
                            onClick = { onTransactionClick(transaction) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryTopBar(
    title: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onLeadingClick: (() -> Unit)?,
    onRefresh: (() -> Unit)?,
    isRefreshing: Boolean = false,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onLeadingClick != null) {
            IconButton(onClick = onLeadingClick) {
                Icon(leadingIcon, contentDescription = "Back")
            }
        } else {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (onRefresh != null) {
            IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh transaction history")
                }
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun TransactionHistoryRow(
    transaction: TransactionInfo,
    onClick: () -> Unit
) {
    val isOutgoing = transaction.direction == TransactionInfo.Direction.Direction_Out
    val amount = effectiveNetAmount(transaction)
    val amountText = "${if (isOutgoing) "−" else "+"}${WalletSuite.convertAtomicToXmr(amount)} XMR"
    val amountColor = if (isOutgoing) MaterialTheme.colorScheme.onSurface
    else Color(0xFF2E7D32)
    val status = when {
        transaction.isFailed -> "Failed"
        transaction.isPending -> "Pending"
        transaction.isConfirmed() -> "${transaction.confirmations} confirmations"
        else -> "${transaction.confirmations} confirmations"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isOutgoing) "Sent" else "Received",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = formatTransactionDate(transaction.timestamp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = transaction.hash.orEmpty(),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = amountText,
                color = amountColor,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = if (transaction.isFailed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(start = 20.dp))
}

@Composable
private fun TransactionDetails(
    transaction: TransactionInfo,
    onBack: () -> Unit,
    onDismiss: () -> Unit
) {
    val transfers = transaction.transfers.orEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        HistoryTopBar(
            title = "Transaction Details",
            leadingIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onLeadingClick = onBack,
            onRefresh = null,
            onDismiss = onDismiss
        )
        HorizontalDivider()
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                DetailValue("Status", transactionStatus(transaction))
                DetailValue(
                    "Direction",
                    if (transaction.direction == TransactionInfo.Direction.Direction_Out) "Sent" else "Received"
                )
                DetailValue("Amount", "${WalletSuite.convertAtomicToXmr(transaction.amount)} XMR")
                DetailValue("Net amount", "${WalletSuite.convertAtomicToXmr(effectiveNetAmount(transaction))} XMR")
                DetailValue("Fee", "${WalletSuite.convertAtomicToXmr(transaction.fee)} XMR")
                if (transaction.direction == TransactionInfo.Direction.Direction_Out) {
                    DetailValue(
                        "Pocket change",
                        "${WalletSuite.convertAtomicToXmr(transaction.getPocketChangeAmount())} XMR"
                    )
                }
                DetailValue("Block height", transaction.blockheight.toString())
                DetailValue("Confirmations", transaction.confirmations.toString())
                DetailValue("Timestamp", formatTransactionDate(transaction.timestamp))
                DetailValue("Unlock time", transaction.unlockTime.toString())
                DetailValue("Account index", transaction.accountIndex.toString())
                DetailValue("Address index", transaction.addressIndex.toString())
                transaction.paymentId.takeUnless { it.isNullOrBlank() }?.let {
                    DetailValue("Payment ID", it, monospace = true)
                }
                transaction.subaddressLabel.takeUnless { it.isNullOrBlank() }?.let {
                    DetailValue("Subaddress label", it)
                }
                transaction.address.takeUnless { it.isNullOrBlank() }?.let {
                    DetailValue("Address", it, monospace = true)
                }
                transaction.txKey.takeUnless { it.isNullOrBlank() }?.let {
                    DetailValue("Transaction key", it, monospace = true)
                }
                transaction.notes.takeUnless { it.isNullOrBlank() }?.let {
                    DetailValue("Notes", it)
                }
                DetailValue("Transaction ID", transaction.hash.orEmpty(), monospace = true)
            }
            if (transfers.isNotEmpty()) {
                item {
                    Text(
                        text = "Transfers",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
                    )
                }
                items(transfers) { transfer ->
                    TransferDetail(transfer)
                }
            }
        }
    }
}

@Composable
private fun DetailValue(label: String, value: String, monospace: Boolean = false) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
                ),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun TransferDetail(transfer: Transfer) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("${WalletSuite.convertAtomicToXmr(transfer.amount)} XMR")
            Text(
                text = transfer.address.orEmpty(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

private fun transactionStatus(transaction: TransactionInfo): String = when {
    transaction.isFailed -> "Failed"
    transaction.isPending -> "Pending"
    transaction.isConfirmed() -> "Confirmed"
    else -> "Unconfirmed"
}

private fun effectiveNetAmount(transaction: TransactionInfo): Long {
    var netAmount = transaction.amount
    if (transaction.direction == TransactionInfo.Direction.Direction_Out) {
        transaction.transfers?.let { transfers ->
            for (index in 1 until transfers.size) {
                netAmount -= transfers[index].amount
            }
        }
    }
    return netAmount
}

private fun formatTransactionDate(timestamp: Long): String {
    if (timestamp <= 0L) return "Unknown date"
    val millis = if (timestamp < 100_000_000_000L) timestamp * 1000L else timestamp
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}
